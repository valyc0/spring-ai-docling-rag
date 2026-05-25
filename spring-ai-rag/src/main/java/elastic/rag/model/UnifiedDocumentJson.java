package elastic.rag.model;

import java.util.List;

/**
 * JSON normalizzato unificato: formato comune per qualsiasi sorgente documentale
 * (PDF, DOC via Docling — video via trascrizione esterna).
 * Questo oggetto è il contratto verso il ChunkEmbedService.
 */
public record UnifiedDocumentJson(
        String docId,
        String fileName,
        /** PDF | DOC | VIDEO */
        String sourceType,
        List<DocumentSection> sections
) {}
