package su.onno.ui.notifications;

import su.onno.ui.CurrentUserResolver;
import su.onno.ui.CurrentUserResolver.CurrentUser;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The per-user notification endpoint. Every read and mutation is scoped to the caller: the recipient is
 * resolved from the authenticated principal via {@link CurrentUserResolver} (identity record id, or
 * username for an unlinked login) and used as the {@code _recipient} filter, so a user only ever sees or
 * marks their own notifications — the client never asserts whose feed it is.
 *
 * <ul>
 *   <li>{@code GET /api/notifications} — one newest-first timeline window {@code {items, nextCursor,
 *       hasMore, unreadCount}}; {@code ?unread=true} restricts to unread, {@code ?cursor=} resumes.</li>
 *   <li>{@code POST /api/notifications/{id}/read} — mark one read.</li>
 *   <li>{@code POST /api/notifications/read-all} — mark every unread one read.</li>
 * </ul>
 *
 * <p>A guest (no resolvable identity) gets an empty feed and its mutations are no-ops.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notifications;
    private final CurrentUserResolver currentUser;

    public NotificationController(NotificationService notifications, CurrentUserResolver currentUser) {
        this.notifications = notifications;
        this.currentUser = currentUser;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false, defaultValue = "false") boolean unread,
                                    @RequestParam(required = false) String cursor,
                                    Principal principal) {
        String recipient = recipient(principal);
        Map<String, Object> out = new LinkedHashMap<>();
        if (recipient == null) {
            out.put("items", List.of());
            out.put("nextCursor", null);
            out.put("hasMore", false);
            out.put("unreadCount", 0);
            out.put("types", List.of());
            return out;
        }
        NotificationStore.Page page = notifications.list(recipient, unread, cursor);
        out.put("items", page.items().stream().map(NotificationController::toJson).toList());
        out.put("nextCursor", page.nextCursor());
        out.put("hasMore", page.hasMore());
        out.put("unreadCount", notifications.unreadCount(recipient));
        // The distinct types this user has (independent of the unread filter) — the panel renders one
        // filter tab per type, so the tab set is modular with no config (see NotificationStore#distinctTypes).
        out.put("types", notifications.types(recipient));
        return out;
    }

    @PostMapping("/{id}/read")
    public Map<String, Object> markRead(@PathVariable UUID id, Principal principal) {
        String recipient = recipient(principal);
        if (recipient == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        notifications.markRead(id, recipient);
        return Map.of("unreadCount", notifications.unreadCount(recipient));
    }

    @PostMapping("/read-all")
    public Map<String, Object> markAllRead(Principal principal) {
        String recipient = recipient(principal);
        if (recipient == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        int marked = notifications.markAllRead(recipient);
        return Map.of("marked", marked, "unreadCount", notifications.unreadCount(recipient));
    }

    /** The caller's notification-routing id: their identity record id, or username when unlinked, or null for a guest. */
    private String recipient(Principal principal) {
        CurrentUser me = currentUser.resolve(principal);
        if (me.recordId() != null) {
            return me.recordId();
        }
        return me.username(); // unlinked login — still addressable by username, null only for a guest
    }

    private static Map<String, Object> toJson(Notification n) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", n.id().toString());
        out.put("type", n.type());
        out.put("title", n.title());
        out.put("body", n.body());
        out.put("link", n.link());
        out.put("actorName", n.actorName());
        out.put("actorAvatar", n.actorAvatar());
        out.put("createdAt", n.createdAt());
        out.put("readAt", n.readAt());
        out.put("unread", n.unread());
        return out;
    }
}
