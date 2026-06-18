package su.onno.schema;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A point-in-time record of the metadata-derived schema, persisted as JSON in
 * {@code onno_schema_history}. The diff engine compares the previous snapshot's declared
 * column types against the current metadata to detect type changes (something a live-DB
 * existence check cannot see), and uses the snapshot's table list to recognize tables
 * that used to be metadata-managed and have since been removed from the model.
 */
public record SchemaSnapshot(Map<String, TableSnapshot> tables) {

    public record TableSnapshot(Map<String, ColumnSnapshot> columns) {

        public ColumnSnapshot column(String name) {
            for (Map.Entry<String, ColumnSnapshot> entry : columns.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            return null;
        }
    }

    public record ColumnSnapshot(String type, boolean notNull) {
    }

    public TableSnapshot table(String name) {
        for (Map.Entry<String, TableSnapshot> entry : tables.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public static SchemaSnapshot of(SchemaModel model) {
        Map<String, TableSnapshot> tables = new LinkedHashMap<>();
        for (TableModel table : model.tables()) {
            Map<String, ColumnSnapshot> columns = new LinkedHashMap<>();
            for (ColumnModel column : table.columns()) {
                columns.put(column.name(), new ColumnSnapshot(column.sqlType(), column.notNull()));
            }
            tables.put(table.name(), new TableSnapshot(columns));
        }
        return new SchemaSnapshot(tables);
    }

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", 1L);
        Map<String, Object> tableMap = new LinkedHashMap<>();
        for (Map.Entry<String, TableSnapshot> table : tables.entrySet()) {
            Map<String, Object> columnMap = new LinkedHashMap<>();
            for (Map.Entry<String, ColumnSnapshot> column : table.getValue().columns().entrySet()) {
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("type", column.getValue().type());
                col.put("notNull", column.getValue().notNull());
                columnMap.put(column.getKey(), col);
            }
            tableMap.put(table.getKey(), Map.of("columns", columnMap));
        }
        root.put("tables", tableMap);
        return Json.write(root);
    }

    @SuppressWarnings("unchecked")
    public static SchemaSnapshot fromJson(String json) {
        Map<String, Object> root = (Map<String, Object>) Json.parse(json);
        Map<String, TableSnapshot> tables = new LinkedHashMap<>();
        Map<String, Object> tableMap = (Map<String, Object>) root.getOrDefault("tables", Map.of());
        for (Map.Entry<String, Object> table : tableMap.entrySet()) {
            Map<String, Object> tableBody = (Map<String, Object>) table.getValue();
            Map<String, Object> columnMap = (Map<String, Object>) tableBody.getOrDefault("columns", Map.of());
            Map<String, ColumnSnapshot> columns = new LinkedHashMap<>();
            for (Map.Entry<String, Object> column : columnMap.entrySet()) {
                Map<String, Object> col = (Map<String, Object>) column.getValue();
                columns.put(column.getKey(), new ColumnSnapshot(
                        (String) col.get("type"),
                        Boolean.TRUE.equals(col.get("notNull"))));
            }
            tables.put(table.getKey(), new TableSnapshot(columns));
        }
        return new SchemaSnapshot(tables);
    }
}
