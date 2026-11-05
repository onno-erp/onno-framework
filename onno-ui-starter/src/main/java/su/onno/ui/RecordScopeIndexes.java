package su.onno.ui;

import su.onno.access.RecordPolicies;
import su.onno.access.ScopedEntity;
import su.onno.schema.SchemaMode;
import su.onno.schema.SqlDialect;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.ArrayList;
import java.util.HashSet;
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
 * {@code CREATE INDEX IF NOT EXISTS idx_<table>_<column>} naming, after the schema is in place.
 *
 * <p>Only runs when {@code onno.schema.mode} resolves to {@link SchemaMode#APPLY} (the default, blank
 * included, exactly as the schema initializer reads it); otherwise the statements it would run are
 * logged as a warning for the operator to apply. On PostgreSQL, indexes that already exist are
 * skipped without issuing a statement.
 */
public class RecordScopeIndexes implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(RecordScopeIndexes.class);

    private final RecordPolicies policies;
    private final Jdbi jdbi;
    private final boolean enabled;
    private final String schemaMode;
    private final boolean applies;

    public RecordScopeIndexes(RecordPolicies policies, Jdbi jdbi, boolean enabled, String schemaMode) {
        this.policies = policies;
        this.jdbi = jdbi;
        this.enabled = enabled;
        this.schemaMode = schemaMode == null ? "" : schemaMode.trim();
        this.applies = applies(schemaMode);
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
        return new ArrayList<>(indexes().values());
    }

    /** Index name to its statement, for every scoped column. */
    private Map<String, String> indexes() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<ScopedEntity, Set<ScopedEntity.Field>> e : policies.scopedFields().entrySet()) {
            ScopedEntity entity = e.getKey();
            for (ScopedEntity.Field f : e.getValue()) {
                out.putIfAbsent(indexName(entity.tableName(), f.column()), index(entity.tableName(), f.column()));
                if (entity.totalsTableName() != null) {
                    out.putIfAbsent(indexName(entity.totalsTableName(), f.column()),
                            index(entity.totalsTableName(), f.column()));
                }
            }
        }
        return out;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (policies.isEmpty()) return;
        Map<String, String> ddl = indexes();
        if (ddl.isEmpty()) return;
        if (!enabled || !applies) {
            String reason = enabled ? "onno.schema.mode=" + schemaMode : "onno.access.records.create-indexes=false";
            log.warn("Record access policies scope by columns that may be unindexed; {} so they are not "
                    + "created automatically. Ensure:\n  {}", reason, String.join(";\n  ", ddl.values()));
            return;
        }
        // On PostgreSQL, even CREATE INDEX IF NOT EXISTS takes a write-blocking SHARE lock on the
        // table before it checks the name, so existing indexes are skipped up front (as
        // SchemaUpgrader does) rather than stalling writes to every scoped table on each boot.
        Set<String> existing = existingIndexes();
        for (Map.Entry<String, String> index : ddl.entrySet()) {
            if (existing.contains(index.getKey().toLowerCase(Locale.ROOT))) continue;
            String sql = index.getValue();
            try {
                jdbi.useHandle(h -> h.execute(sql));
            } catch (RuntimeException e) {
                log.warn("Could not create record-scope index ({}): {}", sql, e.getMessage());
            }
        }
    }

    /** Lower-cased index names of the current PostgreSQL schema; empty on other databases. */
    private Set<String> existingIndexes() {
        try {
            return jdbi.withHandle(h -> {
                if (SqlDialect.detect(h.getConnection()) != SqlDialect.POSTGRESQL) return Set.<String>of();
                Set<String> names = new HashSet<>();
                for (String name : h.createQuery("SELECT indexname FROM pg_indexes WHERE schemaname = current_schema()")
                        .mapTo(String.class).list()) {
                    names.add(name.toLowerCase(Locale.ROOT));
                }
                return names;
            });
        } catch (RuntimeException e) {
            log.warn("Could not list existing indexes; issuing every record-scope index statement: {}", e.getMessage());
            return Set.of();
        }
    }

    static String index(String table, String column) {
        return "CREATE INDEX IF NOT EXISTS " + indexName(table, column) + " ON " + table + " (" + column + ")";
    }

    static String indexName(String table, String column) {
        String name = "idx_" + table + "_" + column;
        if (name.length() > 63) {
            name = name.substring(0, 54) + "_" + Integer.toHexString(name.hashCode());
        }
        return name;
    }
}
