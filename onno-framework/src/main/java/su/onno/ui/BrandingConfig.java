package su.onno.ui;

/**
 * Declarative back-office branding for a {@link Layout}'s shell: an explicit app
 * name, a horizontal logo, a compact app mark, a favicon, and brand color
 * overrides for the light and dark {@link BrandPalette}s. Authored via
 * {@code UiLayoutBuilder.shell()} — e.g.
 *
 * <pre>
 * s.shell().nav(NavStyle.SIDEBAR)
 *     .brand("VetoVet")
 *     .logo("/branding/logo.svg")
 *     .mark("/branding/mark.svg")
 *     .markFrame(false)
 *     .favicon("/branding/favicon.svg")
 *     .light(c -> c.primary("#2563EB"))
 *     .dark(c -> c.primary("#3B82F6"));
 * </pre>
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

    /** Source-compatible constructor for branding authored before compact app marks were added. */
    public BrandingConfig(String appName, String logoUrl, String logoUrlDark,
                          Integer logoWidth, Integer logoHeight, String faviconUrl,
                          BrandPalette light, BrandPalette dark) {
        this(appName, logoUrl, logoUrlDark, logoWidth, logoHeight, faviconUrl,
                light, dark, null, null, true);
    }

    /** Source-compatible constructor for branding authored before mark framing was configurable. */
    public BrandingConfig(String appName, String logoUrl, String logoUrlDark,
                          Integer logoWidth, Integer logoHeight, String faviconUrl,
                          BrandPalette light, BrandPalette dark,
                          String markUrl, String markUrlDark) {
        this(appName, logoUrl, logoUrlDark, logoWidth, logoHeight, faviconUrl,
                light, dark, markUrl, markUrlDark, true);
    }

    /** Whether a non-blank app name was authored (else callers fall back to the profile title). */
    public boolean hasAppName() {
        return appName != null && !appName.isBlank();
    }

    /** Whether a logo image was authored (else the shell renders the text brand). */
    public boolean hasLogo() {
        return logoUrl != null && !logoUrl.isBlank();
    }

    /** Whether a dedicated compact app mark was authored. */
    public boolean hasMark() {
        return markUrl != null && !markUrl.isBlank();
    }

    /** The logo for the requested theme: the dark variant in dark mode when set, else {@link #logoUrl}. */
    public String logoFor(String theme) {
        if ("dark".equalsIgnoreCase(theme) && logoUrlDark != null && !logoUrlDark.isBlank()) {
            return logoUrlDark;
        }
        return logoUrl;
    }

    /**
     * Compact mark for the requested theme. A favicon is already square-oriented and is the first
     * compatibility fallback; the horizontal logo remains the final fallback for older apps.
     */
    public String markFor(String theme) {
        if ("dark".equalsIgnoreCase(theme) && markUrlDark != null && !markUrlDark.isBlank()) {
            return markUrlDark;
        }
        if (markUrl != null && !markUrl.isBlank()) {
            return markUrl;
        }
        if (faviconUrl != null && !faviconUrl.isBlank()) {
            return faviconUrl;
        }
        return logoFor(theme);
    }

    /** The brand color overrides for the requested theme. */
    public BrandPalette paletteFor(String theme) {
        return "dark".equalsIgnoreCase(theme) ? dark : light;
    }
}
