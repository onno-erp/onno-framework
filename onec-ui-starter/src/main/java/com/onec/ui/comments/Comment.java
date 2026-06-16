package com.onec.ui.comments;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One stored comment in the {@code onec_comments} thread of a single entity. {@code entityType}
 * is the route kind ({@code "catalogs"}/{@code "documents"}), {@code entityName} the logical name
 * ({@code "Properties"}), and {@code entityId} the target record — together they scope a thread.
 * {@code authorId} is the commenter's catalog record id when the login links to one (see
 * {@link com.onec.ui.CurrentUserResolver}); it is null for an unlinked principal, in which case
 * only {@code authorName} carries identity. {@code editedAt} is null until the comment is edited.
 */
public record Comment(
        UUID id,
        String entityType,
        String entityName,
        UUID entityId,
        String authorId,
        String authorName,
        String body,
        LocalDateTime createdAt,
        LocalDateTime editedAt) {
}
