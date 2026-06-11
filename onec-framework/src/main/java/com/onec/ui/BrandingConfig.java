package com.onec.ui;

/**
 * Declarative back-office branding for a {@link Layout}'s shell: an explicit app
 * name, a logo (with an optional dark-mode variant), a favicon, and brand color
 * overrides for the light and dark {@link BrandPalette}s. Authored via
 * {@code UiLayoutBuilder.shell()} — e.g.
 *
 * <pre>
 * s.shell().nav(NavStyle.SIDEBAR)
 *     .brand("VetoVet")
 *     .logo("/branding/logo.svg")
 *     .favicon("/branding/favicon.svg")
 *     .light(c -> c.primary("#2563EB"))
 *     .dark(c -> c.primary("#3B82F6"));
 * </pre>
 *
 * <p>Every field is optional. A {@code null}/empty {@code appName} falls back to the
 * active profile title (today's behavior); a {@code null} {@code logoUrl} keeps the
 * text brand; empty palettes keep the renderer's neutral scale.</p>
 *
 * @param appName     explicit application name; falls back to the profile title when blank
 * @param logoUrl     logo image URL/asset shown in the sidebar header and mobile menu
 * @param logoUrlDark optional dark-mode logo variant; falls back to {@code logoUrl}
 * @param faviconUrl  optional favicon the web client installs at runtime
 * @param light       brand color overrides for light mode
 * @param dark        brand color overrides for dark mode
 */
public record BrandingConfig(
        String appName, String logoUrl, String logoUrlDark, String faviconUrl,
        BrandPalette light, BrandPalette dark) {

    public BrandingConfig {
        light = light == null ? BrandPalette.empty() : light;
        dark = dark == null ? BrandPalette.empty() : dark;
    }

    public static BrandingConfig defaults() {
        return new BrandingConfig(null, null, null, null, BrandPalette.empty(), BrandPalette.empty());
    }

    /** Whether a non-blank app name was authored (else callers fall back to the profile title). */
    public boolean hasAppName() {
        return appName != null && !appName.isBlank();
    }

    /** Whether a logo image was authored (else the shell renders the text brand). */
    public boolean hasLogo() {
        return logoUrl != null && !logoUrl.isBlank();
    }

    /** The logo for the requested theme: the dark variant in dark mode when set, else {@link #logoUrl}. */
    public String logoFor(String theme) {
        if ("dark".equalsIgnoreCase(theme) && logoUrlDark != null && !logoUrlDark.isBlank()) {
            return logoUrlDark;
        }
        return logoUrl;
    }

    /** The brand color overrides for the requested theme. */
    public BrandPalette paletteFor(String theme) {
        return "dark".equalsIgnoreCase(theme) ? dark : light;
    }
}
