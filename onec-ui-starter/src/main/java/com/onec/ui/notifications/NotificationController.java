package com.onec.ui.notifications;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The per-user notification inbox endpoint. Every operation is scoped to the authenticated principal
 * by {@link NotificationService} — the caller can only read, mark, or dismiss notifications addressed
 * to them, and never asserts an inbox identity from the client. There is no "create" endpoint:
 * notifications are produced server-side (through {@link NotificationService} or the built-in mention
 * / posting bridges), never posted by a browser.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    /** The caller's inbox, newest first. {@code ?unread=true} returns only the unread ones. */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(name = "unread", defaultValue = "false") boolean unreadOnly,
                                          Principal principal) {
        return service.inbox(principal, unreadOnly).stream().map(NotificationController::toJson).toList();
    }

    /** The caller's unread count — what the bell badge shows. */
    @GetMapping("/unread-count")
    public Map<String, Object> unreadCount(Principal principal) {
        return Map.of("count", service.unreadCount(principal));
    }

    /** Mark one notification read. 204 when it changed, 404 when it isn't the caller's / already read. */
    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable UUID id, Principal principal) {
        return service.markRead(principal, id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /** Mark every unread notification in the caller's inbox read. Returns how many changed. */
    @PostMapping("/read-all")
    public Map<String, Object> markAllRead(Principal principal) {
        return Map.of("updated", service.markAllRead(principal));
    }

    /** Remove one notification. 204 when it was the caller's, 404 otherwise. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> dismiss(@PathVariable UUID id, Principal principal) {
        return service.dismiss(principal, id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /** Response shape — the recipient key is internal and deliberately omitted. */
    private static Map<String, Object> toJson(Notification n) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", n.id().toString());
        out.put("title", n.title());
        out.put("body", n.body());
        out.put("severity", n.severity().name().toLowerCase());
        out.put("category", n.category());
        out.put("link", n.link());
        out.put("createdAt", n.createdAt());
        out.put("read", n.read());
        out.put("readAt", n.readAt());
        return out;
    }
}
