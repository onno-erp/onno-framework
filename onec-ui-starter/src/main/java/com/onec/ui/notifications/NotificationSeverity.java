package com.onec.ui.notifications;

import java.util.Locale;

/**
 * Severity of an inbox {@link Notification}, surfaced to the bell widget as a colour/icon hint. It is
 * deliberately a small, fixed vocabulary — the frontend maps each value to a tone, so adding a level
 * means teaching the widget about it. {@link #INFO} is the neutral default for anything unclassified.
 */
public enum NotificationSeverity {
    INFO,
    SUCCESS,
    WARNING,
    ERROR;

    /** Lenient parse: unknown/blank text falls back to {@link #INFO} rather than throwing. */
    public static NotificationSeverity fromString(String value) {
        if (value == null || value.isBlank()) {
            return INFO;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return INFO;
        }
    }
}
