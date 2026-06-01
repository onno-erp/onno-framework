package com.onec.ui;

/**
 * An authored page — a route whose content you compose in code, the page-level
 * peer of {@link EntityView}. Implement it, register as a Spring bean, and the
 * framework resolves it for the route and compiles your composition to DivKit
 * (or any future renderer). Unlike the auto-generated entity surfaces, a page is
 * freeform: arrange widgets and custom components however you like — e.g. a
 * bespoke dashboard.
 *
 * <pre>
 * &#64;Component
 * class DashboardPage implements Page {
 *     public String route() { return "/"; }
 *     public void compose(PageBuilder b) {
 *         b.title("Dashboard");
 *         b.widget("Properties").type("count").catalog(Property.class);
 *     }
 * }
 * </pre>
 */
public interface Page {

    /** The route this page renders, e.g. {@code "/"} for the home/dashboard. */
    String route();

    /**
     * The profile/persona id this page applies to, or {@code null} (default) for
     * every profile. A profile-specific page wins over the default for that
     * persona — the same resolution rule as {@link EntityView}.
     */
    default String profile() {
        return null;
    }

    /**
     * The device class this page targets, or {@code null} (default) for every
     * viewport. A viewport-specific page wins over the universal one on that
     * device — compose a different surface (fewer widgets, a list, ...) per device.
     */
    default Viewport viewport() {
        return null;
    }

    /** Compose the page content. */
    void compose(PageBuilder b);
}
