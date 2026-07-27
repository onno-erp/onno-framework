package su.onno.model;

/**
 * How movements in an accumulation register react to backdated document changes.
 */
public enum PostingOrder {
    /**
     * Documents affect the register independently; posting or unposting one document does not
     * restore later documents.
     */
    INDEPENDENT,

    /**
     * A backdated post or unpost restores later documents that wrote to the same chronological
     * register, then reposts them oldest-first.
     */
    CHRONOLOGICAL
}
