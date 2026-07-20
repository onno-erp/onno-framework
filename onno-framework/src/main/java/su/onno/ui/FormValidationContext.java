package su.onno.ui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Live values supplied to an application-defined asynchronous form validator. */
public record FormValidationContext(
        String kind,
        String name,
        Class<?> entityType,
        UUID id,
        Map<String, Object> values
) {
    public FormValidationContext {
        kind = kind == null ? "" : kind;
        name = name == null ? "" : name;
        values = values == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
