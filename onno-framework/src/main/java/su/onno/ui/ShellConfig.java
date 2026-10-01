package su.onno.ui;

/**
 * App-shell presentation config: the {@link NavStyle} this layout's navigation
 * uses, plus the consumer's {@link BrandingConfig} (app name, logo, favicon, brand
 * palette). Since a {@link Layout} is now authored per {@link Viewport}, the shell
 * carries a single style — author a separate layout to present the nav differently
 * on another device. A {@code null} style means "let the renderer pick a sensible
 * default for the viewport". Authored via {@code UiLayoutBuilder.shell()}.
 */
public record ShellConfig(NavStyle nav, BrandingConfig branding, java.util.Map<String, MobileItem> mobileItems) {

    public record MobileItem(String label, int order) {}

    public ShellConfig(NavStyle nav, BrandingConfig branding) {
        this(nav, branding, java.util.Map.of());
    }

    public ShellConfig {
        mobileItems = mobileItems == null ? java.util.Map.of() : java.util.Map.copyOf(mobileItems);
        branding = branding == null ? BrandingConfig.defaults() : branding;
    }

    public static ShellConfig defaults() {
        return new ShellConfig(null, BrandingConfig.defaults());
    }
}
