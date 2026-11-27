package su.onno.command;

import su.onno.access.RoleGrants;
import su.onno.metadata.AttributeDescriptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The commands every {@link Commands} bean declares, indexed by entity class (first key wins when
 * several beans declare the same one). Resolves commands by key for execution, evaluates their
 * per-record functions, and renders the JSON descriptors every surface emits:
 *
 * <pre>
 * { "key": "cancel", "title": "Cancel order", "description": "…", "target": "record",
 *   "group": "…", "form": [ …input descriptors… ], "dynamicForm": true }
 * </pre>
 *
 * <p>Only the declarative behaviour is described; how a client presents a command is its own
 * business. Execution and access checks live in {@code su.onno.service.CommandService} (onno-framework-starter).</p>
 */
public class CommandRegistry {

    private final Map<Class<?>, List<Command<?>>> byEntity = new LinkedHashMap<>();
    private final Map<Class<?>, List<CommandSpec<?>>> specsByEntity = new LinkedHashMap<>();

    @SuppressWarnings({"rawtypes", "unchecked"})
    public CommandRegistry(List<Commands> beans) {
        for (Commands<?> bean : beans) {
            collect(bean);
        }
    }

    private <E> void collect(Commands<E> bean) {
        if (bean.entity() == null) {
            return;
        }
        CommandSpec<E> spec = new CommandSpec<>();
        bean.define(spec);
        List<Command<E>> declared = spec.commands();
        if (!declared.isEmpty() || spec.hasDynamicCommands()) {
            specsByEntity.computeIfAbsent(bean.entity(), k -> new ArrayList<>()).add(spec);
        }
        if (!declared.isEmpty()) {
            List<Command<?>> bucket = byEntity.computeIfAbsent(bean.entity(), k -> new ArrayList<>());
            for (Command<E> c : declared) {
                if (bucket.stream().noneMatch(e -> e.key().equals(c.key()))) {
                    bucket.add(c);
                }
            }
        }
    }

    /** The entities that declare at least one command (static or dynamic). */
    public List<Class<?>> entities() {
        return List.copyOf(specsByEntity.keySet());
    }

    /** The entity's static commands (dynamic providers are not evaluated). */
    public List<Command<?>> staticCommands(Class<?> entity) {
        return byEntity.getOrDefault(entity, List.of());
    }

    /**
     * Startup check: every static command's {@code .roles(...)} names a role the application
     * declares (or maps with {@code fromIdp}), so a misspelled role fails startup instead of
     * silently hiding the command from everyone. Dynamic commands are built from live data and are
     * not checked here.
     */
    public void requireKnownRoles(RoleGrants grants) {
        for (Class<?> entity : entities()) {
            for (Command<?> command : staticCommands(entity)) {
                grants.requireRecognized("command " + entity.getSimpleName() + "#" + command.key() + " .roles(...)",
                        command.roles());
            }
        }
    }

    /**
     * Static and late-bound commands in authored order, first key wins. Static-only entities
     * return the cached list without re-running bean code.
     */
    public List<Command<?>> commands(Class<?> entity) {
        if (!hasDynamicCommands(entity)) {
            return staticCommands(entity);
        }
        Map<String, Command<?>> merged = new LinkedHashMap<>();
        for (CommandSpec<?> spec : specsByEntity.getOrDefault(entity, List.of())) {
            for (Command<?> c : spec.resolveCommands()) {
                merged.putIfAbsent(c.key(), c);
            }
        }
        return List.copyOf(merged.values());
    }

    /** Whether the entity declares a provider that must be evaluated per request. */
    public boolean hasDynamicCommands(Class<?> entity) {
        return specsByEntity.getOrDefault(entity, List.of()).stream()
                .anyMatch(CommandSpec::hasDynamicCommands);
    }

    /** The command with this key (static or late-bound), or {@code null}. */
    public Command<?> find(Class<?> entity, String key) {
        return commands(entity).stream()
                .filter(c -> c.key().equals(key))
                .findFirst()
                .orElse(null);
    }

    /**
     * Whether any static record command of {@code entity} varies per record — the cheap guard the
     * list feed checks before decorating rows. Late-bound providers are excluded: their state is
     * resolved for one record by {@code GET /api/commands/{kind}/{name}?id=}.
     */
    public boolean hasPerRecordCommands(Class<?> entity) {
        return staticCommands(entity).stream().anyMatch(c -> c.isRecordCommand() && c.isPerRecord());
    }

    /**
     * Per-record state of the entity's per-record commands, keyed by command key — what the list
     * feed attaches to each row under {@code _actions}: {@code {visible, enabled, title?}}.
     * Commands that don't vary per record are absent (the descriptor applies unchanged). A function
     * that throws counts as visible + enabled with the fixed title, so one bad predicate can't
     * break the list. Late-bound commands are not evaluated per row (a provider may query); a
     * client resolves them for one record through {@code GET /api/commands/{kind}/{name}?id=}.
     */
    public Map<String, Object> rowState(Class<?> entity, Map<String, Object> row,
                                        List<AttributeDescriptor> attributes) {
        return rowState(staticCommands(entity), row, attributes);
    }

    /** Per-record state from an already resolved command snapshot. */
    public static Map<String, Object> rowState(List<? extends Command<?>> commands, Map<String, Object> row,
                                               List<AttributeDescriptor> attributes) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Command<?> c : commands) {
            if (c.isRecordCommand() && c.isPerRecord()) {
                RecordState state = recordState(c, row, attributes);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("visible", state.available());
                m.put("enabled", state.enabled());
                if (c.titleFn() != null) {
                    m.put("title", state.title());
                }
                out.put(c.key(), m);
            }
        }
        return out;
    }

    /** A record command's state against one record. */
    public record RecordState(boolean available, boolean enabled, String title) {}

    /**
     * Evaluate {@code c}'s per-record functions against {@code record}. A function that throws
     * falls back to available + enabled + the fixed title. A collection command is always
     * available and enabled.
     */
    public static <E> RecordState recordState(Command<E> c, Map<String, Object> record,
                                              List<AttributeDescriptor> attributes) {
        RecordRow<E> row = new RecordRow<>(record, attributes);
        String title = c.titleFn() != null ? evalString(c.titleFn(), row) : null;
        return new RecordState(
                eval(c.availableFn(), row),
                eval(c.enabledFn(), row),
                title != null && !title.isBlank() ? title : c.title());
    }

    private static <E> boolean eval(Predicate<RecordRow<E>> p, RecordRow<E> row) {
        if (p == null) {
            return true;
        }
        try {
            return p.test(row);
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static <E> String evalString(Function<RecordRow<E>, String> f, RecordRow<E> row) {
        try {
            return f.apply(row);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- descriptors ----------------------------------------------------------------------

    /** The JSON descriptor of one command (see the class doc). */
    public static Map<String, Object> descriptor(Command<?> c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", c.key());
        m.put("title", c.title());
        if (!c.description().isBlank()) {
            m.put("description", c.description());
        }
        m.put("target", c.target().wireValue());
        if (!c.group().isBlank()) {
            m.put("group", c.group());
        }
        if (c.hasInputs()) {
            m.put("form", formDescriptors(c));
            if (c.hasDynamicDefaults()) {
                // The client fetches the opening values from GET …/{key}/defaults.
                m.put("dynamicForm", true);
            }
        }
        return m;
    }

    /**
     * A command's input descriptors: scalar inputs first ({@code kind: "field"}), then the row
     * groups ({@code kind: "group"} with their {@code columns}).
     */
    public static List<Map<String, Object>> formDescriptors(Command<?> c) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CommandInput in : c.inputs()) {
            Map<String, Object> m = inputDescriptor(in);
            m.put("kind", "field");
            out.add(m);
        }
        for (CommandInputGroup g : c.inputGroups()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", "group");
            m.put("key", g.key());
            m.put("label", g.title());
            m.put("required", g.required());
            m.put("columns", g.columns().stream().map(CommandRegistry::inputDescriptor).toList());
            out.add(m);
        }
        return out;
    }

    /** One input's descriptor: {@code key, label, type, options, value, required[, reference, refKind]}. */
    public static Map<String, Object> inputDescriptor(CommandInput in) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", in.key());
        m.put("label", in.title());
        m.put("type", in.type().wireValue());
        m.put("options", in.options());
        m.put("value", in.defaultValue());
        m.put("required", in.required());
        if (in.reference() != null) {
            m.put("reference", in.reference());
            m.put("refKind", in.refKind());
        }
        return m;
    }
}
