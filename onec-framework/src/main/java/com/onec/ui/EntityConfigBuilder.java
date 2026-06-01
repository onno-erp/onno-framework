package com.onec.ui;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-entity configuration scope passed to lambdas on
 * {@code SectionBuilder.catalog/document/register}.
 *
 * <p>Today this exposes only field-level hints (replacing {@code @UiHint}).
 * Future entity-level UI knobs (default sort, list columns subset, form
 * grouping order, etc.) belong here too.</p>
 */
public class EntityConfigBuilder {

    private final Map<String, FieldHintBuilder> fields = new LinkedHashMap<>();

    public FieldHintBuilder field(String name) {
        return fields.computeIfAbsent(name, n -> new FieldHintBuilder(this, n));
    }

    public Map<String, FieldHint> buildFieldHints() {
        Map<String, FieldHint> result = new LinkedHashMap<>();
        for (var entry : fields.entrySet()) {
            result.put(entry.getKey(), entry.getValue().build());
        }
        return Map.copyOf(result);
    }
}
