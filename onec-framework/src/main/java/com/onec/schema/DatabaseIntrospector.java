package com.onec.schema;

import org.jdbi.v3.core.Handle;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads the live database structure (tables and their columns) from
 * {@code INFORMATION_SCHEMA}, which both H2 and PostgreSQL expose. Names are normalized
 * to upper case so lookups are case-insensitive regardless of how the engine stores
 * unquoted identifiers (H2 upper-cases, PostgreSQL lower-cases).
 */
public final class DatabaseIntrospector {

    private DatabaseIntrospector() {
    }

    /** Live tables and columns, keyed by upper-cased table name. */
    public record DbState(Map<String, Set<String>> tables) {

        public boolean hasTable(String tableName) {
            return tables.containsKey(upper(tableName));
        }

        public Set<String> columns(String tableName) {
            return tables.getOrDefault(upper(tableName), Set.of());
        }

        public boolean hasColumn(String tableName, String columnName) {
            return columns(tableName).contains(upper(columnName));
        }
    }

    public static DbState read(Handle handle) {
        Map<String, Set<String>> tables = new HashMap<>();
        handle.createQuery(
                        "SELECT TABLE_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
                                "WHERE UPPER(TABLE_SCHEMA) NOT IN ('INFORMATION_SCHEMA', 'PG_CATALOG')")
                .mapToMap()
                .forEach(row -> {
                    String table = upper(String.valueOf(row.get("table_name")));
                    String column = upper(String.valueOf(row.get("column_name")));
                    tables.computeIfAbsent(table, k -> new HashSet<>()).add(column);
                });
        return new DbState(tables);
    }

    static String upper(String name) {
        return name.toUpperCase(Locale.ROOT);
    }
}
