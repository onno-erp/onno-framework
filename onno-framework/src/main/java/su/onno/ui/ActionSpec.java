package su.onno.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Declares custom action buttons for an entity, from {@link EntityView#actions(ActionSpec)}.
 *
 * <p>Each action is a labelled, icon'd button placed on the list (toolbar or per-row) or the
 * record detail. It does one of two things when clicked:</p>
 * <ul>
 *   <li>a <b>server handler</b> — {@code .handler(ctx -> ...)} runs arbitrary backend logic and
 *       returns an {@link ActionResult} (toast / refresh / navigate); or</li>
 *   <li>a <b>navigation</b> — {@code .navigate("onno://...")} just routes the client (a
 *       {@code {id}} placeholder is filled with the row/record id).</li>
 * </ul>
 *
 * <pre>
 * public void actions(ActionSpec a) {
 *     a.action("archive").label("Archive").icon("archive").scope(ActionScope.ROW)
 *      .handler(ctx -> { repo.archive(ctx.id()); return ActionResult.refresh("Archived"); });
 *     a.action("report").label("Open report").icon("file-text").scope(ActionScope.TOOLBAR)
 *      .navigate("onno://reports/occupancy");
 * }
 * </pre>
 *
 * <p>A <b>row</b> action's icon, label, visibility and enabled state may be a function of the row
 * instead of fixed, so one control adapts to each record (a {@code pause} "Suspend" on a running
 * row, a {@code play} "Resume" on a stopped one; a button shown only where it applies). Pass a
 * {@link ActionRow}-taking function/predicate; it's evaluated per row as the list renders:</p>
 *
 * <pre>
 * a.action("suspend").scope(ActionScope.ROW)
 *  .icon(row -> row.enumValue("status", Status.class) == Status.STOPPED ? "play" : "pause")
 *  .label(row -> row.enumValue("status", Status.class) == Status.STOPPED ? "Resume" : "Suspend")
 *  .visibleWhen(row -> row.enumValue("status", Status.class) != Status.ARCHIVED)
 *  .handler(ctx -> { service.toggle(ctx.id()); return ActionResult.refresh(); });
 * </pre>
 *
 * <p>The per-row functions apply to {@link ActionScope#ROW} actions only (toolbar/detail buttons
 * have no row context); on other scopes they're ignored in favour of the fixed icon/label.</p>
 *
 * <p>A server action may also declare a <b>form</b> — the click then opens a modal dialog that
 * collects the declared fields before the handler runs; the values arrive as
 * {@link ActionContext#input(String)}. The classic case: a "Cancel" action that asks for a
 * reason:</p>
 *
 * <pre>
 * a.action("cancel").label("Cancel order").icon("ban").scope(ActionScope.DETAIL)
 *  .form(f -> f.input("reason").label("Reason").type(InputType.TEXTAREA)
 *              .placeholder("Why is this order cancelled?").required())
 *  .handler(ctx -> { service.cancel(ctx.id(), ctx.input("reason")); return ActionResult.refresh("Cancelled"); });
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
     * <p>{@code icon}/{@code label} are the fixed values (and the fallback). {@code logo} is an
     * optional image URL/path shown in place of the lucide {@code icon} — a brand mark ("Connect
     * with X"), rendered on page-action and list/row/toolbar buttons. {@code iconFn},
     * {@code labelFn}, {@code visibleFn} and {@code enabledFn} are the optional per-row overrides
     * for a {@link ActionScope#ROW} action — any that are non-null are evaluated against each
     * {@link ActionRow} when the list renders.</p>
     */
    public record Action(String key, String label, String icon, String logo, ActionScope scope,
                         String menu,
                         String navigateUrl, Function<ActionContext, ActionResult> handler,
                         Function<ActionRow, String> iconFn, Function<ActionRow, String> labelFn,
                         Predicate<ActionRow> visibleFn, Predicate<ActionRow> enabledFn,
                         List<InputSpec.InputField> form, List<String> roles) {
        public boolean isServer() {
            return handler != null;
        }

        /** Whether clicking must first collect the declared form fields in a modal dialog. */
        public boolean hasForm() {
            return form != null && !form.isEmpty();
        }

        /** Whether any aspect of this action varies per row (so the list must resolve it per row). */
        public boolean isDynamic() {
            return iconFn != null || labelFn != null || visibleFn != null || enabledFn != null;
        }
    }

    /** Fluent builder for one action; setters may be called in any order. */
    public static final class ActionBuilder {
        private final String key;
        private String label;
        private String icon = "";
        private String logo = "";
        private String menu = "";
        private ActionScope scope = ActionScope.ROW;
        private String navigateUrl;
        private Function<ActionContext, ActionResult> handler;
        private Function<ActionRow, String> iconFn;
        private Function<ActionRow, String> labelFn;
        private Predicate<ActionRow> visibleFn;
        private Predicate<ActionRow> enabledFn;
        private List<InputSpec.InputField> form = List.of();
        private List<String> roles = List.of();

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

        /**
         * Put this {@link ActionScope#ROW} action into the row's right-click context menu under a
         * submenu with the given label, instead of rendering it as an inline row icon button.
         * Actions sharing a label land in the same submenu, in declaration order — e.g. one
         * action per status under {@code .menu("Change status")}. The per-row functions
         * ({@code label(row -> …)}, {@code visibleWhen}, {@code enabledWhen}) still apply to the
         * menu entries. Ignored on non-ROW scopes.
         */
        public ActionBuilder menu(String submenuLabel) {
            this.menu = submenuLabel;
            return this;
        }

        /** Run arbitrary server logic when clicked. */
        public ActionBuilder handler(Function<ActionContext, ActionResult> handler) {
            this.handler = handler;
            return this;
        }

        /**
         * Collect input from the user before the {@link #handler} runs: clicking the button opens a
         * modal dialog with the fields declared here (same builder as toolbar inputs — text,
         * textarea, date, number, select; {@code .required()} gates submit). The submitted values
         * reach the handler as {@link ActionContext#input(String)}. Server actions only —
         * a navigation action has no handler to hand the values to.
         */
        public ActionBuilder form(Consumer<InputSpec> form) {
            InputSpec spec = new InputSpec();
            form.accept(spec);
            this.form = spec.inputs();
            return this;
        }

        /**
         * Restrict this action to callers holding any of the given roles ({@code ADMIN} always
         * passes, like entity {@code @AccessControl}). The server rejects a caller without a
         * matching role, and page-action buttons the caller can't run are hidden from the rendered
         * page. Unset (the default) means any caller the surface already admits — for an entity
         * action that's the entity's write roles; for a page action, any authenticated user.
         */
        public ActionBuilder roles(String... roles) {
            this.roles = List.of(roles);
            return this;
        }

        /** Route the client to {@code url} when clicked ({@code {id}} is filled with the record id). */
        public ActionBuilder navigate(String url) {
            this.navigateUrl = url;
            return this;
        }

        /**
         * Pick the icon per row ({@link ActionScope#ROW} actions) — e.g. {@code play} vs {@code pause}
         * by the row's state. Overrides the fixed {@link #icon(String)} on rows it resolves.
         */
        public ActionBuilder icon(Function<ActionRow, String> icon) {
            this.iconFn = icon;
            return this;
        }

        /** Pick the label per row ({@link ActionScope#ROW} actions). Overrides the fixed {@link #label(String)}. */
        public ActionBuilder label(Function<ActionRow, String> label) {
            this.labelFn = label;
            return this;
        }

        /** Show this row action only on rows where the predicate holds ({@link ActionScope#ROW} actions). */
        public ActionBuilder visibleWhen(Predicate<ActionRow> visible) {
            this.visibleFn = visible;
            return this;
        }

        /** Enable this row action only on rows where the predicate holds — disabled (greyed) elsewhere. */
        public ActionBuilder enabledWhen(Predicate<ActionRow> enabled) {
            this.enabledFn = enabled;
            return this;
        }

        Action build() {
            return new Action(key, label != null ? label : key, icon, logo, scope, menu, navigateUrl, handler,
                    iconFn, labelFn, visibleFn, enabledFn, form, roles);
        }
    }
}
