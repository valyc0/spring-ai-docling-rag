package elastic.rag.summary;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.GetResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import elastic.rag.model.DocumentRecord;
import elastic.rag.model.SummaryStatus;
import elastic.rag.model.UnifiedDocumentJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Gestisce la persistenza dei DocumentRecord nell'indice Elasticsearch "document-store".
 *
 * Responsabilità:
 *  - save()                : indicizza un documento normalizzato dopo il parsing
 *  - findByDocId()         : recupera un documento per docId
 *  - updateSummaryStatus() : aggiornamento parziale dei campi summary (partial update)
 */
@Service
public class DocumentStoreService {

    private static final Logger log = LoggerFactory.getLogger(DocumentStoreService.class);

    private final ElasticsearchClient esClient;
    private final ObjectMapper objectMapper;
    private final String documentStoreIndex;
    private final String summaryIndex;
    private final String vectorStoreIndex;

    public DocumentStoreService(ElasticsearchClient esClient,
                                ObjectMapper objectMapper,
                                @Value("${summary.document-store-index:document-store}") String documentStoreIndex,
                                @Value("${summary.index-name:summary-index}") String summaryIndex,
                                @Value("${spring.ai.vectorstore.elasticsearch.index-name:spring-ai-document-index}") String vectorStoreIndex) {
        this.esClient           = esClient;
        this.objectMapper       = objectMapper;
        this.documentStoreIndex = documentStoreIndex;
        this.summaryIndex       = summaryIndex;
        this.vectorStoreIndex   = vectorStoreIndex;
    }

    /**
     * Salva il documento normalizzato con stato summary iniziale NONE.
     * Chiamato subito dopo l'indicizzazione dei chunk nel vector store.
     */
    public void save(String docId, UnifiedDocumentJson unified) {
        DocumentRecord record = new DocumentRecord(
                docId,
                unified.fileName(),
                unified.sourceType(),
                Instant.now().toString(),
                SummaryStatus.NONE,
                null, null, null,
                unified
        );
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> doc = objectMapper.convertValue(record, Map.class);
            esClient.index(i -> i.index(documentStoreIndex).id(docId).document(doc));
            log.info("[DOCUMENT-STORE] salvato docId={} fileName={} sezioni={}",
                    docId, unified.fileName(), unified.sections().size());
        } catch (IOException e) {
            log.error("[DOCUMENT-STORE] errore salvataggio docId={}: {}", docId, e.getMessage(), e);
        }
    }

    /**
     * Recupera un DocumentRecord dall'indice per docId.
     * Restituisce Optional.empty() se non trovato o in caso di errore.
     */
    public Optional<DocumentRecord> findByDocId(String docId) {
        try {
            GetResponse<ObjectNode> resp = esClient.get(
                    g -> g.index(documentStoreIndex).id(docId), ObjectNode.class);
            if (resp.found() && resp.source() != null) {
                DocumentRecord record = objectMapper.treeToValue(resp.source(), DocumentRecord.class);
                return Optional.of(record);
            }
        } catch (IOException e) {
            log.error("[DOCUMENT-STORE] errore get docId={}: {}", docId, e.getMessage(), e);
        }
        return Optional.empty();
    }

    /**
     * Aggiornamento parziale dei campi relativi al summary.
     * I parametri null vengono ignorati (non sovrascrivono il valore esistente).
     */
    public void updateSummaryStatus(String docId, SummaryStatus status,
                                    String requestedAt, String completedAt, String error) {
        Map<String, Object> partial = new HashMap<>();
        partial.put("summaryStatus", status.name());
        if (requestedAt != null) partial.put("summaryRequestedAt", requestedAt);
        if (completedAt != null) partial.put("summaryCompletedAt", completedAt);
        if (error       != null) partial.put("summaryError",       error);

        try {
            esClient.update(u -> u.index(documentStoreIndex).id(docId).doc(partial), Void.class);
            log.info("[DOCUMENT-STORE] status aggiornato docId={} → {}", docId, status);
        } catch (IOException e) {
            log.error("[DOCUMENT-STORE] errore update status docId={}: {}", docId, e.getMessage(), e);
        }
    }

    /**
     * Elimina solo il summary di un documento:
     * rimuove da summary-index e resetta summaryStatus=NONE nel document-store.
     */
    public void deleteSummary(String docId) {
        try {
            esClient.delete(d -> d.index(summaryIndex).id(docId));
            log.info("[DOCUMENT-STORE] summary eliminato da '{}' per docId={}", summaryIndex, docId);
        } catch (IOException e) {
            log.warn("[DOCUMENT-STORE] nessun summary o errore delete su '{}' docId={}: {}",
                    summaryIndex, docId, e.getMessage());
        }
        Map<String, Object> reset = new HashMap<>();
        reset.put("summaryStatus",       SummaryStatus.NONE.name());
        reset.put("summaryRequestedAt",  "");
        reset.put("summaryCompletedAt",  "");
        reset.put("summaryError",        "");
        try {
            esClient.update(u -> u.index(documentStoreIndex).id(docId).doc(reset), Void.class);
            log.info("[DOCUMENT-STORE] summary status reset a NONE per docId={}", docId);
        } catch (IOException e) {
            log.error("[DOCUMENT-STORE] errore reset status docId={}: {}", docId, e.getMessage(), e);
        }
    }

    /**
     * Elimina completamente un documento da tutti e tre gli indici:
     *  1. chunk dal vector store (deleteByQuery su metadata.docId.keyword)
     *  2. record dal document-store
     *  3. summary dall'summary-index (best-effort)
     */
    public void deleteDocument(String docId) throws IOException {
        esClient.deleteByQuery(d -> d
                .index(vectorStoreIndex)
                .query(q -> q.term(t -> t
                        .field("metadata.docId.keyword")
                        .value(v -> v.stringValue(docId)))));
        log.info("[DOCUMENT-STORE] chunk eliminati da '{}' per docId={}", vectorStoreIndex, docId);

        esClient.delete(d -> d.index(documentStoreIndex).id(docId));
        log.info("[DOCUMENT-STORE] record eliminato da '{}' per docId={}", documentStoreIndex, docId);

        try {
            esClient.delete(d -> d.index(summaryIndex).id(docId));
            log.info("[DOCUMENT-STORE] summary eliminato da '{}' per docId={}", summaryIndex, docId);
        } catch (IOException e) {
            log.warn("[DOCUMENT-STORE] summary non presente in '{}' per docId={}", summaryIndex, docId);
        }
    }

    /**
     * Elimina tutti i documenti da tutti e tre gli indici (reset completo).
     * Usa deleteByQuery con match_all per preservare le mappature degli indici.
     */
    public void deleteAll() {
        for (String index : List.of(vectorStoreIndex, documentStoreIndex, summaryIndex)) {
            try {
                esClient.deleteByQuery(d -> d.index(index).query(q -> q.matchAll(m -> m)));
                log.info("[DOCUMENT-STORE] deleteAll completato su indice '{}'", index);
            } catch (Exception e) {
                log.warn("[DOCUMENT-STORE] errore deleteAll su indice '{}': {}", index, e.getMessage());
            }
        }
    }
}
