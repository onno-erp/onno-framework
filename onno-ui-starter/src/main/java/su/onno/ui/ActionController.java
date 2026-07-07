package su.onno.ui;

import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.ui.ActionSpec.Action;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    private final CatalogQueryService catalogQuery;
    private final DocumentQueryService documentQuery;
    private final UiAccessService access;
    private final UiActionResolver actions;
    private final UiProperties properties;

    public ActionController(CatalogQueryService catalogQuery, DocumentQueryService documentQuery,
                            UiAccessService access, UiActionResolver actions, UiProperties properties) {
        this.catalogQuery = catalogQuery;
        this.documentQuery = documentQuery;
        this.access = access;
        this.actions = actions;
        this.properties = properties;
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
        ActionContext ctx = new ActionContext(kind, name, id,
                principal != null ? principal.getName() : null, inputValues(body));
        ActionResult result = action.handler().apply(ctx);
        return result != null ? result : ActionResult.ok();
    }

    /** How many records one batch call may target — a UI batch, not a data-migration channel. */
    static final int BATCH_LIMIT = 500;

    /**
     * Run a server action over a set of records in one request — the list's batch selection posts
     * here instead of firing N single calls. The handler is invoked per id, sequentially, in the
     * request's transaction-per-invocation semantics (identical to N single calls, minus the HTTP
     * round-trips); a failing id is recorded and the batch continues. Returns {@code {ok, failed,
     * total}} so the client can toast a summary. Capped at {@link #BATCH_LIMIT} ids.
     */
    @PostMapping("/{kind}/{name}/{key}/batch")
    public Map<String, Object> runBatch(@PathVariable String kind, @PathVariable String name,
                                        @PathVariable String key,
                                        @RequestBody Map<String, Object> body, Principal principal) {
        requireWritable();
        Class<?> entity = resolveAndAuthorize(kind, name, principal);
        Action action = findRunnable(entity, key, principal);
        List<UUID> ids = idList(body);
        Map<String, String> inputs = inputValues(body);
        String user = principal != null ? principal.getName() : null;
        int ok = 0;
        List<String> failed = new ArrayList<>();
        for (UUID id : ids) {
            try {
                action.handler().apply(new ActionContext(kind, name, id, user, inputs));
                ok++;
            } catch (RuntimeException e) {
                failed.add(id.toString());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("failed", failed);
        out.put("total", ids.size());
        return out;
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

    /** Pull the toolbar input values out of the request body ({@code {"inputs": {key: value}}}). */
    @SuppressWarnings("unchecked")
    private static Map<String, String> inputValues(Map<String, Object> body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body != null && body.get("inputs") instanceof Map<?, ?> raw) {
            raw.forEach((k, v) -> out.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        }
        return out;
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
