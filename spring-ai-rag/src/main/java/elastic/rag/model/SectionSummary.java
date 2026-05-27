package elastic.rag.model;

/**
 * Riassunto di una singola sezione del documento, prodotto durante la fase Map.
 */
public record SectionSummary(
        String sectionId,
        String title,
        Integer pageNumber,
        String summary
) {}
