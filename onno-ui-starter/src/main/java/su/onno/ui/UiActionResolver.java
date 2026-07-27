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
    private final Map<Class<?>, List<ActionSpec>> specsByEntity = new LinkedHashMap<>();
    private final Map<Class<?>, List<InputField>> inputsByEntity = new LinkedHashMap<>();

    public UiActionResolver(List<EntityView> views) {
        for (EntityView view : views) {
            if (view.entity() == null) {
                continue;
            }
            ActionSpec spec = new ActionSpec();
            view.actions(spec);
            List<Action> declared = spec.actions();
            if (!declared.isEmpty() || spec.hasDynamicActions()) {
                specsByEntity.computeIfAbsent(view.entity(), k -> new ArrayList<>()).add(spec);
            }
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

    /**
     * Static and late-bound actions in authored order, with the same first-view/key-wins merge as
     * startup declarations. Static-only entities return the cached list without re-running view
     * code or allocating a new collection.
     */
    public List<Action> resolvedForEntity(Class<?> entity) {
        if (!hasDynamicActions(entity)) {
            return forEntity(entity);
        }
        Map<String, Action> merged = new LinkedHashMap<>();
        for (ActionSpec spec : specsByEntity.getOrDefault(entity, List.of())) {
            for (Action action : spec.resolveActions()) {
                merged.putIfAbsent(action.key(), action);
            }
        }
        return List.copyOf(merged.values());
    }

    /** Whether the entity declares any provider that must be evaluated at menu/action time. */
    public boolean hasDynamicActions(Class<?> entity) {
        return specsByEntity.getOrDefault(entity, List.of()).stream()
                .anyMatch(ActionSpec::hasDynamicActions);
    }

    /** The action whose key matches, or {@code null}. */
    public Action find(Class<?> entity, String key) {
        return resolvedForEntity(entity).stream()
                .filter(a -> a.key().equals(key))
                .findFirst()
                .orElse(null);
    }

    /**
     * Whether any row action of {@code entity} varies per row — the cheap guard the list-data feed
     * checks before decorating rows. Late-bound providers are deliberately excluded: their state
     * is resolved once for the clicked row by the menu endpoint, not once per row in the list feed.
     * When false (the common case), rows ship untouched.
     */
    public boolean hasDynamicRowActions(Class<?> entity) {
        return forEntity(entity).stream()
                .anyMatch(a -> a.scope() == ActionScope.ROW && a.isDynamic());
    }

    /**
     * Per-row state for the entity's dynamic row actions, keyed by action key — what the list feed
     * attaches to each row (under {@code _actions}) so the client can render that row's button with
     * the right icon/label and honour its visibility/enabled state. Only dynamic row actions appear;
     * static ones render from the descriptor unchanged. A function that throws is treated as a no-op
     * (the descriptor value / visible+enabled) so one bad predicate can't break the list.
     */
    public Map<String, Object> rowActionState(Class<?> entity, Map<String, Object> row) {
        List<Action> current;
        try {
            current = resolvedForEntity(entity);
        } catch (RuntimeException e) {
            // A live provider must not take down the list feed. Menu-open/execution still surface
            // the provider failure, while row decoration safely falls back to static state.
            current = forEntity(entity);
        }
        return rowActionState(current, row);
    }

    /** Resolve per-row state from an already resolved action snapshot (one live-provider evaluation). */
    public Map<String, Object> rowActionState(List<Action> actions, Map<String, Object> row) {
        ActionRow actionRow = new ActionRow(row);
        Map<String, Object> out = new LinkedHashMap<>();
        for (Action a : actions) {
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

    /**
     * A DETAIL action's per-record resolution (issue #255): the same {@code visibleWhen} /
     * {@code enabledWhen} / {@code label(fn)} / {@code icon(fn)} functions a ROW action evaluates
     * per list row, evaluated once against the loaded detail record. {@code label}/{@code icon}
     * fall back to the fixed values; a function that throws falls back the same way the row path
     * does (visible + enabled + fixed label/icon), so one bad predicate can't break the surface.
     */
    public record RecordActionState(boolean visible, boolean enabled, String label, String icon) {}

    /** Evaluate {@code a}'s per-record functions against a loaded record (see {@link RecordActionState}). */
    public static RecordActionState recordActionState(Action a, Map<String, Object> record) {
        ActionRow row = new ActionRow(record);
        String label = a.labelFn() != null ? evalString(a.labelFn(), row) : null;
        String icon = a.iconFn() != null ? evalString(a.iconFn(), row) : null;
        return new RecordActionState(
                eval(a.visibleFn(), row, true),
                eval(a.enabledFn(), row, true),
                label != null ? label : a.label(),
                icon != null ? icon : a.icon());
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
        if (in.reference() != null) {
            // A REFERENCE field: the client renders the ref picker for this target entity.
            m.put("reference", in.reference());
            m.put("refKind", in.refKind());
        }
        return m;
    }

    /**
     * An action's form-item descriptor maps ({@link ActionSpec.ActionBuilder#form}), empty when none.
     * Scalar fields come first (tagged {@code kind: "field"}), then the repeatable row groups
     * ({@code kind: "group"}, carrying their {@code columns}); the client renders each accordingly.
     */
    public static List<Map<String, Object>> formDescriptors(Action a) {
        if (!a.hasForm()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (InputField f : a.form()) {
            Map<String, Object> m = inputFieldMap(f);
            m.put("kind", "field");
            out.add(m);
        }
        for (InputSpec.InputGroup g : a.formGroups()) {
            out.add(groupFieldMap(g));
        }
        return out;
    }

    /** Optional presentation metadata for an action form's canonical dialog shell. */
    public static Map<String, Object> formDialogDescriptor(Action a) {
        InputSpec.ActionFormDialog d = a.formDialog();
        if (d == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (d.title() != null && !d.title().isBlank()) out.put("title", d.title());
        if (d.description() != null && !d.description().isBlank()) out.put("description", d.description());
        if (d.submitLabel() != null && !d.submitLabel().isBlank()) out.put("submitLabel", d.submitLabel());
        if (d.cancelLabel() != null && !d.cancelLabel().isBlank()) out.put("cancelLabel", d.cancelLabel());
        if (d.icon() != null && !d.icon().isBlank()) out.put("icon", d.icon());
        if (d.tone() != null) out.put("tone", d.tone().name().toLowerCase());
        if (d.size() != null) out.put("size", d.size().name().toLowerCase());
        return out;
    }

    /** One row group's descriptor map — its columns are scalar input-field maps. */
    static Map<String, Object> groupFieldMap(InputSpec.InputGroup g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "group");
        m.put("key", g.key());
        m.put("label", g.label());
        m.put("required", g.required());
        m.put("columns", g.columns().stream().map(UiActionResolver::inputFieldMap).toList());
        return m;
    }

    /** Descriptor maps for actions in the given scopes — what the list/detail surfaces emit. */
    public List<Map<String, Object>> descriptors(Class<?> entity, Set<ActionScope> scopes) {
        return descriptors(forEntity(entity), scopes);
    }

    /**
     * Current descriptor maps including late-bound providers. Called by the dynamic menu endpoint;
     * static list surfaces continue to use {@link #descriptors(Class, Set)} at zero extra cost.
     */
    public List<Map<String, Object>> resolvedDescriptors(Class<?> entity, Set<ActionScope> scopes) {
        return descriptors(resolvedForEntity(entity), scopes);
    }

    /** Descriptor maps for an already resolved action snapshot. */
    public static List<Map<String, Object>> descriptors(List<Action> actions, Set<ActionScope> scopes) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Action a : actions) {
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
                m.put("formDialog", formDialogDescriptor(a));
                if (a.hasDynamicForm()) {
                    // The modal fetches its opening values from GET /api/actions/{kind}/{name}/{key}/form.
                    m.put("dynamicForm", true);
                }
            }
            out.add(m);
        }
        return out;
    }
}
