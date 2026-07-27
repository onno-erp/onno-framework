package su.onno.ui.comments;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the {@code /api/comments} discussion-thread feature, under the
 * {@code onno.comments.*} namespace. Comments are a generic collaboration surface: any catalog or
 * document detail page gets a thread of authored, timestamped notes, stored in the framework-owned
 * {@code onno_comments} table rather than in any app-modelled entity.
 */
@ConfigurationProperties(prefix = "onno.comments")
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

    /** {@code @}-mention settings (the {@code onno.comments.mentions.*} sub-namespace). */
    private final Mentions mentions = new Mentions();

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

    public Mentions getMentions() {
        return mentions;
    }

    /**
     * Settings for {@code @}-mentions in comment bodies — letting a comment reference any catalog or
     * document (users included) the author can read. Stored as a token in the body and resolved live,
     * so mentions inherit the same per-entity read gate as everything else.
     */
    public static class Mentions {

        /**
         * Whether {@code @}-mentions are parsed, resolved and offered in the compose typeahead. Turn
         * it off to keep plain-text comments without touching {@code onno.comments.enabled}; existing
         * mention tokens then degrade to their plain label text.
         */
        private boolean enabled = true;

        /**
         * Largest number of suggestions a single {@code /api/mentions} typeahead response returns
         * across all readable entities. Defaults to 8.
         */
        private int suggestionLimit = 8;

        /**
         * Largest number of matches pulled from any one entity before the suggestions are merged and
         * ranked, bounding the per-keystroke scan. Defaults to 5.
         */
        private int perEntityLimit = 5;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getSuggestionLimit() {
            return suggestionLimit;
        }

        public void setSuggestionLimit(int suggestionLimit) {
            this.suggestionLimit = suggestionLimit;
        }

        public int getPerEntityLimit() {
            return perEntityLimit;
        }

        public void setPerEntityLimit(int perEntityLimit) {
            this.perEntityLimit = perEntityLimit;
        }
    }
}
