package elastic.rag.model;

import java.util.Map;

/**
 * Risposta del microservizio Docling (POST /parse).
 * doclingJson è il documento nativo Docling (export_to_dict()) restituito direttamente.
 * markdown è opzionale (null se non fornito dal servizio).
 */
public record DoclingResponse(
        Map<String, Object> doclingJson,
        String markdown
) {}
