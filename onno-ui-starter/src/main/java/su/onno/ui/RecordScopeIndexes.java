package su.onno.ui;

import su.onno.access.RecordPolicies;
import su.onno.access.ScopedEntity;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.ArrayList;
import java.util.LinkedHashSet;
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
 * <p>Only runs when {@code onno.schema.mode=apply}; otherwise the statements it would run are
 * logged as a warning for the operator to apply.
 */
public class RecordScopeIndexes implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(RecordScopeIndexes.class);

    private final RecordPolicies policies;
    private final Jdbi jdbi;
    private final boolean enabled;
    private final String schemaMode;

    public RecordScopeIndexes(RecordPolicies policies, Jdbi jdbi, boolean enabled, String schemaMode) {
        this.policies = policies;
        this.jdbi = jdbi;
        this.enabled = enabled;
        this.schemaMode = schemaMode == null ? "apply" : schemaMode.trim().toLowerCase(Locale.ROOT);
    }

    /** The {@code CREATE INDEX IF NOT EXISTS} statements for every scoped column. */
    public List<String> statements() {
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<ScopedEntity, Set<ScopedEntity.Field>> e : policies.scopedFields().entrySet()) {
            ScopedEntity entity = e.getKey();
            for (ScopedEntity.Field f : e.getValue()) {
                out.add(index(entity.tableName(), f.column()));
                if (entity.totalsTableName() != null) {
                    out.add(index(entity.totalsTableName(), f.column()));
                }
            }
        }
        return new ArrayList<>(out);
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (policies.isEmpty()) return;
        List<String> ddl = statements();
        if (ddl.isEmpty()) return;
        if (!enabled || !"apply".equals(schemaMode)) {
            log.warn("Record access policies scope by columns that may be unindexed; onno.schema.mode={} "
                    + "so they are not created automatically. Ensure:\n  {}", schemaMode, String.join(";\n  ", ddl));
            return;
        }
        for (String sql : ddl) {
            try {
                jdbi.useHandle(h -> h.execute(sql));
            } catch (RuntimeException e) {
                log.warn("Could not create record-scope index ({}): {}", sql, e.getMessage());
            }
        }
    }

    static String index(String table, String column) {
        String name = "idx_" + table + "_" + column;
        if (name.length() > 63) {
            name = name.substring(0, 54) + "_" + Integer.toHexString(name.hashCode());
        }
        return "CREATE INDEX IF NOT EXISTS " + name + " ON " + table + " (" + column + ")";
    }
}
