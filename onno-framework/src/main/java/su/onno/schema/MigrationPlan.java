package su.onno.schema;

import java.util.List;

/** The ordered set of changes that would bring the database in line with the metadata. */
public record MigrationPlan(List<SchemaChange> changes) {

    public static final MigrationPlan EMPTY = new MigrationPlan(List.of());

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    public List<SchemaChange> destructive() {
        return changes.stream().filter(SchemaChange::destructive).toList();
    }

    public List<SchemaChange> safe() {
        return changes.stream().filter(c -> !c.destructive()).toList();
    }

    public String describe() {
        if (changes.isEmpty()) {
            return "Schema is up to date.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Schema migration plan (").append(changes.size()).append(" change")
                .append(changes.size() == 1 ? "" : "s").append("):");
        for (SchemaChange change : changes) {
            sb.append("\n  ").append(change.describe());
            for (String sql : change.sql()) {
                sb.append("\n      ").append(sql.replace("\n", "\n      "));
            }
        }
        return sb.toString();
    }
}
