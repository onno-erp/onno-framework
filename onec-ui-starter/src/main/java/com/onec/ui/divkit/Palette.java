package com.onec.ui.divkit;

import com.onec.ui.BrandPalette;
import com.onec.ui.BrandingConfig;

/**
 * Theme-aware color palette mirroring the app's shadcn CSS variables (a neutral,
 * zero-saturation scale) so emitted DivKit surfaces match the rest of the UI in
 * light and dark. The client passes {@code ?theme=dark|light}; the controller
 * resolves it via {@link #of} and threads the palette through the builders.
 *
 * <p>A consumer can override any slot from Java via {@code shell().light(...)} /
 * {@code shell().dark(...)} — those land here as a {@link BrandPalette} merged over
 * the built-in {@link #LIGHT}/{@link #DARK} constants by {@link #of(String, BrandingConfig)}.</p>
 */
public record Palette(
        String page, String surface, String border, String text, String muted, String faint,
        String primary, String primarySoft, String success, String successSoft, String rowAlt) {

    // shadcn :root  (background/card #fff, foreground #0a0a0a, primary #171717,
    // secondary/accent #f5f5f5, muted-foreground #737373, border #ebebeb)
    public static final Palette LIGHT = new Palette(
            "#FFFFFF", "#FFFFFF", "#EBEBEB", "#0A0A0A", "#737373", "#A3A3A3",
            "#171717", "#F5F5F5", "#16A34A", "#DCFCE7", "#FAFAFA");

    // shadcn .dark  (background #0d0d0d, card #121212, foreground #ededed,
    // primary #fafafa, secondary/accent #1f1f1f, muted-foreground #808080, border #242424)
    public static final Palette DARK = new Palette(
            "#0D0D0D", "#121212", "#242424", "#EDEDED", "#808080", "#5C5C5C",
            "#FAFAFA", "#1F1F1F", "#22C55E", "#14532D", "#171717");

    public static Palette of(String theme) {
        return "dark".equalsIgnoreCase(theme) ? DARK : LIGHT;
    }

    /**
     * The palette for {@code theme} with the consumer's {@link BrandingConfig} overrides
     * merged in. Starts from the built-in {@link #LIGHT}/{@link #DARK} and replaces only
     * the slots the app set, so unbranded apps render exactly as before.
     */
    public static Palette of(String theme, BrandingConfig branding) {
        Palette base = of(theme);
        return branding == null ? base : base.withOverrides(branding.paletteFor(theme));
    }

    /** This palette with any non-null slot of {@code o} replacing the default; a no-op if empty. */
    public Palette withOverrides(BrandPalette o) {
        if (o == null || o.isEmpty()) {
            return this;
        }
        return new Palette(
                coalesce(o.page(), page),
                coalesce(o.surface(), surface),
                coalesce(o.border(), border),
                coalesce(o.text(), text),
                coalesce(o.muted(), muted),
                faint,
                coalesce(o.primary(), primary),
                coalesce(o.primarySoft(), primarySoft),
                success, successSoft, rowAlt);
    }

    private static String coalesce(String override, String fallback) {
        return override == null || override.isBlank() ? fallback : override;
    }
}
