package su.onno.schema;

import org.jdbi.v3.core.Handle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads the live database structure (tables, their columns, and their {@code UNIQUE} constraints)
 * from the connection's current schema in {@code INFORMATION_SCHEMA}, which both H2 and PostgreSQL
 * expose. Names are normalized to upper case so lookups are case-insensitive regardless of how the
 * engine stores unquoted identifiers (H2 upper-cases, PostgreSQL lower-cases) — except a
 * constraint's own name, which is kept verbatim because it is fed back into {@code DROP CONSTRAINT}.
 */
public final class DatabaseIntrospector {

    private DatabaseIntrospector() {
    }

    /**
     * One {@code UNIQUE} constraint as the database holds it.
     *
     * @param name    the constraint's name exactly as stored, for {@code DROP CONSTRAINT}
     * @param columns its columns, upper-cased. A set because column order carries no meaning for
     *                uniqueness, so comparing tuples as ordered lists would report phantom changes.
     */
    public record UniqueConstraint(String name, Set<String> columns) {
    }

    /** Live tables, columns and unique constraints, keyed by upper-cased table name. */
    public record DbState(Map<String, Set<String>> tables,
                          Map<String, List<UniqueConstraint>> uniqueConstraints) {

        public DbState(Map<String, Set<String>> tables) {
            this(tables, Map.of());
        }

        public boolean hasTable(String tableName) {
            return tables.containsKey(upper(tableName));
        }

        public Set<String> columns(String tableName) {
            return tables.getOrDefault(upper(tableName), Set.of());
        }

        public boolean hasColumn(String tableName, String columnName) {
            return columns(tableName).contains(upper(columnName));
        }

        /** The table's {@code UNIQUE} constraints, primary keys excluded. */
        public List<UniqueConstraint> uniqueConstraints(String tableName) {
            return uniqueConstraints.getOrDefault(upper(tableName), List.of());
        }
    }

    public static DbState read(Handle handle) {
        Map<String, Set<String>> tables = new HashMap<>();
        String currentSchema = handle.createQuery("SELECT CURRENT_SCHEMA")
                .mapTo(String.class)
                .one();
        handle.createQuery(
                        "SELECT TABLE_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
                                "WHERE UPPER(TABLE_SCHEMA) = UPPER(:currentSchema)")
                .bind("currentSchema", currentSchema)
                .mapToMap()
                .forEach(row -> {
                    String table = upper(String.valueOf(row.get("table_name")));
                    String column = upper(String.valueOf(row.get("column_name")));
                    tables.computeIfAbsent(table, k -> new HashSet<>()).add(column);
                });
        return new DbState(tables, readUniqueConstraints(handle, currentSchema));
    }

    /**
     * Unique constraints per table. Joined on the table name as well as the constraint name because
     * PostgreSQL only requires constraint names to be unique per table, not per schema.
     */
    private static Map<String, List<UniqueConstraint>> readUniqueConstraints(Handle handle, String currentSchema) {
        record Key(String table, String name) {
        }
        Map<Key, Set<String>> byConstraint = new LinkedHashMap<>();
        handle.createQuery(
                        "SELECT tc.TABLE_NAME, tc.CONSTRAINT_NAME, kcu.COLUMN_NAME " +
                                "FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc " +
                                "JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu " +
                                "  ON kcu.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA " +
                                " AND kcu.CONSTRAINT_NAME = tc.CONSTRAINT_NAME " +
                                " AND kcu.TABLE_NAME = tc.TABLE_NAME " +
                                "WHERE tc.CONSTRAINT_TYPE = 'UNIQUE' " +
                                "  AND UPPER(tc.TABLE_SCHEMA) = UPPER(:currentSchema)")
                .bind("currentSchema", currentSchema)
                .mapToMap()
                .forEach(row -> {
                    Key key = new Key(upper(String.valueOf(row.get("table_name"))),
                            String.valueOf(row.get("constraint_name")));
                    byConstraint.computeIfAbsent(key, k -> new HashSet<>())
                            .add(upper(String.valueOf(row.get("column_name"))));
                });

        Map<String, List<UniqueConstraint>> byTable = new HashMap<>();
        byConstraint.forEach((key, columns) -> byTable
                .computeIfAbsent(key.table(), k -> new ArrayList<>())
                .add(new UniqueConstraint(key.name(), Set.copyOf(columns))));
        return byTable;
    }

    static String upper(String name) {
        return name.toUpperCase(Locale.ROOT);
    }
}
