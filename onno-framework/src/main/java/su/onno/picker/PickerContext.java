package su.onno.picker;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The live form a reference picker is searching for, handed to {@link PickerOptions}.
 *
 * @param targetKind {@code catalog} or {@code document} — the kind of the referenced entity
 * @param targetName logical name of the referenced entity
 * @param fieldPath  top-level field name or {@code section.field}
 * @param formValues current top-level form values, keyed by Java field name
 * @param section    current tabular section, or {@code null} for a top-level field
 * @param rowIndex   current tabular row index, or {@code null}
 * @param rowValues  current tabular row values, keyed by Java field name
 * @param recordId   the record being edited, or {@code null} on create
 */
public record PickerContext(
        String targetKind,
        String targetName,
        String fieldPath,
        Map<String, Object> formValues,
        String section,
        Integer rowIndex,
        Map<String, Object> rowValues,
        UUID recordId
) {
    public PickerContext {
        formValues = formValues == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(formValues));
        rowValues = rowValues == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(rowValues));
    }
}
