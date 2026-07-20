package su.onno.ui;

import java.util.Map;
import java.util.UUID;
import java.util.Collections;
import java.util.LinkedHashMap;

/**
 * Live generated-form context supplied when decorating reference-picker options.
 *
 * @param targetKind {@code catalog} or {@code document}
 * @param targetName registered logical name of the referenced entity
 * @param fieldPath top-level field name or {@code section.field}
 * @param formValues current top-level form values, keyed by Java field name
 * @param section current tabular section, or {@code null} for a top-level field
 * @param rowIndex current tabular row index, or {@code null}
 * @param rowValues current tabular row values, keyed by Java field name
 * @param documentId current document id on edit forms, or {@code null} on create
 */
public record RefOptionContext(
        String targetKind,
        String targetName,
        String fieldPath,
        Map<String, Object> formValues,
        String section,
        Integer rowIndex,
        Map<String, Object> rowValues,
        UUID documentId
) {
    public RefOptionContext {
        formValues = formValues == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(formValues));
        rowValues = rowValues == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(rowValues));
    }
}
