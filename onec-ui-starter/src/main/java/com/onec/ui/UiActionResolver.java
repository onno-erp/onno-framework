package com.onec.ui;

import com.onec.ui.ActionSpec.Action;
import com.onec.ui.InputSpec.InputField;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collects the custom {@link ActionSpec actions} and toolbar {@link InputSpec inputs} declared by
 * every {@link EntityView}, indexed by entity class. Actions resolve two ways: as descriptor maps
 * the list/detail surfaces emit (so the client renders the buttons), and by key for the
 * {@link ActionController} to execute. Inputs resolve as descriptor maps the list toolbar renders.
 */
public class UiActionResolver {

    private final Map<Class<?>, List<Action>> byEntity = new LinkedHashMap<>();
    private final Map<Class<?>, List<InputField>> inputsByEntity = new LinkedHashMap<>();

    public UiActionResolver(List<EntityView> views) {
        for (EntityView view : views) {
            if (view.entity() == null) {
                continue;
            }
            ActionSpec spec = new ActionSpec();
            view.actions(spec);
            List<Action> declared = spec.actions();
            if (!declared.isEmpty()) {
                List<Action> bucket = byEntity.computeIfAbsent(view.entity(), k -> new ArrayList<>());
                // First view to declare a key wins (a profile-specific view can't clobber another's).
                for (Action a : declared) {
                    if (bucket.stream().noneMatch(e -> e.key().equals(a.key()))) {
                        bucket.add(a);
                    }
                }
            }

            InputSpec inputSpec = new InputSpec();
            view.inputs(inputSpec);
            List<InputField> declaredInputs = inputSpec.inputs();
            if (!declaredInputs.isEmpty()) {
                List<InputField> bucket = inputsByEntity.computeIfAbsent(view.entity(), k -> new ArrayList<>());
                for (InputField in : declaredInputs) {
                    if (bucket.stream().noneMatch(e -> e.key().equals(in.key()))) {
                        bucket.add(in);
                    }
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

    public List<InputField> inputsForEntity(Class<?> entity) {
        return inputsByEntity.getOrDefault(entity, List.of());
    }

    /** Descriptor maps for the toolbar inputs — what the list surface emits for the client to render. */
    public List<Map<String, Object>> inputDescriptors(Class<?> entity) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (InputField in : inputsForEntity(entity)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", in.key());
            m.put("label", in.label());
            m.put("type", in.type().name().toLowerCase());
            m.put("placeholder", in.placeholder());
            m.put("options", in.options());
            m.put("value", in.defaultValue());
            out.add(m);
        }
        return out;
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
            if (a.logo() != null && !a.logo().isBlank()) {
                m.put("logo", a.logo());
            }
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
