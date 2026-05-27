package elastic.rag.model;

public enum SummaryStatus {
    /** Documento indicizzato, nessun summary richiesto ancora. */
    NONE,
    /** Summary richiesto, in attesa di essere elaborato. */
    PENDING,
    /** Summary in fase di generazione. */
    PROCESSING,
    /** Summary generato e disponibile. */
    COMPLETED,
    /** Generazione fallita (vedi summaryError). */
    FAILED
}
