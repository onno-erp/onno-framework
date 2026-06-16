package com.onec.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Declares custom action buttons for an entity, from {@link EntityView#actions(ActionSpec)}.
 *
 * <p>Each action is a labelled, icon'd button placed on the list (toolbar or per-row) or the
 * record detail. It does one of two things when clicked:</p>
 * <ul>
 *   <li>a <b>server handler</b> — {@code .handler(ctx -> ...)} runs arbitrary backend logic and
 *       returns an {@link ActionResult} (toast / refresh / navigate); or</li>
 *   <li>a <b>navigation</b> — {@code .navigate("onec://...")} just routes the client (a
 *       {@code {id}} placeholder is filled with the row/record id).</li>
 * </ul>
 *
 * <pre>
 * public void actions(ActionSpec a) {
 *     a.action("archive").label("Archive").icon("archive").scope(ActionScope.ROW)
 *      .handler(ctx -> { repo.archive(ctx.id()); return ActionResult.refresh("Archived"); });
 *     a.action("report").label("Open report").icon("file-text").scope(ActionScope.TOOLBAR)
 *      .navigate("onec://reports/occupancy");
 * }
 * </pre>
 */
public final class ActionSpec {

    private final List<ActionBuilder> builders = new ArrayList<>();

    /** Start declaring an action with the given unique key. */
    public ActionBuilder action(String key) {
        ActionBuilder b = new ActionBuilder(key);
        builders.add(b);
        return b;
    }

    public List<Action> actions() {
        return builders.stream().map(ActionBuilder::build).toList();
    }

    /**
     * A resolved action button. Exactly one of {@code navigateUrl} / {@code handler} is set.
     *
     * <p>{@code logo} is an optional image URL/path shown in place of the lucide {@code icon} — use it
     * for brand marks ("Connect with X"). Rendered on page-action and list/row/toolbar buttons.</p>
     */
    public record Action(String key, String label, String icon, String logo, ActionScope scope,
                         String navigateUrl, Function<ActionContext, ActionResult> handler) {
        public boolean isServer() {
            return handler != null;
        }
    }

    /** Fluent builder for one action; setters may be called in any order. */
    public static final class ActionBuilder {
        private final String key;
        private String label;
        private String icon = "";
        private String logo = "";
        private ActionScope scope = ActionScope.ROW;
        private String navigateUrl;
        private Function<ActionContext, ActionResult> handler;

        ActionBuilder(String key) {
            this.key = key;
        }

        public ActionBuilder label(String label) {
            this.label = label;
            return this;
        }

        /** A kebab-case lucide icon name (e.g. {@code "archive"}, {@code "download"}). */
        public ActionBuilder icon(String icon) {
            this.icon = icon;
            return this;
        }

        /**
         * An image URL or app-static path shown instead of the lucide {@link #icon(String)} — e.g. a
         * brand logo for a "Connect with X" button ({@code .logo("https://cdn/github.svg")}).
         */
        public ActionBuilder logo(String logo) {
            this.logo = logo;
            return this;
        }

        public ActionBuilder scope(ActionScope scope) {
            this.scope = scope;
            return this;
        }

        /** Run arbitrary server logic when clicked. */
        public ActionBuilder handler(Function<ActionContext, ActionResult> handler) {
            this.handler = handler;
            return this;
        }

        /** Route the client to {@code url} when clicked ({@code {id}} is filled with the record id). */
        public ActionBuilder navigate(String url) {
            this.navigateUrl = url;
            return this;
        }

        Action build() {
            return new Action(key, label != null ? label : key, icon, logo, scope, navigateUrl, handler);
        }
    }
}
