package su.onno.schema;

import org.jdbi.v3.core.Handle;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads the live database structure (tables and their columns) from the connection's current
 * schema in {@code INFORMATION_SCHEMA}, which both H2 and PostgreSQL expose. Names are normalized
 * to upper case so lookups are case-insensitive regardless of how the engine stores unquoted
 * identifiers (H2 upper-cases, PostgreSQL lower-cases).
 */
public final class DatabaseIntrospector {

    private DatabaseIntrospector() {
    }

    /**
     * Live tables and columns, keyed by upper-cased table name.
     *
     * @param tables table columns by table name
     * @param foreignKeyParents referenced tables by referencing table name
     */
    public record DbState(Map<String, Set<String>> tables, Map<String, Set<String>> foreignKeyParents) {

        public boolean hasTable(String tableName) {
            return tables.containsKey(upper(tableName));
        }

        public Set<String> columns(String tableName) {
            return tables.getOrDefault(upper(tableName), Set.of());
        }

        public Set<String> referencedTables(String tableName) {
            return foreignKeyParents.getOrDefault(upper(tableName), Set.of());
        }
    }

    public static DbState read(Handle handle) {
        Map<String, Set<String>> tables = new HashMap<>();
        Map<String, String> actualTableNames = new HashMap<>();
        String currentSchema = handle.createQuery("SELECT CURRENT_SCHEMA")
                .mapTo(String.class)
                .one();
        handle.createQuery(
                        "SELECT TABLE_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
                                "WHERE UPPER(TABLE_SCHEMA) = UPPER(:currentSchema)")
                .bind("currentSchema", currentSchema)
                .mapToMap()
                .forEach(row -> {
                    String tableName = String.valueOf(row.get("table_name"));
                    String table = upper(tableName);
                    String column = upper(String.valueOf(row.get("column_name")));
                    actualTableNames.putIfAbsent(table, tableName);
                    tables.computeIfAbsent(table, k -> new HashSet<>()).add(column);
                });
        return new DbState(tables, foreignKeyParents(handle, currentSchema, actualTableNames, tables.keySet()));
    }

    private static Map<String, Set<String>> foreignKeyParents(
            Handle handle,
            String currentSchema,
            Map<String, String> actualTableNames,
            Set<String> knownTables) {
        Map<String, Set<String>> parents = new HashMap<>();
        try {
            DatabaseMetaData metaData = handle.getConnection().getMetaData();
            for (Map.Entry<String, String> table : actualTableNames.entrySet()) {
                try (ResultSet keys = metaData.getImportedKeys(null, currentSchema, table.getValue())) {
                    while (keys.next()) {
                        String child = upper(keys.getString("FKTABLE_NAME"));
                        String parent = upper(keys.getString("PKTABLE_NAME"));
                        String childSchema = keys.getString("FKTABLE_SCHEM");
                        String parentSchema = keys.getString("PKTABLE_SCHEM");
                        if (sameSchema(currentSchema, childSchema)
                                && sameSchema(currentSchema, parentSchema)
                                && knownTables.contains(child)
                                && knownTables.contains(parent)) {
                            parents.computeIfAbsent(child, ignored -> new HashSet<>()).add(parent);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to introspect database foreign keys", e);
        }
        return parents;
    }

    private static boolean sameSchema(String expected, String actual) {
        return actual != null && actual.equalsIgnoreCase(expected);
    }

    static String upper(String name) {
        return name.toUpperCase(Locale.ROOT);
    }
}
