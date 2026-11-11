package su.onno.ui.notifications;

import su.onno.access.AccessSubject;
import su.onno.ui.CurrentUserResolver;
import su.onno.ui.CurrentUserResolver.CurrentUser;
import su.onno.ui.RecordAccess;
import su.onno.ui.RecordRoute;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

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
    private final RecordAccess recordAccess;

    public NotificationController(NotificationService notifications, CurrentUserResolver currentUser) {
        this(notifications, currentUser, null);
    }

    public NotificationController(NotificationService notifications, CurrentUserResolver currentUser,
                                  RecordAccess recordAccess) {
        this.notifications = notifications;
        this.currentUser = currentUser;
        this.recordAccess = recordAccess;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false, defaultValue = "false") boolean unread,
                                    @RequestParam(required = false) String cursor,
                                    Principal principal, AccessSubject subject) {
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
        // A notification about a record the viewer can no longer read (outside its record scope) is
        // withheld — its title snapshots the record's label.
        out.put("items", visible(subject, page.items(), Notification::link).stream()
                .map(NotificationController::toJson).toList());
        out.put("nextCursor", page.nextCursor());
        out.put("hasMore", page.hasMore());
        out.put("unreadCount", unreadCount(recipient, subject));
        // The distinct types this user has (independent of the unread filter) — the panel renders one
        // filter tab per type, so the tab set is modular with no config (see NotificationStore#distinctTypes).
        out.put("types", notifications.types(recipient));
        return out;
    }

    @PostMapping("/{id}/read")
    public Map<String, Object> markRead(@PathVariable UUID id, Principal principal, AccessSubject subject) {
        String recipient = recipient(principal);
        if (recipient == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        notifications.markRead(id, recipient);
        return Map.of("unreadCount", unreadCount(recipient, subject));
    }

    @PostMapping("/read-all")
    public Map<String, Object> markAllRead(Principal principal, AccessSubject subject) {
        String recipient = recipient(principal);
        if (recipient == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        int marked = notifications.markAllRead(recipient);
        return Map.of("marked", marked, "unreadCount", unreadCount(recipient, subject));
    }

    /**
     * The badge count: every unread notification, except — for a viewer some record policy narrows —
     * those the feed withholds, so the badge never counts what the panel cannot show.
     */
    private int unreadCount(String recipient, AccessSubject subject) {
        if (recordAccess == null || !recordAccess.narrowsReads(subject)) {
            return notifications.unreadCount(recipient);
        }
        return visible(subject, notifications.unreadLinks(recipient), link -> link).size();
    }

    /** The {@code items} whose link the viewer may read (record scope; one query per linked entity). */
    private <T> List<T> visible(AccessSubject subject, List<T> items, Function<T, String> link) {
        if (recordAccess == null || items.isEmpty()) return items;
        List<Optional<RecordRoute>> routes = items.stream().map(i -> RecordRoute.parse(link.apply(i))).toList();
        Set<RecordRoute> readable = recordAccess.readable(subject, routes.stream().flatMap(Optional::stream).toList());
        List<T> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (routes.get(i).map(readable::contains).orElse(true)) out.add(items.get(i));
        }
        return out;
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
