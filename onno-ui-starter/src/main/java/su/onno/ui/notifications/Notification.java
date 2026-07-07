package su.onno.ui.notifications;

import java.time.Instant;
import java.util.UUID;

/**
 * One durable, per-user notification: a timeline entry telling a person that something concerning them
 * happened — they were {@code @}-mentioned in a comment, a document was assigned to them, and so on.
 *
 * <p>It is framework infrastructure, not an app-modelled entity: rows live in the framework-owned
 * {@code onno_notifications} table (see {@link NotificationStore}), never in the metadata model, and
 * never surface in the nav or the generic REST catalog API — only through {@code /api/notifications}.
 *
 * @param id           the notification's own id.
 * @param recipientId  the target user's identity record id (the {@code recordId} of their
 *                     {@link su.onno.ui.CurrentUserResolver.CurrentUser}), or their username for an
 *                     unlinked in-memory login. This is what the delivery layer routes on.
 * @param type         a short machine tag for the source ({@code mention}, {@code assignment}, …); the
 *                     UI maps it to an icon and the producers set it. Never null.
 * @param title        the short headline shown in the timeline row.
 * @param body         optional longer text (the comment excerpt, the document number), or {@code null}.
 * @param link         the {@code kind/name/id} route the row opens when clicked (the body of an
 *                     {@code onno://} navigation url), or {@code null} for a non-navigating notice.
 * @param actorId      the identity record id of whoever triggered it, or {@code null} (system-raised).
 * @param actorName    the actor's display name, for the row's "{@code X assigned you …}" line.
 * @param actorAvatar  the actor's avatar image URL, or {@code null} (the row falls back to initials).
 * @param createdAt    when it was raised.
 * @param readAt       when the recipient marked it read, or {@code null} while it is still unread.
 */
public record Notification(
        UUID id,
        String recipientId,
        String type,
        String title,
        String body,
        String link,
        String actorId,
        String actorName,
        String actorAvatar,
        Instant createdAt,
        Instant readAt) {

    /** Whether the recipient has not yet marked this notification read. */
    public boolean unread() {
        return readAt == null;
    }
}
