package su.onno.service;

import su.onno.command.CommandRegistry;
import su.onno.access.AccessMode;
import su.onno.access.AccessSubject;
import su.onno.command.Command;
import su.onno.command.CommandContext;
import su.onno.command.CommandDefaults;
import su.onno.command.CommandFeedback;
import su.onno.command.CommandInput;
import su.onno.command.CommandInputGroup;
import su.onno.command.CommandRejectedException;
import su.onno.command.CommandResult;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs {@link su.onno.command.Commands commands} with the caller's {@link AccessSubject} — the one
 * execution path shared by the REST endpoint ({@code su.onno.web.CommandController}), MCP tools and module
 * endpoints, so every surface enforces the same rules:
 *
 * <ol>
 *   <li>the application is not in read-only mode ({@code onno.web.read-only}) — 403;</li>
 *   <li>the caller may write the entity — 403;</li>
 *   <li>the command exists — 404 — and the caller holds one of its {@code roles} — 403;</li>
 *   <li>a record command names a record inside the caller's write scope — 400 / 404;</li>
 *   <li>the command is available and enabled for that record — 409.</li>
 * </ol>
 *
 * <p>Failures are {@link ServiceException}s; a handler's purposeful
 * {@link su.onno.command.CommandRejectedException} propagates unchanged.</p>
 *
 * <p><b>Why this lives in the web starter, not in core.</b> The command <em>vocabulary</em>
 * ({@code Commands}, {@code CommandRegistry}) is core API; <em>running</em> one on behalf of a caller
 * is not. It needs what only this starter has — the caller's {@link AccessSubject}, the compiled
 * record policies, {@code onno.web.read-only} — and answers in HTTP terms (403/404/409). Core code
 * and typed repositories are trusted and unscoped by design; every caller-facing surface (REST, MCP,
 * import, print, CRM) already depends on this starter and reuses this one path.</p>
 */
public class CommandService {

    private static final Logger log = LoggerFactory.getLogger(CommandService.class);

    /** How many records one batch call may target — a UI batch, not a data-migration channel. */
    public static final int BATCH_LIMIT = 500;

    private final CatalogQueryService catalogQuery;
    private final DocumentQueryService documentQuery;
    private final AccessService access;
    private final CommandRegistry registry;
    private final WebProperties properties;
    private final BatchRunner batch;

    public CommandService(CatalogQueryService catalogQuery, DocumentQueryService documentQuery,
                          AccessService access, CommandRegistry registry, WebProperties properties,
                          BatchRunner batch) {
        this.catalogQuery = catalogQuery;
        this.documentQuery = documentQuery;
        this.access = access;
        this.registry = registry;
        this.properties = properties;
        this.batch = batch;
    }

    public CommandRegistry registry() {
        return registry;
    }

    /** The entity a command call addresses: normalized plural kind, route name, class and attributes. */
    public record Target(String kind, String name, Class<?> type, List<AttributeDescriptor> attributes) {}

    /** Resolve {@code kind}/{@code name} and require entity write access. */
    public Target target(String kind, String name, AccessSubject subject) {
        String k = kind == null ? "" : kind.toLowerCase();
        if (k.equals("catalogs") || k.equals("catalog")) {
            CatalogDescriptor desc = catalogQuery.require(name);
            access.requireWrite(subject, desc);
            return new Target("catalogs", name, desc.javaClass(), desc.attributes());
        }
        if (k.equals("documents") || k.equals("document")) {
            DocumentDescriptor desc = documentQuery.require(name);
            access.requireWrite(subject, desc);
            return new Target("documents", name, desc.javaClass(), desc.attributes());
        }
        throw new ServiceException(ServiceException.Status.NOT_FOUND, "Unknown kind: " + kind);
    }

    /** Whether {@code subject} holds one of the command's roles (an empty list admits every writer). */
    public boolean mayRun(Command<?> command, AccessSubject subject) {
        return command.roles().isEmpty() || access.hasAnyRole(subject, command.roles());
    }

    /** Descriptors of the commands {@code subject} may run, in order. */
    public List<Map<String, Object>> descriptors(List<? extends Command<?>> commands, AccessSubject subject) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Command<?> c : commands) {
            if (mayRun(c, subject)) {
                out.add(CommandRegistry.descriptor(c));
            }
        }
        return out;
    }

    /**
     * The current command descriptors of an entity (late-bound providers evaluated now) and, when
     * {@code id} is given, that record's per-record state: {@code {commands: [...], state: {key:
     * {visible, enabled, title?}}}}.
     */
    public Map<String, Object> listing(String kind, String name, UUID id, AccessSubject subject) {
        Target target = target(kind, name, subject);
        List<Command<?>> current = registry.commands(target.type());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("commands", descriptors(current, subject));
        out.put("state", id == null ? Map.of()
                : CommandRegistry.rowState(current, row(subject, target, id), target.attributes()));
        return out;
    }

    /**
     * The record commands available on one loaded record, for a record surface: each descriptor
     * carries the record's resolved {@code title} and {@code enabled}; unavailable commands and
     * commands the caller may not run are omitted.
     */
    public List<Map<String, Object>> recordCommands(Class<?> type, Map<String, Object> row,
                                                    List<AttributeDescriptor> attributes,
                                                    AccessSubject subject) {
        List<Command<?>> current;
        try {
            current = registry.commands(type);
        } catch (RuntimeException e) {
            log.warn("Command provider for {} failed: {}", type.getSimpleName(), e.getMessage());
            current = registry.staticCommands(type);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Command<?> c : current) {
            if (!c.isRecordCommand() || !mayRun(c, subject)) {
                continue;
            }
            CommandRegistry.RecordState state = CommandRegistry.recordState(c, row, attributes);
            if (!state.available()) {
                continue;
            }
            Map<String, Object> d = CommandRegistry.descriptor(c);
            d.put("title", state.title());
            d.put("enabled", state.enabled());
            out.add(d);
        }
        return out;
    }

    /** Run one command; see the class doc for the checks. */
    public CommandResult run(String kind, String name, String key, UUID id, Map<String, Object> body,
                             AccessSubject subject) {
        requireWritable();
        Target target = target(kind, name, subject);
        Command<?> command = runnable(target, key, subject);
        if (command.isRecordCommand()) {
            if (id == null) {
                throw new ServiceException(ServiceException.Status.BAD_REQUEST, "Command needs a record id: " + key);
            }
            requireRunnableOn(subject, target, id, command);
        } else if (id != null) {
            throw new ServiceException(ServiceException.Status.BAD_REQUEST,
                    "Collection command takes no record id: " + key);
        }
        CommandContext context = CommandContext.from(target.kind(), target.name(), id, username(subject), body, subject);
        requireInputs(command, context);
        CommandResult result = command.handler().apply(context);
        return result != null ? result : CommandResult.ok();
    }

    /**
     * Run a record command over many records in one call. Each id gets the same record checks as a
     * single run and its own handler invocation; a failing id is recorded and the rest still run.
     * Returns {@code {ok, failed, total[, feedback]}}.
     */
    public Map<String, Object> runBatch(String kind, String name, String key, List<UUID> ids,
                                        Map<String, Object> body, AccessSubject subject) {
        requireWritable();
        Target target = target(kind, name, subject);
        Command<?> command = runnable(target, key, subject);
        if (!command.isRecordCommand()) {
            throw new ServiceException(ServiceException.Status.BAD_REQUEST, "Only record commands run in batch: " + key);
        }
        if (ids == null || ids.isEmpty()) {
            throw new ServiceException(ServiceException.Status.BAD_REQUEST, "ids required");
        }
        if (ids.size() > BATCH_LIMIT) {
            throw new ServiceException(ServiceException.Status.BAD_REQUEST, "Batch limited to " + BATCH_LIMIT + " ids");
        }
        CommandContext shared = CommandContext.from(target.kind(), target.name(), null, username(subject), body, subject);
        requireInputs(command, shared);
        AtomicReference<CommandFeedback> firstFeedback = new AtomicReference<>();
        Map<String, Object> result = batch.run(ids, id -> {
            requireRunnableOn(subject, target, id, command);
            CommandResult r = command.handler().apply(shared.withId(id));
            if (r != null && r.feedback() != null) {
                firstFeedback.compareAndSet(null, r.feedback());
            }
        });
        if (!result.containsKey("feedback") && firstFeedback.get() != null) {
            result.put("feedback", firstFeedback.get());
        }
        return result;
    }

    /**
     * The opening values of a command's inputs ({@code defaults(...)}). A read: no read-only gate,
     * but the caller must still be allowed to run the command. A hook that throws yields no
     * defaults.
     */
    public CommandDefaults defaults(String kind, String name, String key, UUID id, AccessSubject subject) {
        Target target = target(kind, name, subject);
        Command<?> command = runnable(target, key, subject);
        if (!command.hasDynamicDefaults()) {
            throw new ServiceException(ServiceException.Status.NOT_FOUND, "Command has no dynamic defaults: " + key);
        }
        if (command.isRecordCommand() && id != null) {
            requireRunnableOn(subject, target, id, command);
        }
        CommandContext ctx = new CommandContext(target.kind(), target.name(), id, username(subject),
                Map.of(), Map.of(), subject);
        try {
            CommandDefaults defaults = command.defaultsFn().apply(ctx);
            return defaults == null ? CommandDefaults.none() : defaults;
        } catch (RuntimeException e) {
            log.warn("defaults for {}/{}/{} failed: {}", kind, name, key, e.getMessage());
            return CommandDefaults.none();
        }
    }

    /** Parse and bound the {@code {"ids": [...]}} list of a batch request body. */
    public static List<UUID> idList(Map<String, Object> body) {
        if (body == null || !(body.get("ids") instanceof List<?> raw) || raw.isEmpty()) {
            throw new ServiceException(ServiceException.Status.BAD_REQUEST, "ids required");
        }
        if (raw.size() > BATCH_LIMIT) {
            throw new ServiceException(ServiceException.Status.BAD_REQUEST, "Batch limited to " + BATCH_LIMIT + " ids");
        }
        List<UUID> ids = new ArrayList<>(raw.size());
        for (Object o : raw) {
            try {
                ids.add(UUID.fromString(String.valueOf(o)));
            } catch (IllegalArgumentException e) {
                throw new ServiceException(ServiceException.Status.BAD_REQUEST, "Bad id: " + o);
            }
        }
        return ids;
    }

    private void requireWritable() {
        if (properties.isReadOnly()) {
            throw new ServiceException(ServiceException.Status.FORBIDDEN, "The application is in read-only mode");
        }
    }

    private Command<?> runnable(Target target, String key, AccessSubject subject) {
        Command<?> command = registry.find(target.type(), key);
        if (command == null) {
            throw new ServiceException(ServiceException.Status.NOT_FOUND, "Unknown command: " + key);
        }
        if (!mayRun(command, subject)) {
            throw new ServiceException(ServiceException.Status.FORBIDDEN, "Not allowed to run command: " + key);
        }
        return command;
    }

    /**
     * Before a record command runs on {@code id}: the record must be inside the caller's write
     * scope (404 otherwise, like a missing record) and the command available and enabled for it
     * (409 otherwise).
     */
    private void requireRunnableOn(AccessSubject subject, Target target, UUID id, Command<?> command) {
        boolean inScope = switch (target.kind()) {
            case "catalogs" -> catalogQuery.inScope(subject, catalogQuery.require(target.name()), id, AccessMode.WRITE);
            case "documents" -> documentQuery.inScope(subject, documentQuery.require(target.name()), id, AccessMode.WRITE);
            default -> false;
        };
        if (!inScope) {
            throw new ServiceException(ServiceException.Status.NOT_FOUND);
        }
        if (command.availableFn() != null || command.enabledFn() != null) {
            CommandRegistry.RecordState state = CommandRegistry.recordState(command, row(subject, target, id),
                    target.attributes());
            if (!state.available() || !state.enabled()) {
                throw new ServiceException(ServiceException.Status.CONFLICT,
                        "Command is not available for this record: " + command.key());
            }
        }
    }

    private Map<String, Object> row(AccessSubject subject, Target target, UUID id) {
        return switch (target.kind()) {
            case "catalogs" -> catalogQuery.get(subject, catalogQuery.require(target.name()), id);
            case "documents" -> documentQuery.get(subject, documentQuery.require(target.name()), id);
            default -> Map.of();
        };
    }

    private static String username(AccessSubject subject) {
        return subject instanceof AccessSubject.User user ? user.username() : null;
    }

    /**
     * Reject a run whose {@code required()} inputs are missing or blank, before the handler sees
     * it. The form validates this client-side, but REST and MCP callers reach the handler directly,
     * so the server is the authority. Throws a {@link CommandRejectedException} (422) whose
     * {@code fieldErrors} name each missing input, so an open input form shows them inline.
     */
    static void requireInputs(Command<?> command, CommandContext context) {
        CommandRejectedException.Builder rejection = null;
        for (CommandInput input : command.inputs()) {
            if (input.required() && context.input(input.key()).isBlank()) {
                rejection = (rejection == null ? CommandRejectedException.builder() : rejection)
                        .fieldError(input.key(), input.title() + " is required");
            }
        }
        for (CommandInputGroup group : command.inputGroups()) {
            List<Map<String, String>> rows = context.inputRows(group.key());
            if (group.required() && rows.isEmpty()) {
                rejection = (rejection == null ? CommandRejectedException.builder() : rejection)
                        .fieldError(group.key(), group.title() + " needs at least one row");
            }
            for (int i = 0; i < rows.size(); i++) {
                for (CommandInput column : group.columns()) {
                    String value = rows.get(i).get(column.key());
                    if (column.required() && (value == null || value.isBlank())) {
                        rejection = (rejection == null ? CommandRejectedException.builder() : rejection)
                                .fieldError(group.key(), "Row " + (i + 1) + ": " + column.title() + " is required");
                    }
                }
            }
        }
        if (rejection != null) {
            throw rejection.message("Fill in the required inputs").build();
        }
    }
}
