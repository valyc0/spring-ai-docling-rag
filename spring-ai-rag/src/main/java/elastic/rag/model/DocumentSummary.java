package elastic.rag.model;

import java.util.List;

/**
 * Summary completo di un documento, persistito nell'indice "summary-index".
 * Contiene sia il riassunto globale (fullSummary) prodotto dalla fase Reduce,
 * sia i riassunti per sezione (sectionSummaries) prodotti dalla fase Map.
 * Per documenti corti (strategia Stuffing) sectionSummaries è una lista vuota.
 */
public record DocumentSummary(
        String docId,
        String fileName,
        String fullSummary,
        List<SectionSummary> sectionSummaries,
        /** ISO-8601 timestamp di creazione del summary. */
        String createdAt
) {}
