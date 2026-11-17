package su.onno.command;

import java.util.List;
import java.util.Map;

/**
 * Opening values of a command's inputs, computed by {@link CommandBuilder#defaults}: {@code values}
 * seed the scalar inputs (keyed by input key), {@code rows} seed the row groups (keyed by group
 * key, each row a {@code column → value} map — the same shape the command receives back). Either
 * map may be empty; a key the command doesn't declare is ignored.
 */
public record CommandDefaults(Map<String, String> values, Map<String, List<Map<String, String>>> rows) {

    public CommandDefaults {
        values = values == null ? Map.of() : Map.copyOf(values);
        rows = rows == null ? Map.of() : Map.copyOf(rows);
    }

    /** No defaults. */
    public static CommandDefaults none() {
        return new CommandDefaults(Map.of(), Map.of());
    }

    /** Defaults carrying only row-group rows. */
    public static CommandDefaults ofRows(String groupKey, List<Map<String, String>> groupRows) {
        return new CommandDefaults(Map.of(), Map.of(groupKey, groupRows));
    }

    /** Defaults carrying only scalar input values. */
    public static CommandDefaults ofValues(Map<String, String> values) {
        return new CommandDefaults(values, Map.of());
    }
}
