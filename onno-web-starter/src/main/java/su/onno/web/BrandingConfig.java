package su.onno.web;

/**
 * The app shell's branding: an explicit app name, a horizontal logo, a compact app mark, a
 * favicon, and brand color overrides for the light and dark {@link BrandPalette}s. Configured
 * with {@code onno.ui.branding.*} (see {@link UiProperties}) and served once, before sign-in, by
 * {@code GET /api/branding}. Which logo or mark a theme shows is the client's choice.
 *
 * <p>Every field is optional. A {@code null}/empty {@code appName} falls back to the
 * active profile title (today's behavior); a {@code null} {@code logoUrl} keeps the
 * text brand; empty palettes keep the renderer's neutral scale. A {@code null}
 * {@code logoWidth}/{@code logoHeight} leaves the logo at its intrinsic aspect ratio and
 * each surface's default size.</p>
 *
 * @param appName     explicit application name; falls back to the profile title when blank
 * @param logoUrl     logo image URL/asset shown in the sidebar header and mobile menu
 * @param logoUrlDark optional dark-mode logo variant; falls back to {@code logoUrl}
 * @param logoWidth   optional fixed logo width in dp; {@code null} keeps the intrinsic width
 * @param logoHeight  optional fixed logo height in dp; {@code null} keeps each surface's default
 * @param faviconUrl  optional favicon the web client installs at runtime
 * @param light       brand color overrides for light mode
 * @param dark        brand color overrides for dark mode
 * @param markUrl     optional compact mark for square app-rail/home affordances
 * @param markUrlDark optional dark-mode compact mark; falls back to {@code markUrl}
 * @param markFramed  whether the shell draws its own border around the compact mark
 */
public record BrandingConfig(
        String appName, String logoUrl, String logoUrlDark,
        Integer logoWidth, Integer logoHeight, String faviconUrl,
        BrandPalette light, BrandPalette dark,
        String markUrl, String markUrlDark, boolean markFramed) {

    public BrandingConfig {
        light = light == null ? BrandPalette.empty() : light;
        dark = dark == null ? BrandPalette.empty() : dark;
    }

    public static BrandingConfig defaults() {
        return new BrandingConfig(null, null, null, null, null, null,
                BrandPalette.empty(), BrandPalette.empty(), null, null, true);
    }
}
