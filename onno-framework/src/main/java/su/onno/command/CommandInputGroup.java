package su.onno.command;

import java.util.List;

/** A resolved repeatable row group of a command: each row is a set of typed {@code columns}. */
public record CommandInputGroup(String key, String title, boolean required, List<CommandInput> columns) {

    public CommandInputGroup {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
