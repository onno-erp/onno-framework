package su.onno.ui.comments;

import su.onno.ui.CurrentUserResolver.CurrentUser;

/**
 * Published once for every readable entity mention in a freshly posted comment — the mention
 * counterpart of {@link su.onno.posting.DocumentPostedEvent}. It deliberately ships with <em>no
 * consumers</em>: delivery (in-app notifications, the cross-node event bus, {@code onno-mail-starter})
 * is purely additive and can be added later by registering a Spring {@code @EventListener}, the same
 * decoupling the framework prescribes for posting:
 *
 * <pre>{@code
 * @EventListener
 * void onMention(EntityMentionedEvent event) {
 *     // notify whoever owns event.mention(), raised by event.actor() on event.comment()
 * }
 * }</pre>
 *
 * <p>{@code comment} is the stored comment the mention appears in, {@code mention} the resolved
 * {@link MentionRef} (already filtered to ones the author can read), and {@code actor} the commenter.
 */
public record EntityMentionedEvent(Comment comment, MentionRef mention, CurrentUser actor) {
}
