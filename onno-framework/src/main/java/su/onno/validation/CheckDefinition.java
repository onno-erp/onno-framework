package su.onno.validation;

import java.util.List;

/**
 * One resolved live check.
 *
 * @param key            stable key, unique within the entity
 * @param check          what it computes
 * @param dependencies   the fields whose changes re-run it (dot paths address tabular columns)
 * @param debounceMillis how long a client waits after the last change before re-running it
 */
public record CheckDefinition(
        String key,
        Check check,
        List<String> dependencies,
        long debounceMillis
) {
    public CheckDefinition {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        debounceMillis = Math.max(0, debounceMillis);
    }
}
