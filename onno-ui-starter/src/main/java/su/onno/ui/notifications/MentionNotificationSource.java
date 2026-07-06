package su.onno.ui.notifications;

import su.onno.metadata.MetadataRegistry;
import su.onno.ui.CurrentUserResolver.CurrentUser;
import su.onno.ui.UiLayout;
import su.onno.ui.comments.Comment;
import su.onno.ui.comments.EntityMentionedEvent;
import su.onno.ui.comments.MentionRef;
import su.onno.ui.comments.Mentions;

import org.springframework.context.event.EventListener;

/**
 * Built-in notification producer for comment {@code @}-mentions. It listens for the framework's
 * {@link EntityMentionedEvent} — published once per readable mention in a freshly posted comment, with
 * no consumer of its own — and turns a mention <em>of a user</em> into a notification for that user.
 *
 * <p>Only mentions that point at the identity catalog (the one {@code Layout.identity(...)} links a
 * login to) become notifications: mentioning a customer or a document is a navigable reference, but
 * only a mentioned <em>person</em> has someone to notify. A self-mention notifies nobody. Gated by
 * {@code onno.notifications.mentions.enabled} (default true).
 */
public class MentionNotificationSource {

    /** Longest comment excerpt carried in the notification body. */
    private static final int EXCERPT_LIMIT = 160;

    private final NotificationService notifications;
    private final UiLayout layout;
    private final MetadataRegistry registry;

    public MentionNotificationSource(NotificationService notifications, UiLayout layout, MetadataRegistry registry) {
        this.notifications = notifications;
        this.layout = layout;
        this.registry = registry;
    }

    @EventListener
    public void onMention(EntityMentionedEvent event) {
        MentionRef mention = event.mention();
        // A user is a catalog record in the identity catalog; anything else mentioned has no inbox.
        if (!"catalogs".equals(mention.kind())) {
            return;
        }
        String identityRoute = NotificationIdentity.routeName(layout, registry);
        if (identityRoute == null || !identityRoute.equals(mention.name())) {
            return;
        }
        String recipientId = mention.id().toString();
        CurrentUser actor = event.actor();
        if (actor != null && recipientId.equals(actor.recordId())) {
            return; // don't notify someone for mentioning themselves
        }
        Comment comment = event.comment();
        String actorName = actor == null || actor.displayName() == null ? "Someone" : actor.displayName();
        notifications.notify(NotificationRequest.to(recipientId)
                .type("mention")
                .title(actorName + " mentioned you")
                .body(excerpt(comment.body()))
                // The comment's own (kind, name, id) triple is the route the notification opens — the
                // record whose thread the mention lives in.
                .link(comment.entityType() + "/" + comment.entityName() + "/" + comment.entityId())
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
