package com.onec.ui;

/**
 * App-shell presentation config: the {@link NavStyle} this layout's navigation
 * uses, plus the consumer's {@link BrandingConfig} (app name, logo, favicon, brand
 * palette). Since a {@link Layout} is now authored per {@link Viewport}, the shell
 * carries a single style — author a separate layout to present the nav differently
 * on another device. A {@code null} style means "let the renderer pick a sensible
 * default for the viewport". Authored via {@code UiLayoutBuilder.shell()}.
 */
public record ShellConfig(NavStyle nav, BrandingConfig branding) {

    public ShellConfig {
        branding = branding == null ? BrandingConfig.defaults() : branding;
    }

    /** Back-compat: a shell with just a nav style and default (empty) branding. */
    public ShellConfig(NavStyle nav) {
        this(nav, BrandingConfig.defaults());
    }

    public static ShellConfig defaults() {
        return new ShellConfig(null, BrandingConfig.defaults());
    }
}
