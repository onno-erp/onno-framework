package com.onec.ui.notifications;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the {@code /api/notifications} inbox feature, under the
 * {@code onec.notifications.*} namespace. The inbox is a generic per-user surface: anything in the
 * app can push a notification through {@link NotificationService}, and two built-in producers — comment
 * {@code @}-mentions and document posting — can be switched on here without writing a listener.
 */
@ConfigurationProperties(prefix = "onec.notifications")
public class NotificationProperties {

    /**
     * Whether the notifications endpoint, its storage table, and the bell widget are wired at all.
     * Turn it off to drop the feature entirely without touching application code.
     */
    private boolean enabled = true;

    /**
     * Largest number of notifications a single inbox fetch returns, newest first. The bell only ever
     * shows a recent window; older items age out of view (but stay in the table). Defaults to 50.
     */
    private int inboxLimit = 50;

    /** Whether an {@code @}-mention in a comment raises a notification for the mentioned user. */
    private final Mentions mentions = new Mentions();

    /** Whether posting a document notifies a set of roles. */
    private final Posting posting = new Posting();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getInboxLimit() {
        return inboxLimit;
    }

    public void setInboxLimit(int inboxLimit) {
        this.inboxLimit = inboxLimit;
    }

    public Mentions getMentions() {
        return mentions;
    }

    public Posting getPosting() {
        return posting;
    }

    /**
     * The {@code @}-mention notification bridge. When on, every {@code EntityMentionedEvent} the
     * comments feature publishes (a mention the author could read) turns into a notification addressed
     * to the mentioned record, so whoever's account links to it sees it in their bell. Requires the
     * comments mentions feature ({@code onec.comments.mentions.enabled}) to actually emit the events.
     */
    public static class Mentions {

        /** Whether mentions raise inbox notifications. On by default. */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * The document-posting notification bridge. When on, a successful post notifies everyone holding
     * one of {@link #getRoles()} — useful for "a {@code DocumentPostedEvent} the finance team should
     * see". Off by default, and a no-op until at least one role is configured, since who (if anyone)
     * cares about a post is entirely app-specific.
     */
    public static class Posting {

        /** Whether posting a document raises notifications. Off by default. */
        private boolean enabled = false;

        /** Roles notified when a document is posted, e.g. {@code [FINANCE]}. Empty means notify no one. */
        private List<String> roles = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getRoles() {
            return roles;
        }

        public void setRoles(List<String> roles) {
            this.roles = roles;
        }
    }
}
