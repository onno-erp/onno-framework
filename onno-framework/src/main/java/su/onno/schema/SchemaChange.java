package su.onno.schema;

import java.util.List;

/**
 * One change in a {@link MigrationPlan}: what it is, the SQL that performs it, and whether
 * it can lose data ({@code destructive} changes are skipped unless
 * {@code onno.schema.allow-destructive=true}).
 */
public record SchemaChange(
        Type type,
        String table,
        String column,
        String detail,
        boolean destructive,
        List<String> sql
) {

    public enum Type {
        CREATE_TABLE,
        RENAME_TABLE,
        RENAME_COLUMN,
        ADD_COLUMN,
        ALTER_COLUMN_TYPE,
        DROP_COLUMN,
        DROP_TABLE
    }

    public String describe() {
        String marker = destructive ? "!" : (type == Type.CREATE_TABLE || type == Type.ADD_COLUMN ? "+" : "~");
        StringBuilder sb = new StringBuilder();
        sb.append(marker).append(' ').append(type.name().toLowerCase().replace('_', ' '));
        sb.append(' ').append(table);
        if (column != null) {
            sb.append('.').append(column);
        }
        if (detail != null && !detail.isBlank()) {
            sb.append(" (").append(detail).append(')');
        }
        return sb.toString();
    }
}
