package com.onec.ui.notifications;

import java.util.Locale;

/**
 * Builds the opaque {@code recipient} keys a {@link Notification} is addressed to. A user sees a
 * notification when its recipient is any of the keys their session resolves to (see
 * {@link NotificationService#recipientsFor}):
 *
 * <ul>
 *   <li>{@code user:<username>} — a named login (the {@link java.security.Principal} name).</li>
 *   <li>{@code role:<ROLE>} — everyone holding that role; the role is normalised exactly as
 *       {@link com.onec.ui.UiAccessService} normalises authorities (upper-cased, {@code ROLE_}
 *       prefix stripped) so the send side and the resolve side always agree.</li>
 *   <li>{@code record:<uuid>} — whoever's account links (via the layout's identity link) to that
 *       catalog record. This is what an {@code @}-mention targets: the mention carries the mentioned
 *       record's id, and the matching user picks it up without any reverse username lookup.</li>
 * </ul>
 *
 * Keeping the recipient an opaque string (rather than three typed columns) lets a single
 * {@code WHERE recipient IN (...)} query serve the inbox regardless of how the notification was
 * addressed.
 */
public final class NotificationRecipients {

    public static final String USER_PREFIX = "user:";
    public static final String ROLE_PREFIX = "role:";
    public static final String RECORD_PREFIX = "record:";

    private NotificationRecipients() {
    }

    /** Key for a single named user (the {@code Principal} name). */
    public static String user(String username) {
        return USER_PREFIX + username;
    }

    /** Key for everyone holding {@code role}, normalised to match {@code UiAccessService}. */
    public static String role(String role) {
        return ROLE_PREFIX + normalizeRole(role);
    }

    /** Key for whoever's identity links to the catalog record {@code recordId}. */
    public static String record(String recordId) {
        return RECORD_PREFIX + recordId;
    }

    /** Upper-case and strip a leading {@code ROLE_}, exactly as {@code UiAccessService} does. */
    static String normalizeRole(String role) {
        String normalized = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("ROLE_") ? normalized.substring("ROLE_".length()) : normalized;
    }
}
