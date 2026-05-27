package elastic.rag.summary;

import elastic.rag.model.SummaryStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Controller per operazioni amministrative trasversali.
 *
 * Endpoints:
 *   DELETE /admin/all  — elimina tutti i documenti da tutti gli indici (reset completo)
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final DocumentStoreService documentStoreService;

    public AdminController(DocumentStoreService documentStoreService) {
        this.documentStoreService = documentStoreService;
    }

    /**
     * DELETE /admin/all
     *
     * Resetta completamente tutti e tre gli indici Elasticsearch usando deleteByQuery
     * con match_all (le mappature/schema vengono preservate, a differenza di
     * una cancellazione dell'indice che richiederebbe il restart dell'app per ricreare
     * lo schema dense_vector).
     *
     * @return 200 con messaggio di conferma
     */
    @DeleteMapping("/all")
    public ResponseEntity<Map<String, Object>> deleteAll() {
        log.warn("[ADMIN] DELETE /admin/all — reset completo di tutti gli indici");
        documentStoreService.deleteAll();
        log.info("[ADMIN] reset completato");
        return ResponseEntity.ok(Map.of(
                "message", "Reset completato: tutti i documenti eliminati da vector store, document-store e summary-index",
                "note",    "Le mappature degli indici sono state preservate (nessun restart necessario)"
        ));
    }
}
