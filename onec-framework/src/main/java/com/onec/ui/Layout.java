package com.onec.ui;

/**
 * An authored layout — the structural peer of {@link Page} and {@link EntityView}.
 * One {@code Layout} bean describes one persona's shell: its navigation sections,
 * nav presentation, branding, and (for personas) the roles that resolve into it.
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
 *         s.section("Rentals").icon("home")
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

    /** Build this layout's sections, shell, branding and (for personas) roles. */
    void configure(LayoutSpec spec);
}
