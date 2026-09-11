package su.onno.schema;

import su.onno.schema.DatabaseIntrospector.DbState;
import su.onno.schema.DatabaseIntrospector.UniqueConstraint;
import su.onno.schema.SchemaChange.Type;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static su.onno.schema.DatabaseIntrospector.upper;

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
 * <p>A table's business key ({@link TableModel#uniqueKey()}, today the information registers') is
 * diffed against the live database's {@code UNIQUE} constraints rather than against the snapshot, so
 * a mismatch is caught even on the first boot after the framework learned to look. Without this an
 * information register whose key tuple changed — a new {@code periodicity}, an added or removed
 * {@code @Dimension} — kept enforcing the old key, and its upserts silently collapsed rows onto it.
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
                diffColumn(table, column, snapshotTable, changes);
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

        // Between the two column loops: the key has to be reconciled after the columns it names have
        // been added, and before a dropped dimension's column goes — a column a constraint still
        // references cannot be dropped.
        diffUniqueKey(table, existingName, db, changes);

        for (String dbColumn : dbColumns) {
            if (!desiredColumnsUpper.contains(dbColumn)
                    && !renamedAwayColumns.contains(dbColumn)
                    && snapshotTable != null
                    && snapshotTable.column(dbColumn) != null) {
                String columnName = dbColumn.toLowerCase(Locale.ROOT);
                changes.add(new SchemaChange(Type.DROP_COLUMN, table.name(), columnName,
                        "no longer in the metadata model", true,
                        List.of(DdlRenderer.dropColumn(table.name(), columnName))));
            }
        }
    }

    /**
     * Reconciles a table's declared business key with the {@code UNIQUE} constraints the database
     * actually holds. A table that declares a key is generated storage the framework owns outright,
     * so every {@code UNIQUE} constraint on it that is not the declared key is treated as a leftover
     * and dropped. (Constraints, not indexes: a {@code CREATE UNIQUE INDEX} someone added themselves
     * is not reported by {@code TABLE_CONSTRAINTS} and is left alone.)
     *
     * <p>Looked up under {@code existingName} because the database still holds the table under its
     * former name when this same plan renames it, while the DDL targets the new name the rename —
     * emitted earlier — will have already applied. Key columns need no such mapping of their own:
     * {@code previousNames} lives on {@code @Attribute}, and a register's key is built from
     * {@code @Dimension} fields, so a key column is never renamed in place.
     */
    private void diffUniqueKey(TableModel table, String existingName, DbState db,
                               List<SchemaChange> changes) {
        List<String> desired = table.uniqueKey();
        if (desired.isEmpty()) {
            return;
        }
        Set<String> desiredUpper = new HashSet<>();
        for (String column : desired) {
            desiredUpper.add(upper(column));
        }

        boolean satisfied = false;
        List<UniqueConstraint> stale = new ArrayList<>();
        for (UniqueConstraint constraint : db.uniqueConstraints(existingName)) {
            if (constraint.columns().equals(desiredUpper)) {
                satisfied = true;
            } else {
                stale.add(constraint);
            }
        }
        if (satisfied && stale.isEmpty()) {
            return;
        }

        List<String> sql = new ArrayList<>();
        for (UniqueConstraint constraint : stale) {
            sql.add(DdlRenderer.dropConstraint(table.name(), constraint.name()));
        }
        if (!satisfied) {
            sql.add(DdlRenderer.addUniqueConstraint(table.name(), desired));
        }

        // Adding a constraint is the only step that can fail, and it cannot when one of the
        // constraints being dropped covered a subset of the new key: uniqueness on a subset already
        // implies uniqueness on the whole tuple. That is the shape of every key-widening change — a
        // finer periodicity, an added dimension — so those apply by default. Everything else may meet
        // rows that duplicate under the new key, and is gated like the other narrowing changes.
        boolean impliedByAnExistingKey =
                stale.stream().anyMatch(constraint -> desiredUpper.containsAll(constraint.columns()));
        boolean destructive = !satisfied && !impliedByAnExistingKey;

        StringBuilder detail = new StringBuilder();
        if (stale.isEmpty()) {
            detail.append("no unique key");
        } else {
            for (int i = 0; i < stale.size(); i++) {
                detail.append(i == 0 ? "" : ", ").append(columnTuple(stale.get(i).columns()));
            }
        }
        detail.append(" -> (").append(String.join(", ", desired)).append(')');
        if (destructive) {
            detail.append("; fails unless existing rows are already unique on the new key");
        }

        changes.add(new SchemaChange(Type.ALTER_UNIQUE_KEY, table.name(), null,
                detail.toString(), destructive, List.copyOf(sql)));
    }

    private static String columnTuple(Set<String> columns) {
        return "(" + String.join(", ", new TreeSet<>(columns)).toLowerCase(Locale.ROOT) + ")";
    }

    private void diffColumn(TableModel table, ColumnModel column,
                            SchemaSnapshot.TableSnapshot snapshotTable,
                            List<SchemaChange> changes) {
        if (snapshotTable == null) {
            return;
        }
        SchemaSnapshot.ColumnSnapshot recorded = snapshotTable.column(column.name());
        if (recorded == null) {
            return;
        }
        if (recorded.type() != null) {
            String oldType = recorded.type().trim();
            String newType = column.sqlType().trim();
            if (!oldType.equalsIgnoreCase(newType)) {
                boolean widening = isWidening(oldType, newType);
                changes.add(new SchemaChange(Type.ALTER_COLUMN_TYPE, table.name(), column.name(),
                        oldType + " -> " + newType, !widening,
                        List.of(DdlRenderer.alterColumnType(
                                table.name(), column.name(), newType, dialect))));
            }
        }
        if (recorded.notNull() != column.notNull()) {
            boolean tightening = column.notNull();
            String detail = tightening
                    ? "nullable -> NOT NULL; backfill existing nulls before applying"
                    : "NOT NULL -> nullable";
            changes.add(new SchemaChange(Type.ALTER_COLUMN_NULLABILITY,
                    table.name(), column.name(), detail, tightening,
                    List.of(DdlRenderer.alterColumnNullability(
                            table.name(), column.name(), column.notNull()))));
        }
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
