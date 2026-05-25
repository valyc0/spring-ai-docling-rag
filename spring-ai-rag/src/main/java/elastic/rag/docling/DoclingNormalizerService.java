package elastic.rag.docling;

import elastic.rag.model.DoclingResponse;
import elastic.rag.model.DocumentSection;
import elastic.rag.model.UnifiedDocumentJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Normalizza la risposta grezza di Docling (export_to_dict) nel formato
 * UnifiedDocumentJson comune a tutti i tipi di sorgente.
 *
 * Logica di raggruppamento:
 *  - Ogni elemento con label "section_header" o "title" apre una nuova sezione.
 *  - Gli elementi "text", "paragraph", "list_item", "caption" vengono accumulati
 *    nel testo della sezione corrente.
 *  - Il pageNumber viene letto da prov[0].page_no del primo elemento del gruppo.
 *  - Gli elementi precedenti al primo section_header vengono raccolti
 *    in una sezione "preamble" (senza titolo).
 */
@Service
public class DoclingNormalizerService {

    private static final Logger log = LoggerFactory.getLogger(DoclingNormalizerService.class);

    private static final Set<String> SECTION_LABELS = Set.of("section_header", "title");
    private static final Set<String> TEXT_LABELS    = Set.of("text", "paragraph", "list_item", "caption");

    public UnifiedDocumentJson normalize(DoclingResponse response, String docId, String fileName) {
        log.info("Inizio normalizzazione per docId={}, file={}", docId, fileName);
        List<DocumentSection> sections = extractSections(response.doclingJson());
        log.info("Estratte {} sezioni per docId={}", sections.size(), docId);
        return new UnifiedDocumentJson(docId, fileName, "PDF", sections);
    }

    @SuppressWarnings("unchecked")
    private List<DocumentSection> extractSections(Map<String, Object> doclingJson) {
        List<Map<String, Object>> texts = (List<Map<String, Object>>) doclingJson.get("texts");
        if (texts == null || texts.isEmpty()) {
            log.warn("Nessun elemento 'texts' trovato nel JSON Docling");
            return Collections.emptyList();
        }
        log.info("[NORMALIZER] totale elementi texts da processare: {}", texts.size());
        Map<String, Long> labelCounts = texts.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        t -> String.valueOf(t.get("label")), java.util.stream.Collectors.counting()));
        log.info("[NORMALIZER] distribuzione label: {}", labelCounts);

        List<DocumentSection> sections = new ArrayList<>();
        int sectionIndex = 0;

        // Accumulatori per la sezione corrente
        String        currentTitle = null;
        Integer       currentPage  = null;
        StringBuilder currentText  = new StringBuilder();

        for (Map<String, Object> element : texts) {
            String label = (String) element.get("label");
            String text  = (String) element.get("text");

            if (text == null || text.isBlank()) continue;

            Integer pageNo = extractPageNumber(element);

            if (SECTION_LABELS.contains(label)) {
                // Flush sezione precedente se ha contenuto
                if (!currentText.isEmpty()) {
                    sections.add(buildSection(sectionIndex++, currentTitle, currentPage, currentText.toString().trim()));
                    currentText.setLength(0);
                }
                currentTitle = text;
                currentPage  = pageNo;

            } else if (TEXT_LABELS.contains(label)) {
                // Prima riga di testo: registra la pagina se non ancora impostata
                if (currentPage == null) currentPage = pageNo;
                if (!currentText.isEmpty()) currentText.append("\n");
                currentText.append(text);
            }
            // label sconosciuta (picture, table, ecc.) → ignorata in questa fase
        }

        // Flush ultima sezione
        if (!currentText.isEmpty()) {
            sections.add(buildSection(sectionIndex, currentTitle, currentPage, currentText.toString().trim()));
        }

        return sections;
    }

    @SuppressWarnings("unchecked")
    private Integer extractPageNumber(Map<String, Object> element) {
        List<Map<String, Object>> prov = (List<Map<String, Object>>) element.get("prov");
        if (prov != null && !prov.isEmpty()) {
            Object pageNo = prov.get(0).get("page_no");
            if (pageNo instanceof Number n) return n.intValue();
        }
        return null;
    }

    private DocumentSection buildSection(int index, String title, Integer page, String text) {
        DocumentSection section = new DocumentSection(
                "section-" + index,
                title,
                page,
                null,
                null,
                text,
                Map.of()
        );
        log.info("[NORMALIZER] sezione built \u2014 id={} title='{}' page={} textLen={}",
                section.sectionId(), section.title(), section.pageNumber(),
                text != null ? text.length() : 0);
        return section;
    }
}
