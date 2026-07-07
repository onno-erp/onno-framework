package su.onno.ui;

import su.onno.ui.ActionSpec.Action;
import su.onno.ui.InputSpec.InputField;

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

    /**
     * Whether any row action of {@code entity} varies per row — the cheap guard the list-data feed
     * checks before decorating rows. When false (the common case), rows ship untouched.
     */
    public boolean hasDynamicRowActions(Class<?> entity) {
        return forEntity(entity).stream().anyMatch(a -> a.scope() == ActionScope.ROW && a.isDynamic());
    }

    /**
     * Per-row state for the entity's dynamic row actions, keyed by action key — what the list feed
     * attaches to each row (under {@code _actions}) so the client can render that row's button with
     * the right icon/label and honour its visibility/enabled state. Only dynamic row actions appear;
     * static ones render from the descriptor unchanged. A function that throws is treated as a no-op
     * (the descriptor value / visible+enabled) so one bad predicate can't break the list.
     */
    public Map<String, Object> rowActionState(Class<?> entity, Map<String, Object> row) {
        ActionRow actionRow = new ActionRow(row);
        Map<String, Object> out = new LinkedHashMap<>();
        for (Action a : forEntity(entity)) {
            if (a.scope() != ActionScope.ROW || !a.isDynamic()) {
                continue;
            }
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("visible", eval(a.visibleFn(), actionRow, true));
            state.put("enabled", eval(a.enabledFn(), actionRow, true));
            if (a.iconFn() != null) {
                String icon = evalString(a.iconFn(), actionRow);
                if (icon != null) {
                    state.put("icon", icon);
                }
            }
            if (a.labelFn() != null) {
                String label = evalString(a.labelFn(), actionRow);
                if (label != null) {
                    state.put("label", label);
                }
            }
            out.put(a.key(), state);
        }
        return out;
    }

    private static boolean eval(java.util.function.Predicate<ActionRow> p, ActionRow row, boolean fallback) {
        if (p == null) {
            return fallback;
        }
        try {
            return p.test(row);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String evalString(java.util.function.Function<ActionRow, String> f, ActionRow row) {
        try {
            return f.apply(row);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public List<InputField> inputsForEntity(Class<?> entity) {
        return inputsByEntity.getOrDefault(entity, List.of());
    }

    /** Descriptor maps for the toolbar inputs — what the list surface emits for the client to render. */
    public List<Map<String, Object>> inputDescriptors(Class<?> entity) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (InputField in : inputsForEntity(entity)) {
            out.add(inputFieldMap(in));
        }
        return out;
    }

    /** One input field's descriptor map — shared by the toolbar inputs and an action's form fields. */
    public static Map<String, Object> inputFieldMap(InputField in) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", in.key());
        m.put("label", in.label());
        m.put("type", in.type().name().toLowerCase());
        m.put("placeholder", in.placeholder());
        m.put("options", in.options());
        m.put("value", in.defaultValue());
        m.put("required", in.required());
        return m;
    }

    /** An action's form-field descriptor maps ({@link ActionSpec.ActionBuilder#form}), empty when none. */
    public static List<Map<String, Object>> formDescriptors(Action a) {
        if (!a.hasForm()) {
            return List.of();
        }
        return a.form().stream().map(UiActionResolver::inputFieldMap).toList();
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
            if (a.color() != null && !a.color().isBlank()) {
                m.put("color", a.color());
            }
            m.put("scope", a.scope().name().toLowerCase());
            if (a.menu() != null && !a.menu().isBlank()) {
                // Context-menu placement: the client renders this row action inside the row's
                // right-click menu, under a submenu with this label, instead of as an icon button.
                m.put("menu", a.menu());
            }
            m.put("server", a.isServer());
            if (!a.isServer()) {
                m.put("url", a.navigateUrl());
            }
            if (a.hasForm()) {
                // The click first opens a modal collecting these fields; values POST as inputs.
                m.put("form", formDescriptors(a));
            }
            out.add(m);
        }
        return out;
    }
}
