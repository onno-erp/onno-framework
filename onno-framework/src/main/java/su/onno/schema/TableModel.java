package su.onno.schema;

import java.util.List;

/**
 * Desired state of a single table, derived from metadata.
 *
 * @param name          table name (already through the naming strategy)
 * @param columns       columns in declaration order
 * @param constraints   trailing table constraints rendered verbatim,
 *                      e.g. {@code PRIMARY KEY (product, warehouse)} or {@code UNIQUE (_period, product)}
 * @param previousNames candidate former table names, in priority order, for rename detection
 */
public record TableModel(
        String name,
        List<ColumnModel> columns,
        List<String> constraints,
        List<String> previousNames
) {

    public ColumnModel column(String columnName) {
        for (ColumnModel column : columns) {
            if (column.name().equalsIgnoreCase(columnName)) {
                return column;
            }
        }
        return null;
    }
}
