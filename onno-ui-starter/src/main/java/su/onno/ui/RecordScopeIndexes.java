package su.onno.ui;

import su.onno.access.RecordPolicies;
import su.onno.access.ScopedEntity;
import su.onno.schema.SchemaMode;
import su.onno.schema.SearchIndexReconciler;
import su.onno.schema.SqlDialect;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ensures every column a record policy scopes by is indexed, so a scoped list, count or
 * {@code via} subquery never degrades to a full table scan. Reference columns and register
 * dimensions on the movement table are already indexed by the schema; this adds the rest
 * (scalar/enum fields, dimensions on a balance totals table) with the same
 * {@code idx_<table>_<column>} naming, after the schema is in place.
 *
 * <p>Only runs when {@code onno.schema.mode} resolves to {@link SchemaMode#APPLY} (the default, blank
 * included, exactly as the schema initializer reads it); otherwise the statements it would run are
 * logged as a warning for the operator to apply.
 *
 * <p>On PostgreSQL the indexes are reconciled on a background thread, off the startup path, as
 * {@link SearchIndexReconciler} does for search indexes: a missing index is built with
 * {@code CREATE INDEX CONCURRENTLY}, which does not block writes to the table; an invalid index
 * left behind by an interrupted build is dropped and rebuilt; a valid one issues no statement. It
 * runs under a {@code pg_try_advisory_lock}, so one node of a scaled-out deployment builds at a
 * time. Two concurrent builds (or a build and a concurrent drop) on one table deadlock, so it also
 * waits for and holds {@link SearchIndexReconciler#BUILD_LOCK_KEY}, which the search-index
 * reconciliation waits for too, so the two take turns (neither holds a connection while it waits for
 * the other); and before it builds or repairs an index it waits while another session (autovacuum
 * aside) holds that table's {@code SHARE UPDATE EXCLUSIVE} lock, then reads the index again. On other
 * databases the plain statements run synchronously at startup.
 */
public class RecordScopeIndexes implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(RecordScopeIndexes.class);

    static final long LOCK_KEY =
            ((long) "onno".hashCode() << 32) | ("record-scope-indexes".hashCode() & 0xFFFFFFFFL);

    /** How long to wait for another session to finish with a table before leaving it for the next start. */
    static final Duration BUSY_TABLE_WAIT = Duration.ofMinutes(30);

    private static final long BUSY_TABLE_POLL_MILLIS = 1_000;

    private final RecordPolicies policies;
    private final Jdbi jdbi;
    private final boolean enabled;
    private final String schemaMode;
    private final boolean applies;
    private final Duration busyTableWait;

    public RecordScopeIndexes(RecordPolicies policies, Jdbi jdbi, boolean enabled, String schemaMode) {
        this(policies, jdbi, enabled, schemaMode, BUSY_TABLE_WAIT);
    }

    RecordScopeIndexes(RecordPolicies policies, Jdbi jdbi, boolean enabled, String schemaMode,
                       Duration busyTableWait) {
        this.policies = policies;
        this.jdbi = jdbi;
        this.enabled = enabled;
        this.schemaMode = schemaMode == null ? "" : schemaMode.trim();
        this.applies = applies(schemaMode);
        this.busyTableWait = busyTableWait;
    }

    // Read the mode exactly as the schema initializer does, so a blank value means apply here too.
    // An unknown value already fails startup there; here it only means "run no DDL".
    private static boolean applies(String schemaMode) {
        try {
            return SchemaMode.fromString(schemaMode) == SchemaMode.APPLY;
        } catch (IllegalArgumentException unknown) {
            return false;
        }
    }

    /** The {@code CREATE INDEX IF NOT EXISTS} statements for every scoped column. */
    public List<String> statements() {
        List<String> out = new ArrayList<>();
        for (ScopeIndex index : indexes().values()) {
            out.add(index.ddl(false));
        }
        return out;
    }

    /** Index name to the index, for every scoped column. */
    private Map<String, ScopeIndex> indexes() {
        Map<String, ScopeIndex> out = new LinkedHashMap<>();
        for (Map.Entry<ScopedEntity, Set<ScopedEntity.Field>> e : policies.scopedFields().entrySet()) {
            ScopedEntity entity = e.getKey();
            for (ScopedEntity.Field f : e.getValue()) {
                put(out, entity.tableName(), f.column());
                if (entity.totalsTableName() != null) {
                    put(out, entity.totalsTableName(), f.column());
                }
            }
        }
        return out;
    }

    private static void put(Map<String, ScopeIndex> out, String table, String column) {
        ScopeIndex index = new ScopeIndex(indexName(table, column), table, column);
        out.putIfAbsent(index.name(), index);
    }

    @Override
    public void afterSingletonsInstantiated() {
        Collection<ScopeIndex> wanted = wanted();
        if (wanted.isEmpty()) return;
        if (dialect() != SqlDialect.POSTGRESQL) {
            createInPlace(wanted);
            return;
        }
        // An index build on a large table can take minutes; keep it off the startup path.
        Thread thread = new Thread(() -> {
            try {
                reconcile(wanted);
            } catch (RuntimeException e) {
                log.warn("Record-scope index reconciliation failed: {}", e.getMessage());
            }
        }, "onno-record-scope-indexes");
        thread.setDaemon(true);
        thread.start();
    }

    /** Ensure the indexes synchronously on the calling thread, on any database. */
    void build() {
        Collection<ScopeIndex> wanted = wanted();
        if (wanted.isEmpty()) return;
        if (dialect() == SqlDialect.POSTGRESQL) {
            reconcile(wanted);
        } else {
            createInPlace(wanted);
        }
    }

    /** The indexes to ensure; empty when there are none or this mode runs no DDL (then logged). */
    private Collection<ScopeIndex> wanted() {
        if (policies.isEmpty()) return List.of();
        Map<String, ScopeIndex> indexes = indexes();
        if (indexes.isEmpty()) return List.of();
        if (!enabled || !applies) {
            String reason = enabled ? "onno.schema.mode=" + schemaMode : "onno.access.records.create-indexes=false";
            List<String> ddl = indexes.values().stream().map(index -> index.ddl(false)).toList();
            log.warn("Record access policies scope by columns that may be unindexed; {} so they are not "
                    + "created automatically. Ensure:\n  {}", reason, String.join(";\n  ", ddl));
            return List.of();
        }
        return indexes.values();
    }

    private SqlDialect dialect() {
        try {
            return jdbi.withHandle(h -> SqlDialect.detect(h.getConnection()));
        } catch (RuntimeException e) {
            log.warn("Could not detect the database; running record-scope index statements in place: {}",
                    e.getMessage());
            return SqlDialect.H2;
        }
    }

    /** Plain statements on the calling thread, for databases other than PostgreSQL. */
    private void createInPlace(Collection<ScopeIndex> indexes) {
        for (ScopeIndex index : indexes) {
            String sql = index.ddl(false);
            try {
                jdbi.useHandle(h -> h.execute(sql));
            } catch (RuntimeException e) {
                log.warn("Could not create record-scope index ({}): {}", sql, e.getMessage());
            }
        }
    }

    /**
     * PostgreSQL: one node at a time builds what is missing or invalid, without blocking writes.
     * Each attempt runs on its own short-lived connection, so waiting for the other reconciliation
     * holds no pooled connection.
     */
    private void reconcile(Collection<ScopeIndex> indexes) {
        long deadline = System.nanoTime() + busyTableWait.toNanos();
        boolean announced = false;
        while (true) {
            Attempt attempt = jdbi.withHandle(handle -> attempt(handle, indexes));
            if (attempt == Attempt.SKIPPED) {
                log.debug("Record-scope indexes are being built, or waited for, on another node; skipping.");
                return;
            }
            if (attempt == Attempt.DONE) return;
            if (System.nanoTime() >= deadline) {
                log.warn("Another index reconciliation kept running for {}; record-scope indexes are left "
                        + "for the next start", busyTableWait);
                return;
            }
            if (!announced) {
                log.info("Waiting for another index reconciliation to finish before record-scope indexes");
                announced = true;
            }
            if (!pause()) return;
        }
    }

    /** One try: this reconciliation's lock, then the shared build lock, then the work. */
    private Attempt attempt(Handle handle, Collection<ScopeIndex> indexes) {
        if (!tryLock(handle, LOCK_KEY)) return Attempt.SKIPPED;
        try {
            // The search-index reconciliation builds concurrently too, possibly on the same table:
            // take turns with it (on any node) under the shared build lock.
            if (!tryLock(handle, SearchIndexReconciler.BUILD_LOCK_KEY)) return Attempt.BUSY;
            try {
                reconcile(handle, indexes);
                return Attempt.DONE;
            } finally {
                unlock(handle, SearchIndexReconciler.BUILD_LOCK_KEY);
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

    /** Sleeps one poll interval; {@code false} when interrupted. */
    private static boolean pause() {
        try {
            Thread.sleep(BUSY_TABLE_POLL_MILLIS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void reconcile(Handle handle, Collection<ScopeIndex> indexes) {
        for (ScopeIndex index : indexes) {
            // A valid index needs no statement, so there is nothing to collide with: no wait.
            if (isValid(existingIndex(handle, index.name()))) continue;
            // Two concurrent builds, or a build and a concurrent drop, on one table deadlock and
            // PostgreSQL aborts one of them: wait until no other session is at work on the table.
            if (!awaitQuiet(handle, index.table())) {
                if (Thread.currentThread().isInterrupted()) return;
                log.warn("Table {} stayed busy with another index build or maintenance for {}; "
                        + "record-scope index {} is left for the next start", index.table(), busyTableWait,
                        index.name());
                continue;
            }
            // Read it again: the build we waited for may have created this very index.
            ExistingIndex current = existingIndex(handle, index.name());
            if (isValid(current)) continue;
            if (current != null) {
                // Only drop what this class would have built; never an unrelated index that shares the name.
                if (!current.isPlainIndexOn(index)) {
                    log.warn("Index {} is invalid but is not a plain index on {}.{}; leaving it in place, "
                            + "so that column stays unindexed", index.name(), index.table(), index.column());
                    continue;
                }
                if (!execute(handle, "DROP INDEX CONCURRENTLY IF EXISTS " + index.name())) continue;
                log.info("Dropped record-scope index {}: invalid (interrupted build)", index.name());
            }
            long started = System.nanoTime();
            if (!execute(handle, index.ddl(true))) continue;
            // IF NOT EXISTS succeeds without building when another relation already has the name.
            if (isValid(existingIndex(handle, index.name()))) {
                log.info("Built record-scope index {} on {}.{} in {} ms", index.name(), index.table(),
                        index.column(), (System.nanoTime() - started) / 1_000_000);
            } else {
                log.warn("Record-scope index {} on {}.{} was not built: the name is taken by another "
                        + "relation, or the build did not finish", index.name(), index.table(), index.column());
            }
        }
    }

    /**
     * Waits while another session holds or awaits {@code table}'s {@code SHARE UPDATE EXCLUSIVE}
     * lock: for example a concurrent index build or drop, or a manual {@code VACUUM}. {@code pg_locks}
     * shows every role's locks, unlike the progress views, and its relation OIDs are narrowed to this
     * database.
     *
     * <p>Autovacuum is not waited for: it can run for hours on a large table, while the build cancels
     * it, as a build run by hand would (and waits for an anti-wraparound one without a deadlock). Its
     * workers are background processes, which {@code pg_stat_activity} shows to every role with no
     * user; client sessions, of any role, show theirs.
     */
    private boolean awaitQuiet(Handle handle, String table) {
        long deadline = System.nanoTime() + busyTableWait.toNanos();
        boolean announced = false;
        while (tableBusy(handle, table)) {
            if (System.nanoTime() >= deadline) return false;
            if (!announced) {
                log.info("Waiting for another index build or maintenance on {} before its record-scope index",
                        table);
                announced = true;
            }
            if (!pause()) return false;
        }
        return true;
    }

    private static boolean tableBusy(Handle handle, String table) {
        try {
            return handle.createQuery("""
                            SELECT EXISTS (
                                SELECT 1 FROM pg_locks l
                                WHERE l.locktype = 'relation'
                                  AND l.database = (SELECT oid FROM pg_database WHERE datname = current_database())
                                  AND l.relation = to_regclass(:table)
                                  AND l.mode = 'ShareUpdateExclusiveLock'
                                  AND l.pid <> pg_backend_pid()
                                  AND NOT EXISTS (
                                      SELECT 1 FROM pg_stat_activity a
                                      WHERE a.pid = l.pid AND a.usesysid IS NULL))
                            """)
                    .bind("table", table).mapTo(Boolean.class).one();
        } catch (RuntimeException e) {
            log.debug("Could not check locks on {}; proceeding: {}", table, e.getMessage());
            return false;
        }
    }

    private static boolean execute(Handle handle, String sql) {
        try {
            handle.execute(sql);
            return true;
        } catch (RuntimeException e) {
            log.warn("Record-scope index statement failed ({}): {}", sql, e.getMessage());
            return false;
        }
    }

    private static boolean isValid(ExistingIndex index) {
        return index != null && index.valid();
    }

    /** The current-schema index named {@code name}, with validity and definition, or {@code null}. */
    private static ExistingIndex existingIndex(Handle handle, String name) {
        return handle.createQuery("""
                        SELECT c.relname AS name, t.relname AS tbl, i.indisvalid AS valid,
                               pg_get_indexdef(c.oid) AS def
                        FROM pg_index i
                        JOIN pg_class c ON c.oid = i.indexrelid
                        JOIN pg_class t ON t.oid = i.indrelid
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = current_schema() AND lower(c.relname) = :name
                        """)
                .bind("name", name.toLowerCase(Locale.ROOT))
                .map((rs, ctx) -> new ExistingIndex(
                        rs.getString("name"), rs.getString("tbl"),
                        rs.getBoolean("valid"), rs.getString("def")))
                .findOne().orElse(null);
    }

    static String indexName(String table, String column) {
        String name = "idx_" + table + "_" + column;
        if (name.length() > 63) {
            name = name.substring(0, 54) + "_" + Integer.toHexString(name.hashCode());
        }
        return name;
    }

    /** One index this class ensures. */
    record ScopeIndex(String name, String table, String column) {

        String ddl(boolean concurrently) {
            return "CREATE INDEX " + (concurrently ? "CONCURRENTLY " : "") + "IF NOT EXISTS " + name
                    + " ON " + table + " (" + column + ")";
        }
    }

    /** An index found in the database. */
    record ExistingIndex(String name, String table, boolean valid, String definition) {

        /** Whether this is the plain, non-unique btree index {@code index} describes. */
        boolean isPlainIndexOn(ScopeIndex index) {
            String def = definition == null ? "" : definition.toLowerCase(Locale.ROOT);
            return table.equalsIgnoreCase(index.table())
                    && def.startsWith("create index ")
                    && def.endsWith(" using btree (" + index.column().toLowerCase(Locale.ROOT) + ")");
        }
    }
}
