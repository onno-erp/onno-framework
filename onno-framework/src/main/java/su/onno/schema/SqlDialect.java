package su.onno.schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
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

    private static final Logger log = LoggerFactory.getLogger(SqlDialect.class);

    /**
     * Detects the dialect from a live JDBC connection, defaulting to {@link #H2}
     * (the in-memory engine used by tests) when the product is unknown.
     */
    public static SqlDialect detect(Connection connection) {
        String product = null;
        try {
            product = connection.getMetaData().getDatabaseProductName();
            if (product != null && product.toLowerCase().contains("postgresql")) {
                return POSTGRESQL;
            }
        } catch (SQLException e) {
            log.warn("Could not read database product name; falling back to the H2 dialect. "
                    + "Generated upsert SQL may be invalid for your database.", e);
            return H2;
        }
        if (product == null || !product.toLowerCase().contains("h2")) {
            log.warn("Unsupported database product '{}'; falling back to the H2 dialect. "
                    + "Only H2 and PostgreSQL are supported — generated upsert SQL will likely "
                    + "fail on this database.", product);
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

    /**
     * Builds an atomic "insert or increment" statement for accumulator tables: when the
     * key row exists, every increment column is bumped by its bound value; otherwise the
     * row is inserted. A single statement, so two transactions hitting the same new key
     * cannot race the way a SELECT-then-INSERT/UPDATE pair can.
     *
     * @param table            target table name
     * @param keyColumns       the conflict/primary key columns
     * @param incrementColumns columns added to on conflict
     * @param values           value tokens (named binds like {@code :qty}), aligned with
     *                         {@code keyColumns} followed by {@code incrementColumns}
     */
    public String upsertIncrement(String table, List<String> keyColumns,
                                  List<String> incrementColumns, List<String> values) {
        List<String> columns = new ArrayList<>(keyColumns);
        columns.addAll(incrementColumns);
        String cols = String.join(", ", columns);
        String vals = String.join(", ", values);
        if (this == POSTGRESQL) {
            String assignments = incrementColumns.stream()
                    .map(c -> c + " = " + table + "." + c + " + EXCLUDED." + c)
                    .collect(Collectors.joining(", "));
            return "INSERT INTO " + table + " (" + cols + ") VALUES (" + vals + ")"
                    + " ON CONFLICT (" + String.join(", ", keyColumns) + ")"
                    + " DO UPDATE SET " + assignments;
        }
        // H2 has no ON CONFLICT ... DO UPDATE and its simple MERGE ... KEY replaces rather
        // than increments, so use the standard MERGE ... USING form (H2 2.x).
        String on = keyColumns.stream()
                .map(c -> table + "." + c + " = src." + c)
                .collect(Collectors.joining(" AND "));
        String updates = incrementColumns.stream()
                .map(c -> c + " = " + table + "." + c + " + src." + c)
                .collect(Collectors.joining(", "));
        String insertVals = columns.stream()
                .map(c -> "src." + c)
                .collect(Collectors.joining(", "));
        return "MERGE INTO " + table + " USING (VALUES (" + vals + ")) AS src(" + cols + ")"
                + " ON " + on
                + " WHEN MATCHED THEN UPDATE SET " + updates
                + " WHEN NOT MATCHED THEN INSERT (" + cols + ") VALUES (" + insertVals + ")";
    }
}
