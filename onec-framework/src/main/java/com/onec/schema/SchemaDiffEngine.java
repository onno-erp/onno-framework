package com.onec.schema;

import com.onec.schema.DatabaseIntrospector.DbState;
import com.onec.schema.SchemaChange.Type;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.onec.schema.DatabaseIntrospector.upper;

/**
 * Computes the {@link MigrationPlan} that brings the live database in line with the
 * metadata-derived {@link SchemaModel}.
 *
 * <p>Existence (tables, columns) is diffed against the live database via
 * {@code INFORMATION_SCHEMA}, so it works on day one against any pre-existing deployment.
 * Type changes are detected by comparing declared types against the previous
 * {@link SchemaSnapshot} — exact string comparison of what the metadata declared last
 * time, immune to dialect-specific type reporting. Without a stored snapshot (first boot
 * after upgrading the framework) type changes are invisible; they are picked up once a
 * baseline snapshot exists.
 *
 * <p>Renames are recognized through {@code previousNames} on {@code @Attribute},
 * {@code @Catalog} and {@code @Document}: when the new name is missing from the database
 * but a former name is present, the plan renames (keeping data) instead of creating fresh.
 *
 * <p>Anything that can lose data — dropping tables/columns, narrowing a type — is flagged
 * {@link SchemaChange#destructive()} and only executed when explicitly allowed. Drops are
 * only proposed for objects the framework knows it used to manage (present in the previous
 * snapshot, or columns of a metadata-managed table); user-created tables are never touched.
 */
public class SchemaDiffEngine {

    private final SqlDialect dialect;

    public SchemaDiffEngine(SqlDialect dialect) {
        this.dialect = dialect;
    }

    public MigrationPlan diff(SchemaModel desired, SchemaSnapshot previous, DbState db) {
        List<SchemaChange> changes = new ArrayList<>();
        Set<String> desiredTablesUpper = new HashSet<>();
        for (TableModel table : desired.tables()) {
            desiredTablesUpper.add(upper(table.name()));
        }
        Set<String> renamedAwayTables = new HashSet<>();

        for (TableModel table : desired.tables()) {
            diffTable(table, previous, db, desiredTablesUpper, renamedAwayTables, changes);
        }

        if (previous != null) {
            for (String snapshotTable : previous.tables().keySet()) {
                String tUpper = upper(snapshotTable);
                if (!desiredTablesUpper.contains(tUpper)
                        && !renamedAwayTables.contains(tUpper)
                        && db.hasTable(snapshotTable)) {
                    changes.add(new SchemaChange(Type.DROP_TABLE, snapshotTable, null,
                            "no longer in the metadata model", true,
                            List.of(DdlRenderer.dropTable(snapshotTable))));
                }
            }
        }
        return new MigrationPlan(List.copyOf(changes));
    }

    private void diffTable(TableModel table, SchemaSnapshot previous, DbState db,
                           Set<String> desiredTablesUpper, Set<String> renamedAwayTables,
                           List<SchemaChange> changes) {
        String existingName = null;
        if (db.hasTable(table.name())) {
            existingName = table.name();
        } else {
            for (String formerName : table.previousNames()) {
                if (db.hasTable(formerName) && !desiredTablesUpper.contains(upper(formerName))) {
                    changes.add(new SchemaChange(Type.RENAME_TABLE, table.name(), null,
                            "from " + formerName, false,
                            List.of(DdlRenderer.renameTable(formerName, table.name()))));
                    renamedAwayTables.add(upper(formerName));
                    existingName = formerName;
                    break;
                }
            }
        }

        if (existingName == null) {
            changes.add(new SchemaChange(Type.CREATE_TABLE, table.name(), null, null, false,
                    List.of(DdlRenderer.createTable(table))));
            return;
        }

        Set<String> dbColumns = db.columns(existingName);
        // The snapshot may hold the table under its former name if this boot renames it.
        SchemaSnapshot.TableSnapshot snapshotTable = previous == null ? null : previous.table(table.name());
        if (snapshotTable == null && previous != null) {
            snapshotTable = previous.table(existingName);
        }

        Set<String> desiredColumnsUpper = new HashSet<>();
        for (ColumnModel column : table.columns()) {
            desiredColumnsUpper.add(upper(column.name()));
        }
        Set<String> renamedAwayColumns = new HashSet<>();

        for (ColumnModel column : table.columns()) {
            if (dbColumns.contains(upper(column.name()))) {
                diffColumnType(table, column, snapshotTable, changes);
                continue;
            }
            String renamedFrom = null;
            for (String formerName : column.previousNames()) {
                if (dbColumns.contains(upper(formerName))
                        && !desiredColumnsUpper.contains(upper(formerName))) {
                    renamedFrom = formerName;
                    break;
                }
            }
            if (renamedFrom != null) {
                changes.add(new SchemaChange(Type.RENAME_COLUMN, table.name(), column.name(),
                        "from " + renamedFrom, false,
                        List.of(DdlRenderer.renameColumn(table.name(), renamedFrom, column.name()))));
                renamedAwayColumns.add(upper(renamedFrom));
            } else {
                String detail = null;
                if (column.notNull() && !DdlRenderer.canEnforceNotNull(column)) {
                    detail = "declared required, added as nullable — backfill the data and"
                            + " enforce NOT NULL in an AppMigration";
                }
                changes.add(new SchemaChange(Type.ADD_COLUMN, table.name(), column.name(),
                        detail, false, DdlRenderer.addColumn(table.name(), column)));
            }
        }

        for (String dbColumn : dbColumns) {
            if (!desiredColumnsUpper.contains(dbColumn) && !renamedAwayColumns.contains(dbColumn)) {
                String columnName = dbColumn.toLowerCase(Locale.ROOT);
                changes.add(new SchemaChange(Type.DROP_COLUMN, table.name(), columnName,
                        "no longer in the metadata model", true,
                        List.of(DdlRenderer.dropColumn(table.name(), columnName))));
            }
        }
    }

    private void diffColumnType(TableModel table, ColumnModel column,
                                SchemaSnapshot.TableSnapshot snapshotTable,
                                List<SchemaChange> changes) {
        if (snapshotTable == null) {
            return;
        }
        SchemaSnapshot.ColumnSnapshot recorded = snapshotTable.column(column.name());
        if (recorded == null || recorded.type() == null) {
            return;
        }
        String oldType = recorded.type().trim();
        String newType = column.sqlType().trim();
        if (oldType.equalsIgnoreCase(newType)) {
            return;
        }
        boolean widening = isWidening(oldType, newType);
        changes.add(new SchemaChange(Type.ALTER_COLUMN_TYPE, table.name(), column.name(),
                oldType + " -> " + newType, !widening,
                List.of(DdlRenderer.alterColumnType(table.name(), column.name(), newType, dialect))));
    }

    /** Whether changing {@code oldType} to {@code newType} cannot lose data. */
    static boolean isWidening(String oldType, String newType) {
        String from = oldType.toUpperCase(Locale.ROOT).replace(" ", "");
        String to = newType.toUpperCase(Locale.ROOT).replace(" ", "");
        if (from.equals(to)) {
            return true;
        }
        if (from.startsWith("VARCHAR") && to.equals("TEXT")) {
            return true;
        }
        if (from.startsWith("VARCHAR(") && to.startsWith("VARCHAR(")) {
            return parenArgs(to)[0] >= parenArgs(from)[0];
        }
        if (from.equals("INTEGER") && to.equals("BIGINT")) {
            return true;
        }
        if (from.startsWith("DECIMAL(") && to.startsWith("DECIMAL(")) {
            int[] f = parenArgs(from);
            int[] t = parenArgs(to);
            if (f.length == 2 && t.length == 2) {
                return t[1] >= f[1] && (t[0] - t[1]) >= (f[0] - f[1]);
            }
        }
        return false;
    }

    private static int[] parenArgs(String type) {
        int open = type.indexOf('(');
        int close = type.lastIndexOf(')');
        if (open < 0 || close < open) {
            return new int[0];
        }
        String[] parts = type.substring(open + 1, close).split(",");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Integer.parseInt(parts[i].trim());
        }
        return result;
    }
}
