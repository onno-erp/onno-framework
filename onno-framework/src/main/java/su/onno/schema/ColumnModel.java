package su.onno.schema;

import java.util.List;

/**
 * Desired state of a single column, derived from metadata. This is what the
 * {@link SchemaGenerator} renders into {@code CREATE TABLE} statements and what the
 * {@link SchemaDiffEngine} compares against the live database.
 *
 * @param name          column name (already through the naming strategy)
 * @param sqlType       declared SQL type, e.g. {@code VARCHAR(100)} or {@code DECIMAL(15,2)}
 * @param primaryKey    rendered as an inline {@code PRIMARY KEY}
 * @param notNull       rendered as {@code NOT NULL}; on existing tables the upgrader
 *                      backfills before enforcing (see {@link DdlRenderer#addColumn})
 * @param defaultExpr   SQL default expression (e.g. {@code FALSE}, {@code 0}, {@code 'NEW'}), or null
 * @param references    inline FK target like {@code document_invoices(_id)}, or null
 * @param previousNames former column names, in priority order, for rename detection
 */
public record ColumnModel(
        String name,
        String sqlType,
        boolean primaryKey,
        boolean notNull,
        String defaultExpr,
        String references,
        List<String> previousNames
) {

    public static ColumnModel of(String name, String sqlType) {
        return new ColumnModel(name, sqlType, false, false, null, null, List.of());
    }

    public static ColumnModel primaryKey(String name, String sqlType) {
        return new ColumnModel(name, sqlType, true, false, null, null, List.of());
    }

    public static ColumnModel withDefault(String name, String sqlType, String defaultExpr) {
        return new ColumnModel(name, sqlType, false, false, defaultExpr, null, List.of());
    }

    public ColumnModel asNotNull() {
        return new ColumnModel(name, sqlType, primaryKey, true, defaultExpr, references, previousNames);
    }
}
