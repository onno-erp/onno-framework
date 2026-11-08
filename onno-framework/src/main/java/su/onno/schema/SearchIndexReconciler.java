package su.onno.schema;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Converges an existing PostgreSQL database on the declared set of trigram search indexes
 * ({@link SchemaGenerator#searchIndexes(SearchIndexMode)}).
 *
 * <ul>
 *   <li>A framework-owned trigram index that is no longer declared (attribute not opted in, or
 *       {@code onno.schema.search-indexes=off}) is dropped. Ownership is decided by
 *       <em>definition</em> — a GIN index using {@code gin_trgm_ops} on a catalog/document table,
 *       named {@code idx_trgm_*} — never by name alone, so an unrelated index that happens to use
 *       the name (e.g. an app's placeholder) is left alone.</li>
 *   <li>An invalid index left behind by an interrupted concurrent build is dropped and rebuilt.</li>
 *   <li>Missing declared indexes are built with {@code CREATE INDEX CONCURRENTLY}, which does not
 *       block writes to the table.</li>
 * </ul>
 *
 * <p>Every statement is best-effort: a failure (no {@code pg_trgm}, insufficient privileges, a
 * lock timeout) is logged and the rest continues — search falls back to {@code LIKE} scans.
 * Runs under a {@code pg_try_advisory_lock} so only one node of a scaled-out deployment builds at
 * a time, and also takes {@link #BUILD_LOCK_KEY}, waiting for it, so it never builds alongside
 * another background index reconciliation. While it waits it holds no connection. A no-op on
 * non-PostgreSQL dialects.
 */
public final class SearchIndexReconciler {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexReconciler.class);

    static final long LOCK_KEY =
            ((long) "onno".hashCode() << 32) | ("search-indexes".hashCode() & 0xFFFFFFFFL);

    /**
     * The advisory lock every background index reconciliation holds while it inspects, builds and
     * drops indexes: this one, and the UI starter's record-scope indexes. Two concurrent
     * {@code CREATE INDEX CONCURRENTLY} builds on one table, or a build and a concurrent drop,
     * deadlock, so the reconciliations take turns: each waits for this lock rather than skip.
     */
    public static final long BUILD_LOCK_KEY =
            ((long) "onno".hashCode() << 32) | ("index-builds".hashCode() & 0xFFFFFFFFL);

    private static final long BUILD_LOCK_POLL_MILLIS = 1_000;

    private final SchemaGenerator generator;
    private final SearchIndexMode mode;

    public SearchIndexReconciler(SchemaGenerator generator, SearchIndexMode mode) {
        this.generator = generator;
        this.mode = mode;
    }

    /**
     * Reconcile on a background daemon thread, off the startup path: index builds on a large
     * table can take minutes and must not hold up boot.
     */
    public void reconcileAsync(Jdbi jdbi, SqlDialect dialect) {
        if (dialect != SqlDialect.POSTGRESQL) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                reconcile(jdbi, dialect);
            } catch (RuntimeException e) {
                log.warn("Search index reconciliation failed: {}", e.getMessage());
            }
        }, "onno-search-indexes");
        thread.setDaemon(true);
        thread.start();
    }

    /** Reconcile synchronously on the calling thread. */
    public void reconcile(Jdbi jdbi, SqlDialect dialect) {
        if (dialect != SqlDialect.POSTGRESQL) {
            return;
        }
        // Polls rather than block in pg_advisory_lock: a statement blocked on the lock holds a
        // snapshot, and the lock holder's concurrent build waits for every older snapshot. Each
        // attempt runs on its own short-lived connection, so waiting holds no pooled connection.
        boolean announced = false;
        while (true) {
            Attempt attempt = jdbi.withHandle(this::attempt);
            if (attempt == Attempt.SKIPPED) {
                log.debug("Search index reconciliation is running or waiting on another node; skipping.");
                return;
            }
            if (attempt == Attempt.DONE) {
                return;
            }
            if (!announced) {
                log.info("Waiting for another index reconciliation to finish before search indexes");
                announced = true;
            }
            try {
                Thread.sleep(BUILD_LOCK_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** One try: this reconciliation's lock, then the shared build lock, then the work. */
    private Attempt attempt(Handle handle) {
        if (!tryLock(handle, LOCK_KEY)) {
            return Attempt.SKIPPED;
        }
        try {
            if (!tryLock(handle, BUILD_LOCK_KEY)) {
                return Attempt.BUSY;
            }
            try {
                reconcile(handle);
                return Attempt.DONE;
            } finally {
                unlock(handle, BUILD_LOCK_KEY);
            }
        } finally {
            unlock(handle, LOCK_KEY);
        }
    }

    private enum Attempt { DONE, SKIPPED, BUSY }

    private static boolean tryLock(Handle handle, long key) {
        return Boolean.TRUE.equals(handle.createQuery("SELECT pg_try_advisory_lock(:key)")
                .bind("key", key).mapTo(Boolean.class).one());
    }

    private static void unlock(Handle handle, long key) {
        handle.createQuery("SELECT pg_advisory_unlock(:key)").bind("key", key).mapTo(Boolean.class).one();
    }

    private void reconcile(Handle handle) {
        List<SchemaGenerator.SearchIndex> declared = generator.searchIndexes(mode);
        Set<String> declaredNames = declared.stream()
                .map(SchemaGenerator.SearchIndex::name).collect(Collectors.toSet());
        Set<String> managedTables = generator.searchableTables();

        Map<String, ExistingIndex> existing = new HashMap<>();
        for (ExistingIndex index : existingIndexes(handle)) {
            existing.put(index.name(), index);
        }

        for (ExistingIndex index : existing.values()) {
            if (!index.ownedTrigram(managedTables)) {
                continue;
            }
            if (!declaredNames.contains(index.name()) || !index.valid()) {
                String why = index.valid() ? "no longer declared" : "invalid (interrupted build)";
                if (execute(handle, "DROP INDEX CONCURRENTLY IF EXISTS " + index.name())) {
                    log.info("Dropped trigram search index {} on {}: {}", index.name(), index.table(), why);
                }
            }
        }

        List<SchemaGenerator.SearchIndex> missing = declared.stream()
                .filter(index -> {
                    ExistingIndex current = existing.get(index.name());
                    if (current == null || (current.ownedTrigram(managedTables) && !current.valid())) {
                        return true;
                    }
                    if (!current.ownedTrigram(managedTables)) {
                        log.info("Index {} exists but is not a trigram index; leaving it in place "
                                + "and skipping the search index on {}.{}",
                                index.name(), index.table(), index.column());
                    }
                    return false;
                })
                .toList();
        if (missing.isEmpty()) {
            return;
        }
        if (!execute(handle, "CREATE EXTENSION IF NOT EXISTS pg_trgm")) {
            log.warn("Trigram search indexes unavailable — continuing with LIKE scans. Install the "
                    + "pg_trgm extension (or grant the app role rights to) for indexed substring search.");
            return;
        }
        for (SchemaGenerator.SearchIndex index : missing) {
            long started = System.nanoTime();
            if (execute(handle, index.ddl(true))) {
                log.info("Built trigram search index {} on {}.{} in {} ms", index.name(), index.table(),
                        index.column(), (System.nanoTime() - started) / 1_000_000);
            }
        }
    }

    private static boolean execute(Handle handle, String sql) {
        try {
            handle.execute(sql);
            return true;
        } catch (RuntimeException e) {
            log.warn("Search index statement failed ({}): {}", sql, e.getMessage());
            return false;
        }
    }

    private static List<ExistingIndex> existingIndexes(Handle handle) {
        return handle.createQuery("""
                        SELECT c.relname AS name, t.relname AS tbl, i.indisvalid AS valid,
                               pg_get_indexdef(c.oid) AS def
                        FROM pg_index i
                        JOIN pg_class c ON c.oid = i.indexrelid
                        JOIN pg_class t ON t.oid = i.indrelid
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = current_schema()
                          AND c.relname LIKE 'idx\\_trgm\\_%'
                        """)
                .map((rs, ctx) -> new ExistingIndex(
                        rs.getString("name"), rs.getString("tbl"),
                        rs.getBoolean("valid"), rs.getString("def")))
                .list();
    }

    record ExistingIndex(String name, String table, boolean valid, String definition) {

        boolean ownedTrigram(Set<String> managedTables) {
            String def = definition == null ? "" : definition.toLowerCase(java.util.Locale.ROOT);
            return managedTables.contains(table)
                    && def.contains(" using gin ")
                    && def.contains("gin_trgm_ops");
        }
    }
}
