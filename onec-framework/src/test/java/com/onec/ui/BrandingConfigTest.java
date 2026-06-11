package com.onec.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code shell()} branding DSL (issue #97) captures an app name, a logo (with an optional
 * dark variant), a favicon, and per-mode brand color overrides into the {@link ShellConfig}'s
 * {@link BrandingConfig}. An unconfigured shell carries empty (no-op) branding so unbranded
 * apps are unaffected.
 */
class BrandingConfigTest {

    @Test
    void shellBuilder_capturesAllBranding() {
        UiLayoutBuilder b = new UiLayoutBuilder();
        b.shell()
                .nav(NavStyle.SIDEBAR)
                .brand("Acme")
                .logo("/logo.svg", "/logo-dark.svg")
                .favicon("/favicon.svg")
                .light(c -> c.primary("#2563EB").surface("#FFFFFF"))
                .dark(c -> c.primary("#3B82F6"));

        BrandingConfig branding = b.buildShell().branding();
        assertThat(branding.appName()).isEqualTo("Acme");
        assertThat(branding.hasAppName()).isTrue();
        assertThat(branding.hasLogo()).isTrue();
        assertThat(branding.faviconUrl()).isEqualTo("/favicon.svg");
        assertThat(branding.light().primary()).isEqualTo("#2563EB");
        assertThat(branding.light().surface()).isEqualTo("#FFFFFF");
        assertThat(branding.dark().primary()).isEqualTo("#3B82F6");
        // Unset slots stay null so the renderer keeps its default for them.
        assertThat(branding.light().border()).isNull();
    }

    @Test
    void logoFor_picksDarkVariantInDarkMode() {
        UiLayoutBuilder b = new UiLayoutBuilder();
        b.shell().logo("/logo.svg", "/logo-dark.svg");
        BrandingConfig branding = b.buildShell().branding();

        assertThat(branding.logoFor("light")).isEqualTo("/logo.svg");
        assertThat(branding.logoFor("dark")).isEqualTo("/logo-dark.svg");
        // No theme defaults to the light logo.
        assertThat(branding.logoFor(null)).isEqualTo("/logo.svg");
    }

    @Test
    void singleLogo_fallsBackToItInDarkMode() {
        UiLayoutBuilder b = new UiLayoutBuilder();
        b.shell().logo("/logo.svg");
        BrandingConfig branding = b.buildShell().branding();

        assertThat(branding.logoFor("dark")).isEqualTo("/logo.svg");
    }

    @Test
    void unconfiguredShell_hasEmptyBranding() {
        UiLayoutBuilder b = new UiLayoutBuilder();
        b.shell().nav(NavStyle.TOPBAR);
        BrandingConfig branding = b.buildShell().branding();

        assertThat(branding.hasAppName()).isFalse();
        assertThat(branding.hasLogo()).isFalse();
        assertThat(branding.light().isEmpty()).isTrue();
        assertThat(branding.dark().isEmpty()).isTrue();
    }

    @Test
    void shellConfigDefaults_neverNullBranding() {
        assertThat(ShellConfig.defaults().branding()).isNotNull();
        assertThat(ShellConfig.defaults().branding().light().isEmpty()).isTrue();
        // The back-compat single-arg constructor also fills in empty branding.
        assertThat(new ShellConfig(NavStyle.SIDEBAR).branding()).isNotNull();
    }
}
