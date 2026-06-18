package su.onno.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders DDL statements from the schema model. Everything here is plain unquoted-identifier
 * SQL that both H2 and PostgreSQL accept; the few statements whose syntax diverges
 * (ALTER COLUMN type changes) branch on {@link SqlDialect}.
 */
final class DdlRenderer {

    private DdlRenderer() {
    }

    static String createTable(TableModel table) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ").append(table.name()).append(" (\n");
        boolean first = true;
        for (ColumnModel column : table.columns()) {
            if (!first) {
                sb.append(",\n");
            }
            sb.append("    ").append(columnDefinition(column));
            first = false;
        }
        for (String constraint : table.constraints()) {
            sb.append(",\n    ").append(constraint);
        }
        sb.append("\n)");
        return sb.toString();
    }

    static String columnDefinition(ColumnModel column) {
        StringBuilder sb = new StringBuilder();
        sb.append(column.name()).append(" ").append(column.sqlType());
        if (column.primaryKey()) {
            sb.append(" PRIMARY KEY");
        }
        if (column.references() != null) {
            sb.append(" REFERENCES ").append(column.references());
        }
        if (column.defaultExpr() != null) {
            sb.append(" DEFAULT ").append(column.defaultExpr());
        }
        if (column.notNull() && !column.primaryKey()) {
            sb.append(" NOT NULL");
        }
        return sb.toString();
    }

    /**
     * Statements to add a column to an existing table. A {@code NOT NULL} column cannot be
     * added in one step once the table has rows, so the column is added nullable (with its
     * default, if any), existing rows are backfilled, and only then is the constraint
     * enforced. When the type has no usable zero value (UUID, dates, refs) the column is
     * left nullable; the returned {@code requiresBackfill} detail is surfaced in the plan.
     */
    static List<String> addColumn(String tableName, ColumnModel column) {
        List<String> statements = new ArrayList<>();
        StringBuilder add = new StringBuilder();
        add.append("ALTER TABLE ").append(tableName)
                .append(" ADD COLUMN ").append(column.name()).append(" ").append(column.sqlType());
        if (column.references() != null) {
            add.append(" REFERENCES ").append(column.references());
        }
        if (column.defaultExpr() != null) {
            add.append(" DEFAULT ").append(column.defaultExpr());
        }
        statements.add(add.toString());

        if (column.notNull()) {
            String backfill = column.defaultExpr() != null
                    ? column.defaultExpr()
                    : zeroValue(column.sqlType());
            if (backfill != null) {
                statements.add("UPDATE " + tableName + " SET " + column.name() + " = " + backfill
                        + " WHERE " + column.name() + " IS NULL");
                statements.add("ALTER TABLE " + tableName + " ALTER COLUMN " + column.name()
                        + " SET NOT NULL");
            }
        }
        return statements;
    }

    /** Whether {@link #addColumn} can enforce NOT NULL for this column on a populated table. */
    static boolean canEnforceNotNull(ColumnModel column) {
        return column.defaultExpr() != null || zeroValue(column.sqlType()) != null;
    }

    static String renameTable(String oldName, String newName) {
        return "ALTER TABLE " + oldName + " RENAME TO " + newName;
    }

    static String renameColumn(String tableName, String oldName, String newName) {
        return "ALTER TABLE " + tableName + " RENAME COLUMN " + oldName + " TO " + newName;
    }

    static String alterColumnType(String tableName, String columnName, String newType, SqlDialect dialect) {
        if (dialect == SqlDialect.POSTGRESQL) {
            return "ALTER TABLE " + tableName + " ALTER COLUMN " + columnName + " TYPE " + newType;
        }
        return "ALTER TABLE " + tableName + " ALTER COLUMN " + columnName + " SET DATA TYPE " + newType;
    }

    static String dropColumn(String tableName, String columnName) {
        return "ALTER TABLE " + tableName + " DROP COLUMN " + columnName;
    }

    static String dropTable(String tableName) {
        return "DROP TABLE " + tableName;
    }

    /** A neutral backfill value for the given SQL type, or null when none is sensible. */
    static String zeroValue(String sqlType) {
        String base = sqlType.toUpperCase(Locale.ROOT);
        if (base.startsWith("VARCHAR") || base.equals("TEXT")) {
            return "''";
        }
        if (base.equals("INTEGER") || base.equals("BIGINT")
                || base.startsWith("DECIMAL") || base.startsWith("DOUBLE")) {
            return "0";
        }
        if (base.equals("BOOLEAN")) {
            return "FALSE";
        }
        return null;
    }
}
