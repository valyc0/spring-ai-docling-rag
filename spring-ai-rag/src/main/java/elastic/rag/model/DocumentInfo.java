package elastic.rag.model;

/**
 * Coppia docId / fileName estratta dall'indice vettoriale.
 * Usata dall'endpoint GET /docling/documents per permettere al chiamante
 * di scegliere un documento prima di invocare /docling/ask.
 */
public record DocumentInfo(String docId, String fileName) {}
