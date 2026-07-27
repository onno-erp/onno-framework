package su.onno.ui.notifications;

import su.onno.ui.CurrentUserResolver.CurrentUser;

/**
 * A request to raise one notification, built fluently and handed to {@link NotificationService#notify}.
 * This is the framework's public notification API: any app bean produces a notification by building a
 * request and calling {@code notify}, typically from a Spring {@code @EventListener}:
 *
 * <pre>{@code
 * @EventListener
 * void onApproved(PurchaseApprovedEvent e) {
 *     notifications.notify(NotificationRequest.to(e.requesterId())
 *             .type("approval")
 *             .title("Your purchase was approved")
 *             .link("documents/purchase_orders/" + e.id())
 *             .actor(e.approver())
 *             .build());
 * }
 * }</pre>
 *
 * @param recipientId the target user's identity record id (their {@link CurrentUser#recordId()}), or
 *                    username for an unlinked login. Required — a request with none is a no-op.
 * @param type        a short source tag ({@code mention}/{@code assignment}/…); the UI maps it to an
 *                    icon. Defaults to {@code "info"} when not set.
 * @param title       the timeline headline. Required.
 * @param body        optional longer text, or {@code null}.
 * @param link        the {@code kind/name/id} route the row opens, or {@code null}.
 * @param actorId     the triggering user's record id, or {@code null} (system-raised).
 * @param actorName   the triggering user's display name, or {@code null}.
 * @param actorAvatar the triggering user's avatar URL, or {@code null}.
 */
public record NotificationRequest(
        String recipientId,
        String type,
        String title,
        String body,
        String link,
        String actorId,
        String actorName,
        String actorAvatar) {

    /** Start a request addressed to a user by their identity record id (or username when unlinked). */
    public static Builder to(String recipientId) {
        return new Builder(recipientId);
    }

    /** Whether this request is deliverable (has a recipient and a title). */
    public boolean valid() {
        return recipientId != null && !recipientId.isBlank() && title != null && !title.isBlank();
    }

    /** Fluent builder for a {@link NotificationRequest}. */
    public static final class Builder {

        private final String recipientId;
        private String type = "info";
        private String title;
        private String body;
        private String link;
        private String actorId;
        private String actorName;
        private String actorAvatar;

        private Builder(String recipientId) {
            this.recipientId = recipientId;
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder body(String body) {
            this.body = body;
            return this;
        }

        public Builder link(String link) {
            this.link = link;
            return this;
        }

        /** Set the actor id/name/avatar from a resolved {@link CurrentUser} (the triggering user). */
        public Builder actor(CurrentUser actor) {
            if (actor != null) {
                this.actorId = actor.recordId();
                this.actorName = actor.displayName();
                this.actorAvatar = actor.avatarUrl();
            }
            return this;
        }

        public Builder actor(String actorId, String actorName, String actorAvatar) {
            this.actorId = actorId;
            this.actorName = actorName;
            this.actorAvatar = actorAvatar;
            return this;
        }

        public NotificationRequest build() {
            return new NotificationRequest(recipientId, type, title, body, link, actorId, actorName, actorAvatar);
        }
    }
}
