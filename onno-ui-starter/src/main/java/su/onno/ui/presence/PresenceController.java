package su.onno.ui.presence;

import su.onno.cluster.ClusterEvent;
import su.onno.ui.CurrentUserResolver;
import su.onno.ui.CurrentUserResolver.CurrentUser;
import su.onno.ui.UiAccessService;
import su.onno.ui.comments.CommentAuthorAvatars;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Route-level presence: tracks which signed-in users are currently on each route — a record, an entity
 * list, or any other page (dashboards, custom pages) — so the UI can show collaboration markers ("Ada
 * and Babbage are also here") on the open tab, list rows, and the sidebar nav.
 *
 * <p>The client posts the pane's route {@code path}; the server derives the presence identity from it. A
 * {@code catalogs}/{@code documents} route is gated on the owning entity's <em>read</em> access (so you
 * only register on, and learn about, records/lists you may read), while any other route is a {@code page}
 * visible to any signed-in user. Identity is stamped from the authenticated principal via
 * {@link CurrentUserResolver}, so the client never asserts who it is. The client posts {@code enter} on
 * open, {@code heartbeat} periodically, and {@code leave} on close; the response returns the route's
 * current viewers plus {@code you}, the caller's own id, so the client can omit itself.
 */
@RestController
@RequestMapping("/api/presence")
public class PresenceController {

    private static final Set<String> ACTIONS = Set.of(
            ClusterEvent.Presence.ENTER, ClusterEvent.Presence.HEARTBEAT, ClusterEvent.Presence.LEAVE);

    private final PresenceRegistry registry;
    private final UiAccessService access;
    private final CurrentUserResolver currentUser;
    private final CommentAuthorAvatars authorAvatars;

    public PresenceController(PresenceRegistry registry, UiAccessService access, CurrentUserResolver currentUser,
                              CommentAuthorAvatars authorAvatars) {
        this.registry = registry;
        this.access = access;
        this.currentUser = currentUser;
        this.authorAvatars = authorAvatars;
    }

    /** The heartbeat body: the pane's route {@code path} and an {@code action} (enter/heartbeat/leave). */
    public record PresenceRequest(String path, String action) {}

    @PostMapping
    public Map<String, Object> ping(@RequestBody(required = false) PresenceRequest request, Principal principal) {
        String action = request == null ? null : request.action();
        if (action == null || !ACTIONS.contains(action)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "action must be one of enter, heartbeat, leave");
        }
        RouteId route = routeIdentity(request.path());
        // Entity routes (catalogs/documents) are gated on read access to the owning entity; any other
        // route is a page, registrable by any signed-in user.
        if (route.entity() && !access.canRead(principal, route.type(), route.name())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Current user is not allowed to read " + route.type() + ": " + route.name());
        }
        CurrentUser me = currentUser.resolve(principal);
        String userId = me.recordId() != null ? me.recordId() : me.username();
        if (userId == null) {
            // A guest with no stable identity has nothing to mark presence with.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Presence requires a signed-in user");
        }
        // Resolve the viewer's avatar the same way the comments panel does — from the identity catalog's
        // avatar/image-hinted column, keyed by their record id (null when unlinked → marker uses initials).
        String avatarUrl = authorAvatars.avatarFor(me.recordId());
        registry.onLocal(action, route.kind(), route.name(), route.id(), userId, me.displayName(), avatarUrl);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("you", userId);
        response.put("viewers", registry.viewers(route.kind(), route.name(), route.id()));
        return response;
    }

    /**
     * The ambient-presence snapshot: every route currently being viewed, as {@code {kind, name, id,
     * viewers}}, plus the caller's own id ({@code you}). The client loads this once, then keeps it current
     * from the live {@code presence} SSE deltas. Entity routes are filtered to those the caller may read
     * (you never learn that someone is viewing a record/list in an entity you can't open); page routes are
     * visible to any signed-in user.
     */
    @GetMapping
    public Map<String, Object> snapshot(Principal principal) {
        CurrentUser me = currentUser.resolve(principal);
        String you = me.recordId() != null ? me.recordId() : me.username();
        Map<String, Boolean> readable = new HashMap<>();
        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<String, Object> rec : registry.allViewers()) {
            String kind = (String) rec.get("kind");
            String name = (String) rec.get("name");
            String type = switch (kind == null ? "" : kind) {
                case "catalogs" -> "catalog";
                case "documents" -> "document";
                case "page" -> "page";
                default -> null;
            };
            if (type == null) {
                continue;
            }
            Boolean ok = readable.get(type + ":" + name);
            if (ok == null) {
                ok = "page".equals(type) || access.canRead(principal, type, name);
                readable.put(type + ":" + name, ok);
            }
            if (ok) {
                records.add(rec);
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("you", you == null ? "" : you);
        response.put("records", records);
        return response;
    }

    /**
     * The presence identity of a route path. {@code kind} is {@code catalogs}/{@code documents} for an
     * entity route or {@code page} otherwise; {@code name} is the entity name (for nav aggregation + the
     * read gate) or the normalized path for a page; {@code id} is the record id for a record route, or the
     * path itself so each list/page keys uniquely in the by-id store. {@code entity()} marks the routes the
     * read gate applies to.
     */
    private record RouteId(String kind, String name, String id, boolean entity) {
        String type() {
            return "catalogs".equals(kind) ? "catalog" : "document";
        }
    }

    private static RouteId routeIdentity(String path) {
        String norm = normalizePath(path);
        List<String> parts = new ArrayList<>();
        for (String s : norm.split("/")) {
            if (!s.isBlank()) parts.add(s);
        }
        if (parts.size() >= 2 && ("catalogs".equals(parts.get(0)) || "documents".equals(parts.get(0)))) {
            String kind = parts.get(0);
            String name = parts.get(1);
            if (parts.size() >= 3) {
                return new RouteId(kind, name, parts.get(2), true);   // record — id is the record id
            }
            return new RouteId(kind, name, norm, true);               // entity list — id is the path
        }
        return new RouteId("page", norm, norm, false);                // any other route is a page
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String p = path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
