package su.onno.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BrandingConfig} (issue #97) carries an app name, a logo (with an optional dark variant), a
 * compact mark, a favicon, and per-mode brand color overrides. Unconfigured branding is empty (no-op)
 * so unbranded apps are unaffected.
 */
class BrandingConfigTest {

    @Test
    void capturesAllBranding() {
        BrandingConfig branding = new BrandingConfig("Acme", "/logo.svg", "/logo-dark.svg", null, null,
                "/favicon.svg",
                new BrandPalette(null, "#FFFFFF", null, null, null, "#2563EB", null),
                new BrandPalette(null, null, null, null, null, "#3B82F6", null),
                "/mark.svg", "/mark-dark.svg", false);

        assertThat(branding.appName()).isEqualTo("Acme");
        assertThat(branding.markUrlDark()).isEqualTo("/mark-dark.svg");
        assertThat(branding.markFramed()).isFalse();
        assertThat(branding.faviconUrl()).isEqualTo("/favicon.svg");
        assertThat(branding.light().primary()).isEqualTo("#2563EB");
        assertThat(branding.light().surface()).isEqualTo("#FFFFFF");
        assertThat(branding.dark().primary()).isEqualTo("#3B82F6");
        // Unset slots stay null so the renderer keeps its default for them.
        assertThat(branding.light().border()).isNull();
    }

    @Test
    void defaults_haveEmptyBranding() {
        BrandingConfig branding = BrandingConfig.defaults();
        assertThat(branding.appName()).isNull();
        assertThat(branding.logoUrl()).isNull();
        assertThat(branding.markFramed()).isTrue();
        assertThat(branding.light().isEmpty()).isTrue();
        assertThat(branding.dark().isEmpty()).isTrue();
    }

    @Test
    void nullPalettes_normalizeToEmpty() {
        BrandingConfig branding = new BrandingConfig(null, null, null, null, null, null, null, null,
                null, null, true);
        assertThat(branding.light()).isNotNull();
        assertThat(branding.dark().isEmpty()).isTrue();
    }
}
