package com.onec.ui;

import com.onec.metadata.CatalogDescriptor;
import com.onec.metadata.DocumentDescriptor;
import com.onec.ui.ActionSpec.Action;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.LinkedHashMap;
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

    public ActionController(CatalogQueryService catalogQuery, DocumentQueryService documentQuery,
                            UiAccessService access, UiActionResolver actions) {
        this.catalogQuery = catalogQuery;
        this.documentQuery = documentQuery;
        this.access = access;
        this.actions = actions;
    }

    @PostMapping("/{kind}/{name}/{key}")
    public ActionResult run(@PathVariable String kind, @PathVariable String name, @PathVariable String key,
                            @RequestParam(required = false) UUID id,
                            @RequestBody(required = false) Map<String, Object> body, Principal principal) {
        Class<?> entity = resolveAndAuthorize(kind, name, principal);
        Action action = actions.find(entity, key);
        if (action == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown action: " + key);
        }
        if (!action.isServer()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Action is navigation-only: " + key);
        }
        ActionContext ctx = new ActionContext(kind, name, id,
                principal != null ? principal.getName() : null, inputValues(body));
        ActionResult result = action.handler().apply(ctx);
        return result != null ? result : ActionResult.ok();
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
