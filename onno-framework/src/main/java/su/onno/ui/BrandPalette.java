package su.onno.ui;

/**
 * A consumer's brand color overrides for one mode (light or dark). Every field is
 * nullable: a {@code null} keeps the renderer's default for that slot, so an app can
 * override just {@code primary} and inherit the rest of the neutral scale. The
 * renderer ({@code onno-ui-starter}'s {@code Palette}) merges a non-null value over
 * its built-in {@code LIGHT}/{@code DARK} constant, which is how the DivKit chrome
 * picks up brand colors.
 *
 * <p>The slots mirror the ones the issue calls out as the brandable minimum plus the
 * two that visibly carry the brand accent — {@code primary} (active/selected) and
 * {@code primarySoft} (its tint behind the active item).</p>
 *
 * @param page        the app background behind the islands
 * @param surface     card / panel fill
 * @param border      hairline strokes and separators
 * @param text        primary foreground
 * @param muted       secondary / caption foreground
 * @param primary     brand accent (active nav, links, primary buttons)
 * @param primarySoft the tint painted behind the active/selected accent
 */
public record BrandPalette(
        String page, String surface, String border, String text, String muted,
        String primary, String primarySoft) {

    private static final BrandPalette EMPTY = new BrandPalette(null, null, null, null, null, null, null);

    /** No overrides — every slot keeps the renderer default. */
    public static BrandPalette empty() {
        return EMPTY;
    }

    /** True when nothing is overridden, so the renderer can skip merging entirely. */
    public boolean isEmpty() {
        return page == null && surface == null && border == null && text == null
                && muted == null && primary == null && primarySoft == null;
    }
}
