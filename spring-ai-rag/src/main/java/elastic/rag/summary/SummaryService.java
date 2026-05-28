package elastic.rag.summary;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import elastic.rag.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Genera il summary di un documento tramite strategia Map-Reduce semantica.
 *
 * Strategia selezionata automaticamente in base alla lunghezza totale del testo:
 *
 *   STUFFING    : testo totale <= stuffingThresholdChars
 *                 → tutto in un singolo prompt
 *
 *   MAP-REDUCE  : testo totale > stuffingThresholdChars
 *                 → Map: riassume ogni sezione indipendentemente
 *                 → Reduce: produce il summary finale dai section-summary
 *
 *   REFINE      : fallback per singola sezione > sectionRefineThresholdChars
 *                 → suddivide la sezione in sotto-chunk e aggiorna il
 *                   riassunto in modo incrementale prima della fase Map
 *
 * La generazione è asincrona (@Async): il chiamante riceve subito una risposta
 * e il processo procede in background aggiornando lo stato nel document-store.
 */
@Service
public class SummaryService {

    private static final Logger log = LoggerFactory.getLogger(SummaryService.class);

    private final ChatClient chatClient;
    private final DocumentStoreService documentStoreService;
    private final ElasticsearchClient esClient;
    private final ObjectMapper objectMapper;
    private final String summaryIndex;
    private final int stuffingThresholdChars;
    private final int sectionRefineThresholdChars;

    public SummaryService(ChatClient.Builder chatClientBuilder,
                          DocumentStoreService documentStoreService,
                          ElasticsearchClient esClient,
                          ObjectMapper objectMapper,
                          @Value("${summary.index-name:summary-index}") String summaryIndex,
                          @Value("${summary.stuffing-threshold-chars:6000}") int stuffingThresholdChars,
                          @Value("${summary.section-refine-threshold-chars:3000}") int sectionRefineThresholdChars) {
        this.chatClient                  = chatClientBuilder.build();
        this.documentStoreService        = documentStoreService;
        this.esClient                    = esClient;
        this.objectMapper                = objectMapper;
        this.summaryIndex                = summaryIndex;
        this.stuffingThresholdChars      = stuffingThresholdChars;
        this.sectionRefineThresholdChars = sectionRefineThresholdChars;
    }

    /**
     * Genera il summary del documento identificato da docId.
     * Viene eseguito in modo asincrono: aggiorna lo stato nel document-store
     * e, al termine, persiste il risultato nell'indice summary-index.
     */
    @Async
    public CompletableFuture<Void> generate(String docId) {
        log.info("[SUMMARY] avvio generazione per docId={}", docId);
        documentStoreService.updateSummaryStatus(docId, SummaryStatus.PROCESSING, null, null, null);

        try {
            DocumentRecord record = documentStoreService.findByDocId(docId)
                    .orElseThrow(() -> new IllegalStateException("Documento non trovato in document-store: " + docId));

            List<DocumentSection> sections = record.unifiedDocument().sections().stream()
                    .filter(s -> s.text() != null && !s.text().isBlank())
                    .toList();

            if (sections.isEmpty()) {
                throw new IllegalStateException("Nessuna sezione con testo per docId=" + docId);
            }

            // Conta i caratteri totali di tutte le sezioni per scegliere la strategia
            int totalChars = sections.stream().mapToInt(s -> s.text().length()).sum();
            log.info("[SUMMARY] {} sezioni — {} char totali — file='{}'",
                    sections.size(), totalChars, record.fileName());

            List<SectionSummary> sectionSummaries;
            String fullSummary;

            if (totalChars <= stuffingThresholdChars) {
                // STUFFING: documento piccolo → tutto il testo in un unico prompt, 1 sola chiamata LLM
                log.info("[SUMMARY] strategia=STUFFING ({} char <= soglia {})", totalChars, stuffingThresholdChars);
                String allText = sections.stream()
                        .map(s -> (s.title() != null ? "## " + s.title() + "\n" : "") + s.text())
                        .collect(Collectors.joining("\n\n"));  // concatena tutte le sezioni
                fullSummary     = callLlmForFullSummary(allText, record.fileName());
                sectionSummaries = List.of();  // nessun summary per sezione: non necessario
            } else {
                // MAP-REDUCE: documento grande → elabora sezione per sezione, poi aggrega
                log.info("[SUMMARY] strategia=MAP-REDUCE ({} char > soglia {})", totalChars, stuffingThresholdChars);
                sectionSummaries = mapPhase(sections);             // MAP:    1 SectionSummary per sezione
                fullSummary      = reducePhase(sectionSummaries,   // REDUCE: 1 summary finale
                                               record.fileName());
            }

            DocumentSummary summary = new DocumentSummary(
                    docId,
                    record.fileName(),
                    fullSummary,
                    sectionSummaries,
                    Instant.now().toString()
            );

            persistSummary(docId, summary);
            documentStoreService.updateSummaryStatus(docId, SummaryStatus.COMPLETED,
                    null, Instant.now().toString(), null);
            log.info("[SUMMARY] completato con successo per docId={}", docId);

        } catch (Exception e) {
            log.error("[SUMMARY] errore per docId={}: {}", docId, e.getMessage(), e);
            documentStoreService.updateSummaryStatus(docId, SummaryStatus.FAILED,
                    null, null, e.getMessage());
        }

        return CompletableFuture.completedFuture(null);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // MAP phase
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Fase Map: riassume ogni sezione indipendentemente.
     * Per sezioni che superano sectionRefineThresholdChars applica Refine prima.
     */
    private List<SectionSummary> mapPhase(List<DocumentSection> sections) {
        List<SectionSummary> results = new ArrayList<>(sections.size());
        for (int i = 0; i < sections.size(); i++) {
            DocumentSection section = sections.get(i);
            log.info("[SUMMARY][MAP] {}/{} — title='{}' chars={}",
                    i + 1, sections.size(), section.title(), section.text().length());

            // Se la sezione è troppo grande per una singola chiamata LLM → Refine (chunk incrementali)
            // Altrimenti → 1 chiamata LLM diretta sulla sezione intera
            String sectionSummary = section.text().length() > sectionRefineThresholdChars
                    ? refineSection(section)                                       // sezione grande: Refine
                    : callLlmForSectionSummary(section.title(), section.text());   // sezione piccola: diretta

            results.add(new SectionSummary(
                    section.sectionId(),   // id della sezione originale
                    section.title(),       // titolo (es. "Capitolo 3")
                    section.pageNumber(),  // pagina nel documento originale
                    sectionSummary         // testo del summary prodotto dall'LLM
            ));
        }
        return results;  // lista: 1 SectionSummary per ogni DocumentSection → input per reducePhase
    }

    // ──────────────────────────────────────────────────────────────────────────
    // REFINE fallback (sezioni troppo lunghe)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Strategia Refine per sezioni che superano sectionRefineThresholdChars.
     * Divide la sezione in sotto-chunk con overlap del 10% e aggiorna
     * il riassunto incrementalmente.
     */
    private String refineSection(DocumentSection section) {
        log.info("[SUMMARY][REFINE] sezione '{}' ({} chars) supera soglia {}",
                section.title(), section.text().length(), sectionRefineThresholdChars);

        String text    = section.text();
        int    overlap = sectionRefineThresholdChars / 10;  // overlap = 10% della soglia (es. 300 chars)
        List<String> chunks = new ArrayList<>();

        // Taglia la sezione in sotto-chunk da sectionRefineThresholdChars chars con overlap
        // L'overlap evita di perdere contesto al confine tra chunk consecutivi
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + sectionRefineThresholdChars, text.length());
            chunks.add(text.substring(start, end));  // estrae il chunk
            if (end == text.length()) break;          // ultimo chunk: fermati
            start = end - overlap;                    // prossimo chunk parte <overlap> chars prima della fine
        }

        log.info("[SUMMARY][REFINE] {} sotto-chunk per la sezione '{}'", chunks.size(), section.title());

        // Primo chunk: genera il summary iniziale con una chiamata LLM normale
        String currentSummary = callLlmForSectionSummary(section.title(), chunks.get(0));

        // Chunk 2..N: ogni iterazione aggiorna il summary con il nuovo testo
        // Nel contesto LLM entrano solo: summary corrente (~200 chars) + chunk corrente (~3000 chars)
        // Il summary viene riscritto ad ogni step — non si accumula, rimane sempre compatto
        for (int i = 1; i < chunks.size(); i++) {
            String sectionLabel = section.title() != null ? section.title() : "senza titolo";
            String prompt = """
                    Hai questo riassunto parziale della sezione "%s":
                    %s
                    
                    Integra le seguenti informazioni aggiuntive aggiornando il riassunto:
                    %s
                    
                    Rispondi con il riassunto aggiornato in italiano, in 3-5 punti chiave.
                    """.formatted(sectionLabel, currentSummary, chunks.get(i));
            currentSummary = chatClient.prompt().user(prompt).call().content();  // sovrascrive, non appende
            log.info("[SUMMARY][REFINE] chunk {}/{} completato", i + 1, chunks.size());
        }
        return currentSummary;  // summary finale della sezione → entra nella fase Map come SectionSummary
    }

    // ──────────────────────────────────────────────────────────────────────────
    // REDUCE phase
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Fase Reduce: combina tutti i section-summary nel summary finale del documento.
     */
    private String reducePhase(List<SectionSummary> sectionSummaries, String fileName) {
        log.info("[SUMMARY][REDUCE] riduzione di {} section-summary per '{}'",
                sectionSummaries.size(), fileName);

        // Costruisce un unico testo con tutti i section-summary, uno per sezione
        // Formato: "### Titolo sezione (pag. N)\n• punto 1\n• punto 2..."
        // ⚠️ LIMITE ATTUALE: se il testo combinato supera la context window del modello
        //    (es. documento con 50+ sezioni) questa singola chiamata LLM fallirà.
        //    Soluzione futura: hierarchical reduce (riduzione a livelli ricorsivi)
        String combined = sectionSummaries.stream()
                .map(ss -> {
                    String header = ss.title() != null ? "### " + ss.title() : "### (senza titolo)";
                    String page   = ss.pageNumber() != null ? " (pag. " + ss.pageNumber() + ")" : "";
                    return header + page + "\n" + ss.summary();  // es: "### Cap. 1 (pag. 3)\n• ..."
                })
                .collect(Collectors.joining("\n\n"));  // separa ogni section-summary con riga vuota

        return callLlmForFullSummary(combined, fileName);  // 1 chiamata LLM → fullSummary del documento
    }

    // ──────────────────────────────────────────────────────────────────────────
    // LLM calls
    // ──────────────────────────────────────────────────────────────────────────

    private String callLlmForSectionSummary(String title, String text) {
        String label  = title != null ? "\"" + title + "\"" : "senza titolo";
        String prompt = """
                Riassumi la seguente sezione del documento intitolata %s.
                Rispondi in italiano con 3-5 punti chiave in forma di elenco puntato.
                Sii conciso: cattura solo le informazioni essenziali.
                
                TESTO:
                %s
                """.formatted(label, text);
        return chatClient.prompt().user(prompt).call().content();
    }

    private String callLlmForFullSummary(String text, String fileName) {
        String prompt = """
                Produci un riassunto completo del documento "%s".
                Rispondi in italiano con questa struttura:
                1. Un paragrafo introduttivo di 2-3 frasi che descriva l'argomento principale.
                2. I punti chiave in forma di elenco puntato (massimo 10 punti).
                3. Una frase conclusiva che sintetizzi il messaggio principale del testo.
                
                CONTENUTO:
                %s
                """.formatted(fileName, text);
        return chatClient.prompt().user(prompt).call().content();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Persistence
    // ──────────────────────────────────────────────────────────────────────────

    private void persistSummary(String docId, DocumentSummary summary) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = objectMapper.convertValue(summary, Map.class);
        esClient.index(i -> i.index(summaryIndex).id(docId).document(doc));
        log.info("[SUMMARY] persistito su indice '{}' per docId={}", summaryIndex, docId);
    }
}
