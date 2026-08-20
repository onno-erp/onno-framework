package su.onno.schema;

import java.util.List;

/**
 * Desired state of a single table, derived from metadata.
 *
 * @param name          table name (already through the naming strategy)
 * @param columns       columns in declaration order
 * @param constraints   trailing table constraints rendered verbatim,
 *                      e.g. {@code PRIMARY KEY (product, warehouse)}
 * @param previousNames candidate former table names, in priority order, for rename detection
 * @param uniqueKey     columns of the table's business key, empty when it has none. Unlike
 *                      {@link #constraints} this is structured rather than verbatim SQL, because the
 *                      diff engine has to compare it against the live database's unique constraints
 *                      and reconcile the two — see {@code SchemaDiffEngine.diffUniqueKey}. A table
 *                      that declares one is treated as owning every {@code UNIQUE} constraint on it.
 */
public record TableModel(
        String name,
        List<ColumnModel> columns,
        List<String> constraints,
        List<String> previousNames,
        List<String> uniqueKey
) {

    /** A table with no business key of its own. */
    public TableModel(String name, List<ColumnModel> columns, List<String> constraints,
                      List<String> previousNames) {
        this(name, columns, constraints, previousNames, List.of());
    }

    public ColumnModel column(String columnName) {
        for (ColumnModel column : columns) {
            if (column.name().equalsIgnoreCase(columnName)) {
                return column;
            }
        }
        return null;
    }
}
