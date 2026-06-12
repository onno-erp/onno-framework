package com.onec.schema;

import com.onec.metadata.EnumerationDescriptor;
import com.onec.metadata.MetadataRegistry;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Startup schema lifecycle: scans nothing itself — it takes the already-built
 * {@link MetadataRegistry}, derives the desired {@link SchemaModel}, diffs it against the
 * live database (and the previous {@link SchemaSnapshot} from {@code onec_schema_history}),
 * and then acts according to {@link SchemaMode}.
 *
 * <p>Safe changes (create table, add column, rename via {@code previousNames}, widening
 * type changes) are applied automatically. Destructive changes (drops, narrowing types)
 * are reported and skipped unless {@code allowDestructive} is set. Every applied change-set
 * is recorded in {@code onec_schema_history} together with the metadata snapshot, which is
 * what makes type-change detection and drop detection possible on the next boot.
 */
public class SchemaUpgrader {

    private static final Logger log = LoggerFactory.getLogger(SchemaUpgrader.class);

    private final MetadataRegistry registry;
    private final SchemaMode mode;
    private final boolean allowDestructive;
    private final SchemaHistoryRepository history = new SchemaHistoryRepository();

    public SchemaUpgrader(MetadataRegistry registry, SchemaMode mode, boolean allowDestructive) {
        this.registry = registry;
        this.mode = mode;
        this.allowDestructive = allowDestructive;
    }

    public MigrationPlan run(Jdbi jdbi) {
        if (mode == SchemaMode.OFF) {
            return MigrationPlan.EMPTY;
        }
        SqlDialect dialect = jdbi.withHandle(handle -> SqlDialect.detect(handle.getConnection()));
        history.ensure(jdbi);

        SchemaModel model = new SchemaModelBuilder(registry).build();
        SchemaSnapshot current = SchemaSnapshot.of(model);
        SchemaSnapshot previous = history.latestSnapshotJson(jdbi)
                .map(SchemaSnapshot::fromJson)
                .orElse(null);
        DatabaseIntrospector.DbState db = jdbi.withHandle(DatabaseIntrospector::read);

        MigrationPlan plan = new SchemaDiffEngine(dialect).diff(model, previous, db);

        switch (mode) {
            case PLAN -> log.info(plan.describe());
            case VALIDATE -> {
                if (!plan.isEmpty()) {
                    throw new IllegalStateException(
                            "Schema validation failed (onec.schema.mode=validate):\n" + plan.describe());
                }
                log.info("Schema validation passed: database matches the metadata model.");
            }
            case APPLY -> apply(jdbi, dialect, plan, current, previous);
            default -> { }
        }
        return plan;
    }

    private void apply(Jdbi jdbi, SqlDialect dialect, MigrationPlan plan,
                       SchemaSnapshot current, SchemaSnapshot previous) {
        List<String> applied = new ArrayList<>();
        List<SchemaChange> skipped = new ArrayList<>();
        for (SchemaChange change : plan.changes()) {
            if (change.destructive() && !allowDestructive) {
                skipped.add(change);
                continue;
            }
            jdbi.useHandle(handle -> {
                for (String sql : change.sql()) {
                    handle.execute(sql);
                }
            });
            applied.addAll(change.sql());
            if (change.detail() != null && change.type() == SchemaChange.Type.ADD_COLUMN) {
                log.warn("{}.{}: {}", change.table(), change.column(), change.detail());
            }
        }

        // Enum value seeds are idempotent upserts; keep them in sync on every boot.
        jdbi.useHandle(handle -> {
            for (EnumerationDescriptor enumeration : registry.allEnumerations()) {
                for (String upsert : SchemaGenerator.enumerationInserts(enumeration, dialect)) {
                    handle.execute(upsert);
                }
            }
        });

        if (!applied.isEmpty()) {
            log.info("Applied {} schema change(s):\n{}",
                    plan.changes().size() - skipped.size(),
                    String.join("\n", applied));
        }

        if (!skipped.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("Skipped ").append(skipped.size())
                    .append(" destructive schema change(s); set onec.schema.allow-destructive=true to apply:");
            for (SchemaChange change : skipped) {
                sb.append("\n  ").append(change.describe());
                for (String sql : change.sql()) {
                    sb.append("\n      ").append(sql);
                }
            }
            log.warn(sb.toString());
            // Keep the previous snapshot: recording the new one would erase the knowledge
            // (e.g. a pending type change) these skipped changes were derived from.
            return;
        }

        boolean snapshotChanged = previous == null || !current.equals(previous);
        if (snapshotChanged || !applied.isEmpty()) {
            history.recordSchema(jdbi, current.toJson(), applied,
                    previous == null ? "baseline" : applied.size() + " statement(s) applied");
        }
    }
}
