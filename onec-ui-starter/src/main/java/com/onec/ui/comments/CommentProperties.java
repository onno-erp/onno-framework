package com.onec.ui.comments;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the {@code /api/comments} discussion-thread feature, under the
 * {@code onec.comments.*} namespace. Comments are a generic collaboration surface: any catalog or
 * document detail page gets a thread of authored, timestamped notes, stored in the framework-owned
 * {@code onec_comments} table rather than in any app-modelled entity.
 */
@ConfigurationProperties(prefix = "onec.comments")
public class CommentProperties {

    /**
     * Whether the comments endpoint, its storage table, and the detail-page comments panel are wired
     * at all. Turn it off to drop the feature from every entity without touching the model.
     */
    private boolean enabled = true;

    /**
     * Largest comment body accepted, in characters. The server rejects a longer body with 422; the
     * compose box mirrors the limit client-side. Defaults to 4000.
     */
    private int maxLength = 4000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(int maxLength) {
        this.maxLength = maxLength;
    }
}
