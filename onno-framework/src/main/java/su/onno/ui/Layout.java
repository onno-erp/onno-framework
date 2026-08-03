package su.onno.ui;

/**
 * An authored layout — the structural peer of {@link Page} and {@link EntityView}.
 * One {@code Layout} bean describes one persona's navigation sections and, for
 * persona layouts, the roles that resolve into it. The default layout alone owns
 * the shared shell, branding, and identity-directory link.
 *
 * <p>The default layout ({@code profile() == null}) is the back-office shell served
 * to anyone who matches no persona. A persona layout ({@code profile() == "cleaning"})
 * declares its target roles and its own curated sections. A layout never widens
 * access — RBAC still gates every data endpoint.</p>
 *
 * <pre>
 * &#64;Component
 * class MainLayout implements Layout {
 *     public void configure(LayoutSpec s) {
 *         s.shell().nav(NavStyle.SIDEBAR);
 *         s.section("Rentals").icon("house")
 *             .catalog(Property.class, c -> c.field("displayName").order(0));
 *     }
 * }
 * </pre>
 */
public interface Layout {

    /** The persona id this layout builds, or {@code null} for the default shell. */
    default String profile() {
        return null;
    }

    /**
     * The device class this layout targets, or {@code null} (default) to apply to
     * every viewport. A viewport-specific layout fully replaces the universal one
     * for the same {@link #profile()} on that device — author a separate
     * {@code Layout} bean per device to curate nav and presentation independently.
     */
    default Viewport viewport() {
        return null;
    }

    /**
     * Build this layout's sections and persona metadata. Shell and identity settings
     * are valid only on the default layout ({@link #profile()} is {@code null});
     * startup rejects them on named persona layouts instead of silently discarding them.
     */
    void configure(LayoutSpec spec);
}
