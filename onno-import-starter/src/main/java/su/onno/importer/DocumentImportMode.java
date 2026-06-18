package su.onno.importer;

/**
 * How document rows should be applied.
 */
public enum DocumentImportMode {
    /** Every CSV row creates a new document. */
    CREATE_ONLY,

    /** Rows with an existing number update that document; missing numbers create new documents. */
    UPSERT_BY_NUMBER
}
