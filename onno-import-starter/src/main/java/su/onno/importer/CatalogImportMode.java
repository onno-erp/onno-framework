package su.onno.importer;

/**
 * How catalog rows should be applied.
 */
public enum CatalogImportMode {
    /** Every CSV row creates a new catalog record. */
    CREATE_ONLY,

    /** Rows with an existing code update that record; missing codes create new records. */
    UPSERT_BY_CODE
}
