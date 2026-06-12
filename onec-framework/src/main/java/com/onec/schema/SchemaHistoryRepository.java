package com.onec.schema;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persistence for {@code onec_schema_history}: one row per applied schema change-set
 * (kind {@code SCHEMA}, carrying the metadata snapshot and the DDL that was executed)
 * and one row per executed {@link com.onec.migration.AppMigration} (kind {@code MIGRATION},
 * claimed via a unique constraint so concurrent instances run each migration exactly once).
 */
public class SchemaHistoryRepository {

    public static final String TABLE = "onec_schema_history";

    static final String KIND_SCHEMA = "SCHEMA";
    static final String KIND_MIGRATION = "MIGRATION";

    public void ensure(Jdbi jdbi) {
        jdbi.useHandle(handle -> handle.execute(
                "CREATE TABLE IF NOT EXISTS " + TABLE + " (\n" +
                        "    _id UUID PRIMARY KEY,\n" +
                        "    _ordinal BIGINT NOT NULL,\n" +
                        "    _kind VARCHAR(16) NOT NULL,\n" +
                        "    _version VARCHAR(64),\n" +
                        "    _description VARCHAR(512),\n" +
                        "    _applied_at TIMESTAMP NOT NULL,\n" +
                        "    _snapshot TEXT,\n" +
                        "    _ddl TEXT,\n" +
                        "    UNIQUE (_kind, _version)\n" +
                        ")"));
    }

    public Optional<String> latestSnapshotJson(Jdbi jdbi) {
        return jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT _snapshot FROM " + TABLE +
                                " WHERE _kind = :kind AND _snapshot IS NOT NULL" +
                                " ORDER BY _ordinal DESC LIMIT 1")
                .bind("kind", KIND_SCHEMA)
                .mapTo(String.class)
                .findOne());
    }

    public void recordSchema(Jdbi jdbi, String snapshotJson, List<String> appliedDdl, String description) {
        jdbi.useHandle(handle -> insert(handle, KIND_SCHEMA, null, description,
                snapshotJson, appliedDdl.isEmpty() ? null : String.join(";\n", appliedDdl)));
    }

    public Set<String> appliedMigrationVersions(Jdbi jdbi) {
        return jdbi.withHandle(handle -> Set.copyOf(handle.createQuery(
                        "SELECT _version FROM " + TABLE + " WHERE _kind = :kind")
                .bind("kind", KIND_MIGRATION)
                .mapTo(String.class)
                .list()));
    }

    /**
     * Claims a migration version inside the caller's transaction. Returns false when the
     * version is already recorded (applied earlier, or claimed concurrently by another
     * instance — the unique constraint on {@code (_kind, _version)} arbitrates).
     */
    public boolean tryClaimMigration(Handle handle, String version, String description) {
        try {
            insert(handle, KIND_MIGRATION, version, description, null, null);
            return true;
        } catch (Exception e) {
            if (isUniqueViolation(e)) {
                return false;
            }
            throw e;
        }
    }

    private void insert(Handle handle, String kind, String version, String description,
                        String snapshot, String ddl) {
        long ordinal = handle.createQuery("SELECT COALESCE(MAX(_ordinal), 0) + 1 FROM " + TABLE)
                .mapTo(Long.class)
                .one();
        handle.createUpdate(
                        "INSERT INTO " + TABLE +
                                " (_id, _ordinal, _kind, _version, _description, _applied_at, _snapshot, _ddl)" +
                                " VALUES (:id, :ordinal, :kind, :version, :description, :appliedAt, :snapshot, :ddl)")
                .bind("id", UUID.randomUUID())
                .bind("ordinal", ordinal)
                .bind("kind", kind)
                .bind("version", version)
                .bind("description", description)
                .bind("appliedAt", LocalDateTime.now())
                .bind("snapshot", snapshot)
                .bind("ddl", ddl)
                .execute();
    }

    private static boolean isUniqueViolation(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().startsWith("23")) {
                return true;
            }
        }
        return false;
    }
}
