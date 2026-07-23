package su.onno.ui.divkit;

/**
 * Corner radii for the server-rendered DivKit surfaces, kept in one place so the DivKit chrome
 * (dashboards, authored pages, settings, detail cards, action buttons) matches the React islands.
 *
 * <p>These mirror the CSS shape tokens the React layer reads (see {@code --radius-control} /
 * {@code --radius-card} in {@code index.css}): {@link #CONTROL} is the pill default for interactive
 * controls (buttons/pills), {@link #CARD} matches {@code --radius-card} (1rem). DivKit takes a
 * pixel radius rather than a CSS var, so this is the DivKit-side counterpart; a large value reads as
 * a full pill. (Wiring these to the same {@code onno.ui.theme} override as the CSS tokens is a
 * follow-up — today they carry the matching defaults.)</p>
 */
final class Radii {

    private Radii() {}

    /** Interactive controls (action buttons, pills) — a full pill, matching {@code --radius-control}. */
    static final int CONTROL = 999;

    /** Navigation selections and workspace tabs — matches {@code --radius-field} (12px). */
    static final int TAB = 12;

    /** Surfaces (cards, the page header island, tables) — matches {@code --radius-card} (16px). */
    static final int CARD = 16;
}
