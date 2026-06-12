package com.onec.migration;

/**
 * A one-time, versioned data migration — the home for backfills, data reshaping and seed
 * data that the automatic schema upgrader cannot derive from metadata.
 *
 * <p>Declare implementations as Spring beans; the framework runs unapplied migrations in
 * {@link #version()} order at startup (after the schema upgrade) and records each in
 * {@code onec_schema_history} so it executes exactly once per database, even with several
 * application instances starting concurrently.
 *
 * <pre>{@code
 * @Component
 * public class BackfillWarehouseCodes implements AppMigration {
 *     public String version() { return "2026.06.001"; }
 *     public void migrate(MigrationContext context) {
 *         context.handle().execute("UPDATE catalog_warehouses SET code_prefix = 'WH' WHERE code_prefix IS NULL");
 *     }
 * }
 * }</pre>
 *
 * <p>Versions compare segment-wise ({@code 2 < 10}); any dot-separated scheme works as long
 * as it sorts the way you intend. Each migration runs inside a transaction together with its
 * history record, so a failure rolls both back — but note that engines without transactional
 * DDL (H2, MySQL) auto-commit DDL statements mid-transaction.
 */
public interface AppMigration {

    /** Unique, stable version this migration is recorded under (e.g. {@code "2026.06.001"}). */
    String version();

    /** Short human-readable label stored in the history table. */
    default String description() {
        return getClass().getSimpleName();
    }

    void migrate(MigrationContext context) throws Exception;
}
