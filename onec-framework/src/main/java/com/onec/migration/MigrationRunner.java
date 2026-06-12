package com.onec.migration;

import com.onec.metadata.MetadataRegistry;
import com.onec.schema.SchemaHistoryRepository;
import com.onec.schema.SqlDialect;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Executes {@link AppMigration}s exactly once per database, in version order. Each
 * migration is claimed by inserting its version into {@code onec_schema_history} inside
 * the same transaction that runs the migration: a unique constraint arbitrates between
 * concurrently starting instances, and a failure rolls the claim back with the work.
 */
public class MigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrationRunner.class);

    private final MetadataRegistry registry;
    private final SchemaHistoryRepository history = new SchemaHistoryRepository();

    public MigrationRunner(MetadataRegistry registry) {
        this.registry = registry;
    }

    /** Runs all unapplied migrations; returns the versions executed by this call. */
    public List<String> run(Jdbi jdbi, List<AppMigration> migrations) {
        if (migrations.isEmpty()) {
            return List.of();
        }
        List<AppMigration> ordered = validateAndSort(migrations);
        SqlDialect dialect = jdbi.withHandle(handle -> SqlDialect.detect(handle.getConnection()));
        history.ensure(jdbi);
        Set<String> applied = history.appliedMigrationVersions(jdbi);

        List<String> executed = new ArrayList<>();
        for (AppMigration migration : ordered) {
            if (applied.contains(migration.version())) {
                continue;
            }
            boolean ran = jdbi.inTransaction(handle -> {
                if (!history.tryClaimMigration(handle, migration.version(), migration.description())) {
                    return false;
                }
                try {
                    migration.migrate(new MigrationContext(handle, registry, dialect));
                } catch (Exception e) {
                    throw new IllegalStateException(
                            "Migration " + migration.version() + " (" + migration.description() + ") failed", e);
                }
                return true;
            });
            if (ran) {
                log.info("Applied migration {} ({})", migration.version(), migration.description());
                executed.add(migration.version());
            }
        }
        return executed;
    }

    /** Migrations not yet recorded in the history table, in execution order. */
    public List<AppMigration> pending(Jdbi jdbi, List<AppMigration> migrations) {
        if (migrations.isEmpty()) {
            return List.of();
        }
        List<AppMigration> ordered = validateAndSort(migrations);
        history.ensure(jdbi);
        Set<String> applied = history.appliedMigrationVersions(jdbi);
        return ordered.stream()
                .filter(migration -> !applied.contains(migration.version()))
                .toList();
    }

    private static List<AppMigration> validateAndSort(List<AppMigration> migrations) {
        Set<String> seen = new HashSet<>();
        for (AppMigration migration : migrations) {
            String version = migration.version();
            if (version == null || version.isBlank()) {
                throw new IllegalStateException(
                        migration.getClass().getName() + " declares a blank migration version");
            }
            if (!seen.add(version)) {
                throw new IllegalStateException("Duplicate migration version '" + version + "'");
            }
        }
        return migrations.stream()
                .sorted(Comparator.comparing(AppMigration::version, MigrationRunner::compareVersions))
                .toList();
    }

    /**
     * Segment-wise version comparison: split on dots, numeric segments compare numerically
     * ({@code 2 < 10}), non-numeric segments lexicographically.
     */
    static int compareVersions(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        int n = Math.max(left.length, right.length);
        for (int i = 0; i < n; i++) {
            String l = i < left.length ? left[i] : "";
            String r = i < right.length ? right[i] : "";
            int cmp;
            if (isNumeric(l) && isNumeric(r)) {
                cmp = Long.compare(Long.parseLong(l), Long.parseLong(r));
            } else {
                cmp = l.compareTo(r);
            }
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private static boolean isNumeric(String s) {
        if (s.isEmpty() || s.length() > 18) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
