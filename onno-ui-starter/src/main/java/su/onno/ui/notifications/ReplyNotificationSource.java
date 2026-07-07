package su.onno.ui.notifications;

import su.onno.ui.CurrentUserResolver.CurrentUser;
import su.onno.ui.comments.Comment;
import su.onno.ui.comments.CommentRepliedEvent;
import su.onno.ui.comments.Mentions;

import org.springframework.context.event.EventListener;

/**
 * Built-in notification producer for comment replies. It listens for the framework's
 * {@link CommentRepliedEvent} — published once per reply, with no consumer of its own — and notifies
 * the parent comment's author that someone answered them.
 *
 * <p>Nobody is notified when the parent has no linked author (an unlinked principal only carries a
 * display name — there is no inbox to deliver to), when someone replies to themselves, or when the
 * reply also {@code @}-mentions the parent's author — the mention producer already notifies them,
 * and one reply should not ring twice. Gated by {@code onno.notifications.replies.enabled}
 * (default true).
 */
public class ReplyNotificationSource {

    /** Longest reply excerpt carried in the notification body (mirrors the mention producer). */
    private static final int EXCERPT_LIMIT = 160;

    private final NotificationService notifications;

    public ReplyNotificationSource(NotificationService notifications) {
        this.notifications = notifications;
    }

    @EventListener
    public void onReply(CommentRepliedEvent event) {
        String recipientId = event.parent().authorId();
        if (recipientId == null) {
            return; // unlinked author — no inbox to notify
        }
        CurrentUser actor = event.actor();
        if (actor != null && recipientId.equals(actor.recordId())) {
            return; // replying to your own comment notifies nobody
        }
        Comment reply = event.reply();
        // Deduplicate against the mention producer: a reply whose body @-mentions the parent's
        // author already raises a "mentioned you" notification for the same person.
        boolean mentionsRecipient = Mentions.parse(reply.body(), '@').stream()
                .anyMatch(ref -> "catalogs".equals(ref.kind()) && recipientId.equals(ref.id().toString()));
        if (mentionsRecipient) {
            return;
        }
        String actorName = actor == null || actor.displayName() == null ? "Someone" : actor.displayName();
        notifications.notify(NotificationRequest.to(recipientId)
                .type("reply")
                .title(actorName + " replied to your comment")
                .body(excerpt(reply.body()))
                // The reply's own (kind, name, id) triple is the route the notification opens — the
                // record whose thread the conversation lives in.
                .link(reply.entityType() + "/" + reply.entityName() + "/" + reply.entityId())
                .actor(actor)
                .build());
    }

    /** Plain-text, length-bounded excerpt of a comment body — mention tokens degraded to their labels. */
    private static String excerpt(String body) {
        if (body == null) {
            return null;
        }
        String plain = Mentions.degrade(body, ref -> true).strip();
        if (plain.length() <= EXCERPT_LIMIT) {
            return plain;
        }
        return plain.substring(0, EXCERPT_LIMIT).strip() + "…";
    }
}
