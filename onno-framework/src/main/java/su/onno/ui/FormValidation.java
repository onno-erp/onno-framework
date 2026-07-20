package su.onno.ui;

import java.util.List;

/** Resolved metadata for one live form validator. */
public record FormValidation(
        String key,
        Class<? extends FormValidator> validator,
        List<String> dependencies,
        long debounceMillis
) {
    public FormValidation {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        debounceMillis = Math.max(0, debounceMillis);
    }
}
