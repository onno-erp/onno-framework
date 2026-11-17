package su.onno.command;

import java.util.List;

/**
 * A resolved command input (a scalar value, or one column of a {@link CommandInputGroup}). For a
 * {@link InputType#REFERENCE} input, {@code reference} is the target catalog/document's logical name
 * and {@code refKind} is {@code "catalog"} or {@code "document"} (both {@code null} otherwise).
 */
public record CommandInput(String key, String title, InputType type,
                           List<String> options, String defaultValue, boolean required,
                           String reference, String refKind) {

    public CommandInput {
        options = options == null ? List.of() : List.copyOf(options);
        defaultValue = defaultValue == null ? "" : defaultValue;
    }
}
