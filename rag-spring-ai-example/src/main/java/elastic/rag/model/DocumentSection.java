package elastic.rag.model;

import java.util.Map;

/**
 * Rappresenta una sezione/capitolo estratta da un documento.
 * È il formato comune per PDF/DOC (via Docling) e video (via trascrizione).
 * I campi startTimeMs/endTimeMs sono valorizzati solo per i video.
 * Il campo pageNumber è valorizzato solo per documenti paginati.
 */
public record DocumentSection(
        String sectionId,
        String title,
        Integer pageNumber,
        Long startTimeMs,
        Long endTimeMs,
        String text,
        Map<String, Object> metadata
) {}
