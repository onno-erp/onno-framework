package su.onno.picker;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** One candidate record offered by a reference picker, handed to {@link PickerOptions}. */
public record PickerCandidate(UUID id, Map<String, Object> values) {
    public PickerCandidate {
        values = values == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
