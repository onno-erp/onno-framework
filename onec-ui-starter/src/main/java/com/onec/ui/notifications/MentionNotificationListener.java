package com.onec.ui.notifications;

import com.onec.ui.comments.Comment;
import com.onec.ui.comments.EntityMentionedEvent;
import com.onec.ui.comments.MentionRef;
import com.onec.ui.comments.Mentions;

import org.springframework.context.event.EventListener;

/**
 * Turns a comment {@code @}-mention into an inbox notification. The comments feature publishes an
 * {@link EntityMentionedEvent} for every readable mention in a freshly posted comment (the decoupled
 * "after-mention" hook it deliberately ships without consumers); this is one such consumer.
 *
 * <p>The mention carries the mentioned record's {@code (kind, name, id)} triple, so the notification is
 * addressed to {@code record:<id>} — whoever's account links to that catalog record picks it up,
 * with no reverse username lookup. The deep-link points at the entity whose thread the mention appears
 * in, so clicking the notification opens the conversation. Gated by {@code onec.notifications.mentions
 * .enabled}.
 */
public class MentionNotificationListener {

    private final NotificationService notifications;
    private final NotificationProperties properties;

    public MentionNotificationListener(NotificationService notifications, NotificationProperties properties) {
        this.notifications = notifications;
        this.properties = properties;
    }

    @EventListener
    public void onMention(EntityMentionedEvent event) {
        if (!properties.getMentions().isEnabled()) {
            return;
        }
        MentionRef mentioned = event.mention();
        Comment comment = event.comment();
        String actor = event.actor() == null ? "Someone" : event.actor().displayName();

        notifications.compose()
                .toRecord(mentioned.id().toString())
                .title(actor + " mentioned you")
                .body(snippet(comment.body()))
                .severity(NotificationSeverity.INFO)
                .category("mention")
                // The thread hangs off the commented entity; deep-link there so the mention is in context.
                .link("onec://" + comment.entityType() + "/" + comment.entityName() + "/" + comment.entityId())
                .send();
    }

    /** A readable preview of the comment: mention tokens flattened to their labels, then truncated. */
    private static String snippet(String body) {
        if (body == null) {
            return null;
        }
        String plain = Mentions.degrade(body, ref -> true).strip();
        return plain.length() <= 140 ? plain : plain.substring(0, 139) + "…";
    }
}
