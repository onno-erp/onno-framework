package su.onno.ui.comments;

import su.onno.ui.CurrentUserResolver.CurrentUser;

/**
 * Published when a freshly posted comment replies to another comment — the reply counterpart of
 * {@link EntityMentionedEvent}, with the same contract: the framework ships the event with no
 * mandatory consumer; delivery (in-app notifications, mail, a cross-node bus) is purely additive via
 * a Spring {@code @EventListener}. The built-in
 * {@code su.onno.ui.notifications.ReplyNotificationSource} turns it into a notification for the
 * parent comment's author.
 *
 * <p>{@code reply} is the stored reply, {@code parent} the comment it answers (whose author is the
 * natural recipient), and {@code actor} the replier.
 */
public record CommentRepliedEvent(Comment reply, Comment parent, CurrentUser actor) {
}
