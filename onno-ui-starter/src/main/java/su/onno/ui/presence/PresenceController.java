package su.onno.ui.presence;

import su.onno.cluster.ClusterEvent;
import su.onno.ui.CurrentUserResolver;
import su.onno.ui.CurrentUserResolver.CurrentUser;
import su.onno.ui.UiAccessService;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Record-level presence: tracks which signed-in users are currently viewing a record so the UI can show
 * collaboration markers ("Ada and Babbage are also here").
 *
 * <p>Addressed by the same {@code {kind}/{name}/{id}} triple the UI routes and comment threads use, and
 * gated on the same <em>read</em> access — if you can open the record, your presence is tracked on it.
 * Identity is stamped from the authenticated principal via {@link CurrentUserResolver}, so the client
 * never asserts who it is. The client posts {@code enter} on open, {@code heartbeat} periodically, and
 * {@code leave} on close; the response returns the record's current viewers (so a just-opened page shows
 * who is already there) plus {@code you}, the caller's own id, so the client can omit itself.
 */
@RestController
@RequestMapping("/api/presence")
public class PresenceController {

    private static final Set<String> ACTIONS = Set.of(
            ClusterEvent.Presence.ENTER, ClusterEvent.Presence.HEARTBEAT, ClusterEvent.Presence.LEAVE);

    private final PresenceRegistry registry;
    private final UiAccessService access;
    private final CurrentUserResolver currentUser;

    public PresenceController(PresenceRegistry registry, UiAccessService access, CurrentUserResolver currentUser) {
        this.registry = registry;
        this.access = access;
        this.currentUser = currentUser;
    }

    /** The heartbeat body: {@code action} is one of {@code enter} / {@code heartbeat} / {@code leave}. */
    public record PresenceRequest(String action) {}

    @PostMapping("/{kind}/{name}/{id}")
    public Map<String, Object> ping(@PathVariable String kind, @PathVariable String name,
                                    @PathVariable UUID id, @RequestBody(required = false) PresenceRequest request,
                                    Principal principal) {
        String type = requireReadableType(kind, name, principal);
        String action = request == null ? null : request.action();
        if (action == null || !ACTIONS.contains(action)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "action must be one of enter, heartbeat, leave");
        }
        CurrentUser me = currentUser.resolve(principal);
        String userId = me.recordId() != null ? me.recordId() : me.username();
        if (userId == null) {
            // A guest with no stable identity has nothing to mark presence with.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Presence requires a signed-in user");
        }
        registry.onLocal(action, type, name, id.toString(), userId, me.displayName());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("you", userId);
        response.put("viewers", registry.viewers(type, name, id.toString()));
        return response;
    }

    /** Map the route's plural kind to the entity type and require read access, mirroring the comment gate. */
    private String requireReadableType(String kind, String name, Principal principal) {
        String type = switch (kind) {
            case "catalogs" -> "catalog";
            case "documents" -> "document";
            default -> null;
        };
        if (type == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!access.canRead(principal, type, name)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Current user is not allowed to read " + type + ": " + name);
        }
        return type;
    }
}
