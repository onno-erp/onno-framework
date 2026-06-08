package com.onec.schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Identifies the target database so upsert helpers can emit portable SQL.
 *
 * <p>Table DDL produced by {@link SchemaGenerator} is already portable, but the
 * seed/upsert statements for {@code @Enumeration} values and constants are not:
 * H2 understands {@code MERGE INTO t (cols) KEY(k) VALUES (...)}, while
 * PostgreSQL only accepts {@code INSERT ... ON CONFLICT (k) DO UPDATE ...}
 * (PG 9.5+). H2 in turn does not support {@code ON CONFLICT ... DO UPDATE}, so a
 * single statement cannot satisfy both engines — we branch on the dialect here.
 */
public enum SqlDialect {

    H2,
    POSTGRESQL;

    /**
     * Detects the dialect from a live JDBC connection, defaulting to {@link #H2}
     * (the in-memory engine used by tests) when the product is unknown.
     */
    public static SqlDialect detect(Connection connection) {
        try {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product != null && product.toLowerCase().contains("postgresql")) {
                return POSTGRESQL;
            }
        } catch (SQLException ignored) {
            // fall through to the safe default
        }
        return H2;
    }

    /**
     * Builds a portable "insert or update" statement.
     *
     * @param table      target table name
     * @param columns    every column being written, in order
     * @param keyColumns the conflict/primary key columns
     * @param values     value tokens (literals or named binds like {@code :id}),
     *                   aligned with {@code columns}
     */
    public String upsert(String table, List<String> columns, List<String> keyColumns, List<String> values) {
        String cols = String.join(", ", columns);
        String vals = String.join(", ", values);
        if (this == POSTGRESQL) {
            String assignments = columns.stream()
                    .filter(c -> !keyColumns.contains(c))
                    .map(c -> c + " = EXCLUDED." + c)
                    .collect(Collectors.joining(", "));
            String insert = "INSERT INTO " + table + " (" + cols + ") VALUES (" + vals + ")"
                    + " ON CONFLICT (" + String.join(", ", keyColumns) + ")";
            return assignments.isEmpty()
                    ? insert + " DO NOTHING"
                    : insert + " DO UPDATE SET " + assignments;
        }
        return "MERGE INTO " + table + " (" + cols + ") KEY(" + String.join(", ", keyColumns)
                + ") VALUES (" + vals + ")";
    }
}
