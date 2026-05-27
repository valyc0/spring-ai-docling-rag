package elastic.rag.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Documento persistito nell'indice Elasticsearch "document-store".
 * Contiene il documento normalizzato (UnifiedDocumentJson) e lo stato
 * del ciclo di vita del summary associato.
 *
 * Ciclo di vita summaryStatus:
 *   NONE → PENDING → PROCESSING → COMPLETED
 *                              → FAILED (+ summaryError)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocumentRecord(
        String docId,
        String fileName,
        String sourceType,
        /** ISO-8601 timestamp di indicizzazione. */
        String indexedAt,
        SummaryStatus summaryStatus,
        /** ISO-8601: quando è stato richiesto il summary. */
        String summaryRequestedAt,
        /** ISO-8601: quando il summary è stato completato. */
        String summaryCompletedAt,
        /** Messaggio di errore in caso di FAILED. */
        String summaryError,
        /** Documento normalizzato: sezioni, titoli, testi. */
        UnifiedDocumentJson unifiedDocument
) {}
