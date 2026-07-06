package su.onno.ui.notifications;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the {@code /api/notifications} per-user notification timeline, under the
 * {@code onno.notifications.*} namespace. The notification panel is a generic collaboration surface:
 * every signed-in user gets a top-right bell with an unread badge and a timeline of updates concerning
 * them, stored in the framework-owned {@code onno_notifications} table rather than in any app entity.
 *
 * <p>The two built-in producers each have their own toggle; an app adds further sources simply by
 * registering a Spring {@code @EventListener} that calls {@link NotificationService#notify}.
 */
@ConfigurationProperties(prefix = "onno.notifications")
public class NotificationProperties {

    /**
     * Whether the notifications endpoint, its storage table, the built-in producers, and the shell's
     * bell + timeline panel are wired at all. Turn it off to drop the feature entirely without touching
     * the model.
     */
    private boolean enabled = true;

    /**
     * Rows fetched per timeline window (the bell panel scrolls these keyset windows). Defaults to 30.
     */
    private int pageSize = 30;

    /**
     * How many days a <em>read</em> notification is kept before the daily retention sweep deletes it.
     * Unread notifications are never pruned. {@code 0} disables pruning (keep read history forever).
     * Defaults to 90.
     */
    private int retentionDays = 90;

    /** Built-in comment-{@code @}-mention producer ({@code onno.notifications.mentions.*}). */
    private final Mentions mentions = new Mentions();

    /** Built-in record-assignment producer ({@code onno.notifications.assignments.*}). */
    private final Assignments assignments = new Assignments();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public Mentions getMentions() {
        return mentions;
    }

    public Assignments getAssignments() {
        return assignments;
    }

    /**
     * The mention producer: turns each readable {@code @}-mention of a user in a freshly posted comment
     * (an {@link su.onno.ui.comments.EntityMentionedEvent}) into a notification for the mentioned person.
     */
    public static class Mentions {

        /** Whether comment {@code @}-mentions of a user raise a notification. */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * The assignment producer: when a catalog/document {@code Ref<>} attribute annotated
     * {@link AssigneeField} is set to (or changed to) a user, that user is notified they were assigned
     * the record.
     */
    public static class Assignments {

        /** Whether writing an {@link AssigneeField} pointing at a user raises a notification. */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
