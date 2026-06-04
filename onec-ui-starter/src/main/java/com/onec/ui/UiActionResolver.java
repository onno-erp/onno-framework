package com.onec.ui;

import com.onec.ui.ActionSpec.Action;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collects the custom {@link ActionSpec actions} declared by every {@link EntityView}, indexed by
 * entity class, and resolves them two ways: as descriptor maps the list/detail surfaces emit (so
 * the client renders the buttons), and by key for the {@link ActionController} to execute.
 */
public class UiActionResolver {

    private final Map<Class<?>, List<Action>> byEntity = new LinkedHashMap<>();

    public UiActionResolver(List<EntityView> views) {
        for (EntityView view : views) {
            if (view.entity() == null) {
                continue;
            }
            ActionSpec spec = new ActionSpec();
            view.actions(spec);
            List<Action> declared = spec.actions();
            if (declared.isEmpty()) {
                continue;
            }
            List<Action> bucket = byEntity.computeIfAbsent(view.entity(), k -> new ArrayList<>());
            // First view to declare a key wins (a profile-specific view can't clobber another's action).
            for (Action a : declared) {
                if (bucket.stream().noneMatch(e -> e.key().equals(a.key()))) {
                    bucket.add(a);
                }
            }
        }
    }

    public List<Action> forEntity(Class<?> entity) {
        return byEntity.getOrDefault(entity, List.of());
    }

    /** The action whose key matches, or {@code null}. */
    public Action find(Class<?> entity, String key) {
        return forEntity(entity).stream().filter(a -> a.key().equals(key)).findFirst().orElse(null);
    }

    /** Descriptor maps for actions in the given scopes — what the list/detail surfaces emit. */
    public List<Map<String, Object>> descriptors(Class<?> entity, Set<ActionScope> scopes) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Action a : forEntity(entity)) {
            if (!scopes.contains(a.scope())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", a.key());
            m.put("label", a.label());
            m.put("icon", a.icon());
            m.put("scope", a.scope().name().toLowerCase());
            m.put("server", a.isServer());
            if (!a.isServer()) {
                m.put("url", a.navigateUrl());
            }
            out.add(m);
        }
        return out;
    }
}
