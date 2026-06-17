package com.onec.ui.notifications;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row of a user's notification inbox, as stored in {@code onec_notifications}. A notification is
 * addressed to a {@code recipient} key (see {@link NotificationRecipients}) rather than to a single
 * user id, so the same record can fan out to whoever holds that key — a named user, everyone in a
 * role, or whoever's identity links to a given catalog record (the target of an {@code @}-mention).
 *
 * <p>{@code link} is an optional {@code onec://kind/name/id} route the bell makes clickable so the
 * notification can deep-link to the record it is about; {@code category} is a free-form tag the
 * producer sets (e.g. {@code "mention"}, {@code "posting"}) for grouping/filtering.
 */
public record Notification(
        UUID id,
        String recipient,
        String title,
        String body,
        NotificationSeverity severity,
        String category,
        String link,
        LocalDateTime createdAt,
        boolean read,
        LocalDateTime readAt) {
}
