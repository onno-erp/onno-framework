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
    private final Map<String, String> actions = new LinkedHashMap<>();
    private String icon = "";

    public FieldHintBuilder field(String name) {
        return fields.computeIfAbsent(name, n -> new FieldHintBuilder(this, n));
    }

    /**
     * Configure where a detail-header action shows: {@code post}, {@code unpost},
     * {@code edit} or {@code delete}. By default Post is a primary button and the
     * rest live in the overflow (⋯) menu; override per action with
     * {@code .primary()}, {@code .inMenu()} or {@code .hidden()}.
     */
    public ActionHintBuilder action(String name) {
        return new ActionHintBuilder(this, name);
    }

    void putAction(String name, String placement) {
        actions.put(name, placement);
    }

    /** Action placement overrides ({@code action name -> primary|menu|hidden}). */
    Map<String, String> buildActions() {
        return Map.copyOf(actions);
    }

    /**
     * The nav icon for this entity — any lucide icon name (e.g. {@code "key"},
     * {@code "calendar-check"}). Honored over the keyword heuristic, so an authored
     * icon always wins. Blank means "fall back to the heuristic".
     */
    public EntityConfigBuilder icon(String icon) {
        this.icon = icon;
        return this;
    }

    String buildIcon() {
        return icon;
    }

    public Map<String, FieldHint> buildFieldHints() {
        Map<String, FieldHint> result = new LinkedHashMap<>();
        for (var entry : fields.entrySet()) {
            result.put(entry.getKey(), entry.getValue().build());
        }
        return Map.copyOf(result);
    }
}
