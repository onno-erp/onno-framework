package su.onno.validation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The live values a {@link Check} runs against.
 *
 * @param kind       {@code "catalog"} or {@code "document"}
 * @param name       the entity's logical name
 * @param entityType the entity class
 * @param id         the record being edited, or {@code null} for a new one
 * @param values     the current values, keyed by Java field name (tabular sections as row lists)
 */
public record CheckContext(
        String kind,
        String name,
        Class<?> entityType,
        UUID id,
        Map<String, Object> values
) {
    public CheckContext {
        kind = kind == null ? "" : kind;
        name = name == null ? "" : name;
        values = values == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
