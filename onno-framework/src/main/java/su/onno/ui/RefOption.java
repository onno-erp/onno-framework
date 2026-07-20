package su.onno.ui;

import java.util.Map;
import java.util.UUID;
import java.util.Collections;
import java.util.LinkedHashMap;

/**
 * One candidate record supplied to a {@link RefOptionDecorator}.
 */
public record RefOption(UUID id, Map<String, Object> values) {
    public RefOption {
        values = values == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
