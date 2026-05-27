package elastic.rag.summary;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.GetResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import elastic.rag.model.DocumentRecord;
import elastic.rag.model.DocumentSummary;
import elastic.rag.model.SummaryStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * REST controller per la gestione dei summary.
 *
 * Endpoints:
 *   POST /summary/{docId}  — richiede la generazione del summary (asincrona)
 *   GET  /summary/{docId}  — legge stato e contenuto del summary
 */
@RestController
@RequestMapping("/summary")
public class SummaryController {

    private static final Logger log = LoggerFactory.getLogger(SummaryController.class);

    private final SummaryService        summaryService;
    private final DocumentStoreService  documentStoreService;
    private final ElasticsearchClient   esClient;
    private final ObjectMapper          objectMapper;
    private final String                summaryIndex;

    public SummaryController(SummaryService summaryService,
                             DocumentStoreService documentStoreService,
                             ElasticsearchClient esClient,
                             ObjectMapper objectMapper,
                             @Value("${summary.index-name:summary-index}") String summaryIndex) {
        this.summaryService       = summaryService;
        this.documentStoreService = documentStoreService;
        this.esClient             = esClient;
        this.objectMapper         = objectMapper;
        this.summaryIndex         = summaryIndex;
    }

    /**
     * POST /summary/{docId}
     *
     * Avvia la generazione asincrona del summary per il documento indicato.
     * Risponde immediatamente con status=PENDING.
     * Se il summary è già in corso (PENDING/PROCESSING) non rilancia un nuovo job.
     * Se era già COMPLETED o FAILED, rilancia la generazione.
     *
     * @return 202 Accepted con { docId, status, message }
     *         404 se il documento non esiste nel document-store
     */
    @PostMapping("/{docId}")
    public ResponseEntity<Map<String, Object>> requestSummary(@PathVariable String docId) {
        Optional<DocumentRecord> recordOpt = documentStoreService.findByDocId(docId);
        if (recordOpt.isEmpty()) {
            log.warn("[SUMMARY-CTRL] docId={} non trovato nel document-store", docId);
            return ResponseEntity.notFound().build();
        }

        SummaryStatus currentStatus = recordOpt.get().summaryStatus();
        if (currentStatus == SummaryStatus.PENDING || currentStatus == SummaryStatus.PROCESSING) {
            log.info("[SUMMARY-CTRL] summary già in corso per docId={} ({})", docId, currentStatus);
            return ResponseEntity.ok(Map.of(
                    "docId",   docId,
                    "status",  currentStatus.name(),
                    "message", "Summary già in corso, riprova tra qualche istante"
            ));
        }

        documentStoreService.updateSummaryStatus(docId, SummaryStatus.PENDING,
                Instant.now().toString(), null, null);
        summaryService.generate(docId);

        log.info("[SUMMARY-CTRL] generazione avviata per docId={}", docId);
        return ResponseEntity.accepted().body(Map.of(
                "docId",   docId,
                "status",  SummaryStatus.PENDING.name(),
                "message", "Generazione summary avviata in background"
        ));
    }

    /**
     * GET /summary/{docId}
     *
     * Legge lo stato corrente del summary.
     * Se status=COMPLETED restituisce il DocumentSummary completo (fullSummary + sectionSummaries).
     * Altrimenti restituisce solo i metadati di stato.
     *
     * @return 200 con DocumentSummary (se COMPLETED) o mappa di stato
     *         404 se il documento non esiste nel document-store
     */
    @GetMapping("/{docId}")
    public ResponseEntity<?> getSummary(@PathVariable String docId) {
        Optional<DocumentRecord> recordOpt = documentStoreService.findByDocId(docId);
        if (recordOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        DocumentRecord record = recordOpt.get();

        if (record.summaryStatus() != SummaryStatus.COMPLETED) {
            return ResponseEntity.ok(Map.of(
                    "docId",               docId,
                    "fileName",            record.fileName() != null ? record.fileName() : "",
                    "status",              record.summaryStatus().name(),
                    "summaryRequestedAt",  record.summaryRequestedAt()  != null ? record.summaryRequestedAt()  : "",
                    "summaryError",        record.summaryError()         != null ? record.summaryError()         : ""
            ));
        }

        try {
            GetResponse<ObjectNode> resp = esClient.get(
                    g -> g.index(summaryIndex).id(docId), ObjectNode.class);

            if (!resp.found() || resp.source() == null) {
                log.warn("[SUMMARY-CTRL] summary-index mancante per docId={} nonostante status=COMPLETED", docId);
                return ResponseEntity.ok(Map.of(
                        "docId",   docId,
                        "status",  "COMPLETED",
                        "message", "Summary segnato come completato ma non trovato nell'indice"
                ));
            }

            DocumentSummary summary = objectMapper.treeToValue(resp.source(), DocumentSummary.class);
            // Aggiunge "status" alla risposta così il polling script può rilevare COMPLETED
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.convertValue(summary, Map.class);
            result.put("status", SummaryStatus.COMPLETED.name());
            log.info("[SUMMARY-CTRL] summary restituito per docId={}", docId);
            return ResponseEntity.ok(result);

        } catch (IOException e) {
            log.error("[SUMMARY-CTRL] errore lettura summary per docId={}: {}", docId, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Errore lettura summary: " + e.getMessage()));
        }
    }

    /**
     * DELETE /summary/{docId}
     *
     * Elimina il summary di un documento dall'indice summary-index
     * e resetta summaryStatus=NONE nel document-store.
     * Il documento rimane indicizzato nel vector store e consultabile via /docling/ask.
     *
     * @return 200 con { docId, message }
     *         404 se il documento non esiste nel document-store
     */
    @DeleteMapping("/{docId}")
    public ResponseEntity<Map<String, Object>> deleteSummary(@PathVariable String docId) {
        if (documentStoreService.findByDocId(docId).isEmpty()) {
            log.warn("[SUMMARY-CTRL] DELETE summary: docId={} non trovato", docId);
            return ResponseEntity.notFound().build();
        }
        documentStoreService.deleteSummary(docId);
        log.info("[SUMMARY-CTRL] summary eliminato per docId={}", docId);
        return ResponseEntity.ok(Map.of(
                "docId",   docId,
                "message", "Summary eliminato. Status reimpostato a NONE."
        ));
    }
}
