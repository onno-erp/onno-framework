package su.onno.schema;

import java.util.List;

/**
 * The full desired database state derived from the {@link su.onno.metadata.MetadataRegistry}:
 * framework tables (sequences, outbox) plus one {@link TableModel} per catalog, document,
 * tabular section, register, enumeration and the constants table.
 */
public record SchemaModel(List<TableModel> tables) {

    public TableModel table(String tableName) {
        for (TableModel table : tables) {
            if (table.name().equalsIgnoreCase(tableName)) {
                return table;
            }
        }
        return null;
    }
}
