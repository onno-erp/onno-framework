package com.onec.ui.notifications;

/**
 * Published (as a Spring {@code ApplicationEvent}) right after a {@link Notification} is stored, so
 * other delivery channels can fan it out beyond the in-app bell without the producer knowing about
 * them — e.g. an {@code @EventListener} in {@code onec-mail-starter} could email the recipient, or a
 * push-notification bridge could forward it. The in-app inbox itself needs no listener: the row is
 * already persisted and the browser is pinged over the existing UI-event stream.
 */
public record NotificationCreatedEvent(Notification notification) {
}
