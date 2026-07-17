package su.onno.ui;

import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.ui.ActionSpec.Action;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs a custom {@link ActionSpec} server handler for an entity. The button on the list (toolbar /
 * row) or detail surface posts here with the action key and (for row/detail actions) the record id;
 * we resolve the entity, enforce write access, invoke the handler bean, and return its
 * {@link ActionResult} so the client can toast / refresh / navigate. Navigation-only actions never
 * reach the server — the client routes them directly.
 */
@RestController
@RequestMapping("/api/actions")
public class ActionController {

    private static final Logger log = LoggerFactory.getLogger(ActionController.class);

    private final CatalogQueryService catalogQuery;
    private final DocumentQueryService documentQuery;
    private final UiAccessService access;
    private final UiActionResolver actions;
    private final UiProperties properties;
    private final BatchRunner batch;

    public ActionController(CatalogQueryService catalogQuery, DocumentQueryService documentQuery,
                            UiAccessService access, UiActionResolver actions, UiProperties properties,
                            BatchRunner batch) {
        this.catalogQuery = catalogQuery;
        this.documentQuery = documentQuery;
        this.access = access;
        this.actions = actions;
        this.properties = properties;
        this.batch = batch;
    }

    /**
     * Server-action handlers are writes by definition — honour {@code onno.ui.read-only} the same
     * way the generic create/update/delete endpoints do (#227).
     */
    private void requireWritable() {
        if (properties.isReadOnly()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "UI is in read-only mode");
        }
    }

    /**
     * Resolve the entity's server action by key and check the caller may run it: it must exist,
     * carry a handler, and — when the action declares {@code .roles(...)} — the caller must hold
     * one of them (#227). Entity write access is already enforced by the caller via
     * {@link #resolveAndAuthorize}; the role list is the finer, per-action gate on top.
     */
    private Action findRunnable(Class<?> entity, String key, Principal principal) {
        Action action = actions.find(entity, key);
        if (action == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown action: " + key);
        }
        if (!action.isServer()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Action is navigation-only: " + key);
        }
        if (!action.roles().isEmpty() && !access.hasAnyRole(principal, action.roles())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed to run action: " + key);
        }
        return action;
    }

    @PostMapping("/{kind}/{name}/{key}")
    public ActionResult run(@PathVariable String kind, @PathVariable String name, @PathVariable String key,
                            @RequestParam(required = false) UUID id,
                            @RequestBody(required = false) Map<String, Object> body, Principal principal) {
        requireWritable();
        Class<?> entity = resolveAndAuthorize(kind, name, principal);
        Action action = findRunnable(entity, key, principal);
        ActionContext ctx = ActionContext.from(kind, name, id,
                principal != null ? principal.getName() : null, body);
        ActionResult result = action.handler().apply(ctx);
        return result != null ? result : ActionResult.ok();
    }

    /**
     * The opening values of an action's form for one record — the {@code formDefaults} hook
     * ({@link ActionSpec.ActionBuilder#formDefaults}) evaluated at open time. The modal fetches
     * this before rendering when the action descriptor carries {@code dynamicForm: true}.
     *
     * <p>A read: no {@code requireWritable} gate (that stays on the POST that actually runs the
     * handler), but the caller must still hold entity write access + the action's roles — the same
     * checks that gate seeing/running the button. A hook that throws yields empty defaults (the
     * dialog falls back to the static seeding) rather than breaking the modal.</p>
     */
    @GetMapping("/{kind}/{name}/{key}/form")
    public Map<String, Object> formDefaults(@PathVariable String kind, @PathVariable String name,
                                            @PathVariable String key,
                                            @RequestParam(required = false) UUID id, Principal principal) {
        Class<?> entity = resolveAndAuthorize(kind, name, principal);
        Action action = findRunnable(entity, key, principal);
        if (!action.hasDynamicForm()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Action has no dynamic form: " + key);
        }
        ActionContext ctx = new ActionContext(kind, name, id,
                principal != null ? principal.getName() : null, Map.of(), Map.of());
        try {
            ActionSpec.FormDefaults defaults = action.formDefaultsFn().apply(ctx);
            if (defaults == null) {
                defaults = new ActionSpec.FormDefaults(Map.of(), Map.of());
            }
            return Map.of("values", defaults.values(), "rows", defaults.rows());
        } catch (RuntimeException e) {
            // Defaults are a convenience — never block the dialog on a broken hook.
            log.warn("formDefaults for {}/{}/{} failed: {}", kind, name, key, e.getMessage());
            return Map.of("values", Map.of(), "rows", Map.of());
        }
    }

    /** How many records one batch call may target — a UI batch, not a data-migration channel. */
    static final int BATCH_LIMIT = 500;

    /**
     * Run a server action over a set of records in one request — the list's batch selection posts
     * here instead of firing N single calls. The handler is invoked per id in its own
     * transaction-per-invocation semantics (identical to N single calls, minus the HTTP round-trips);
     * a failing id is recorded and the batch continues. Ids are resolved concurrently on the shared
     * {@link BatchRunner} pool ({@code onno.ui.batch.parallelism}). Returns {@code {ok, failed, total}}
     * so the client can toast a summary. Capped at {@link #BATCH_LIMIT} ids.
     */
    @PostMapping("/{kind}/{name}/{key}/batch")
    public Map<String, Object> runBatch(@PathVariable String kind, @PathVariable String name,
                                        @PathVariable String key,
                                        @RequestBody Map<String, Object> body, Principal principal) {
        requireWritable();
        Class<?> entity = resolveAndAuthorize(kind, name, principal);
        Action action = findRunnable(entity, key, principal);
        List<UUID> ids = idList(body);
        String user = principal != null ? principal.getName() : null;
        // Parse the shared inputs/rows once; each id runs with the same collected values.
        ActionContext shared = ActionContext.from(kind, name, null, user, body);
        return batch.run(ids, id -> action.handler().apply(
                new ActionContext(kind, name, id, user, shared.inputs(), shared.rows())));
    }

    /** Parse and bound the {@code {"ids": [...]}} list of a batch request. */
    static List<UUID> idList(Map<String, Object> body) {
        if (body == null || !(body.get("ids") instanceof List<?> raw) || raw.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ids required");
        }
        if (raw.size() > BATCH_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Batch limited to " + BATCH_LIMIT + " ids");
        }
        List<UUID> ids = new ArrayList<>(raw.size());
        for (Object o : raw) {
            try {
                ids.add(UUID.fromString(String.valueOf(o)));
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bad id: " + o);
            }
        }
        return ids;
    }

    private Class<?> resolveAndAuthorize(String kind, String name, Principal principal) {
        switch (kind) {
            case "catalogs" -> {
                CatalogDescriptor desc = catalogQuery.require(name);
                access.requireWrite(principal, desc);
                return desc.javaClass();
            }
            case "documents" -> {
                DocumentDescriptor desc = documentQuery.require(name);
                access.requireWrite(principal, desc);
                return desc.javaClass();
            }
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown kind: " + kind);
        }
    }
}
