package su.onno.ui;

/**
 * Per-entity UI definition, authored in code and discovered as a Spring bean —
 * the "view" layer of the framework. Override a surface method to take control of
 * how that entity's screen is composed; anything you don't define falls back to
 * the auto-generated default. The framework resolves these into a renderer-
 * agnostic view model and compiles it to DivKit (or any future target), so you
 * shape the UI from code without writing any frontend.
 *
 * <pre>
 * &#64;Component
 * class PropertyView implements EntityView {
 *     public Class&lt;?&gt; entity() { return Property.class; }
 *     public void list(ListSpec list) {
 *         list.columns("displayName", "address", "defaultNightRate")
 *             .label("defaultNightRate", "Rate / night");
 *     }
 * }
 * </pre>
 */
public interface EntityView {

    /** The domain class (catalog or document) this view customizes. */
    Class<?> entity();

    /**
     * The profile/persona id this view applies to, or {@code null} (default) to
     * apply to every profile. A profile-specific view lets you build a radically
     * different screen for a persona; the resolver prefers it over the default.
     * Composition is just Java — subclass another view and override a surface.
     */
    default String profile() {
        return null;
    }

    /** Customize the list/table surface. Default: auto-generated columns. */
    default void list(ListSpec list) {}

    /**
     * Per-field hints for this entity — order, visibility (list/form/detail),
     * group, width, widget — using the same {@code field(...)} DSL as the layout.
     * Field config lives here now; the layout's section calls are pure placement.
     * Applies to the default view; a profile-specific view can override.
     */
    default void fields(EntityConfigBuilder fields) {}

    /**
     * Custom action buttons for this entity — on the list (toolbar / per-row) or the record
     * detail. Each runs arbitrary server logic ({@code .handler(...)}) or just navigates
     * ({@code .navigate(...)}). Default: none.
     *
     * <pre>
     * public void actions(ActionSpec a) {
     *     a.action("recalc").label("Recalculate").icon("calculator").scope(ActionScope.DETAIL)
     *      .handler(ctx -&gt; { service.recalc(ctx.id()); return ActionResult.refresh("Recalculated"); });
     * }
     * </pre>
     */
    default void actions(ActionSpec actions) {}

    /**
     * Custom input fields for this entity's list toolbar — a date picker, dropdown, text or number
     * field shown next to the action buttons. An input doesn't filter the list itself; its current
     * value is passed to the {@link #actions action} handlers via {@link ActionContext#input(String)}
     * when a button is clicked. Default: none.
     *
     * <pre>
     * public void inputs(InputSpec in) {
     *     in.input("asOf").label("As of").type(InputType.DATE);
     * }
     * public void actions(ActionSpec a) {
     *     a.action("report").label("Report").scope(ActionScope.TOOLBAR)
     *      .handler(ctx -&gt; ActionResult.message("Report as of " + ctx.input("asOf")));
     * }
     * </pre>
     */
    default void inputs(InputSpec inputs) {}

    /**
     * Whether this entity gets a discussion thread (the {@code /api/comments} panel) on its detail
     * surface. Opt-in and per-entity: the default is {@code false}, so an entity has no comments
     * until a view turns them on — you choose exactly which catalogs/documents support discussions.
     * The global {@code onno.comments.enabled} switch still gates the feature as a whole; this picks
     * where it appears. Override to opt in:
     *
     * <pre>
     * &#64;Override public boolean comments() { return true; }
     * </pre>
     *
     * <p>Resolved per entity (not per profile): if any of an entity's views opts in, its detail
     * carries the panel.</p>
     */
    default boolean comments() {
        return false;
    }
}
