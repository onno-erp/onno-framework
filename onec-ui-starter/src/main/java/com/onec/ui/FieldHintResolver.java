package com.onec.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-entity field hints, authored on each {@link EntityView#fields} and resolved
 * by class. The source of truth for field order/visibility/widget — what used to
 * live in the layout's section calls. Only the default view ({@code profile()==null})
 * contributes, matching the previously global (profile-agnostic) hint resolution.
 *
 * <p>Kept separate from {@link UiViewResolver} on purpose: that resolver depends on
 * {@link ResolvedMetadataService}, which consumes these hints — folding them in
 * would create a cycle. This bean depends only on the views.</p>
 */
public class FieldHintResolver {

    private final Map<Class<?>, Map<String, FieldHint>> hints = new LinkedHashMap<>();
    private final Map<Class<?>, Map<String, String>> actions = new LinkedHashMap<>();

    public FieldHintResolver(List<EntityView> views) {
        for (EntityView view : views) {
            if (view.entity() == null || view.profile() != null) {
                continue;
            }
            EntityConfigBuilder cfg = new EntityConfigBuilder();
            view.fields(cfg);
            hints.put(view.entity(), cfg.buildFieldHints());
            actions.put(view.entity(), cfg.buildActions());
        }
    }

    /** Field hints for an entity, or an empty map if its view defines none. */
    public Map<String, FieldHint> forEntity(Class<?> entity) {
        return hints.getOrDefault(entity, Map.of());
    }

    /** Detail-header action placement overrides for an entity ({@code action -> primary|menu|hidden}). */
    public Map<String, String> actionsFor(Class<?> entity) {
        return actions.getOrDefault(entity, Map.of());
    }
}
