package su.onno.ui.comments;

import java.time.Instant;
import java.util.UUID;

/**
 * One stored comment in the {@code onno_comments} thread of a single entity. {@code entityType}
 * is the route kind ({@code "catalogs"}/{@code "documents"}), {@code entityName} the logical name
 * ({@code "Properties"}), and {@code entityId} the target record — together they scope a thread.
 * {@code authorId} is the commenter's catalog record id when the login links to one (see
 * {@link su.onno.ui.CurrentUserResolver}); it is null for an unlinked principal, in which case
 * only {@code authorName} carries identity. {@code editedAt} is null until the comment is edited.
 *
 * <p>{@code createdAt}/{@code editedAt} are {@link Instant}s (points on the timeline, not wall
 * clocks), so the API serializes them zone-qualified ({@code "…Z"}) and any client can localize
 * them. They were once zoneless {@code LocalDateTime}s, which clients in a non-UTC zone mis-rendered
 * by their UTC offset (#177).
 */
public record Comment(
        UUID id,
        String entityType,
        String entityName,
        UUID entityId,
        String authorId,
        String authorName,
        String body,
        UUID parentId,
        Instant createdAt,
        Instant editedAt) {
}
