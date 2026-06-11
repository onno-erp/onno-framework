package com.onec.ui.divkit;

import com.onec.ui.BrandPalette;
import com.onec.ui.BrandingConfig;
import com.onec.ui.NavStyle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Branding (issue #97) reaches the rendered DivKit chrome two ways: brand color overrides merge
 * over the built-in {@link Palette} per mode, and a configured logo replaces the text brand in
 * the sidebar header and mobile menu. An empty {@link BrandingConfig} leaves both untouched.
 */
class BrandingPaletteTest {

    private static BrandingConfig branding(BrandPalette light, BrandPalette dark) {
        return new BrandingConfig(null, "/logo.svg", "/logo-dark.svg", null, light, dark);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> container) {
        return (List<Map<String, Object>>) container.get("items");
    }

    @Test
    void paletteOverride_replacesOnlySetSlots() {
        BrandPalette light = new BrandPalette(null, null, null, null, null, "#2563EB", "#EFF6FF");
        Palette p = Palette.of("light", branding(light, BrandPalette.empty()));

        assertThat(p.primary()).isEqualTo("#2563EB");
        assertThat(p.primarySoft()).isEqualTo("#EFF6FF");
        // Untouched slots keep the default LIGHT scale.
        assertThat(p.page()).isEqualTo(Palette.LIGHT.page());
        assertThat(p.border()).isEqualTo(Palette.LIGHT.border());
    }

    @Test
    void paletteOverride_isModeSpecific() {
        BrandPalette light = new BrandPalette(null, null, null, null, null, "#2563EB", null);
        BrandPalette dark = new BrandPalette(null, null, null, null, null, "#3B82F6", null);
        BrandingConfig b = branding(light, dark);

        assertThat(Palette.of("light", b).primary()).isEqualTo("#2563EB");
        assertThat(Palette.of("dark", b).primary()).isEqualTo("#3B82F6");
    }

    @Test
    void emptyBranding_leavesPaletteUnchanged() {
        assertThat(Palette.of("light", BrandingConfig.defaults())).isEqualTo(Palette.LIGHT);
        assertThat(Palette.of("dark", BrandingConfig.defaults())).isEqualTo(Palette.DARK);
        // A null BrandingConfig is also a no-op.
        assertThat(Palette.of("light", null)).isEqualTo(Palette.LIGHT);
    }

    @Test
    void sidebarLogo_rendersImageInsteadOfTextBrand() {
        Map<String, Object> nav = ShellLayoutBuilder.nav(
                "Acme", "/logo.svg", List.of(), NavStyle.SIDEBAR, false, Palette.LIGHT);

        Map<String, Object> header = items(nav).get(0);
        assertThat(header.get("type")).isEqualTo("image");
        assertThat(header.get("image_url")).isEqualTo("/logo.svg");
    }

    @Test
    void sidebarWithoutLogo_keepsTextBrand() {
        Map<String, Object> nav = ShellLayoutBuilder.nav(
                "Acme", null, List.of(), NavStyle.SIDEBAR, false, Palette.LIGHT);

        Map<String, Object> header = items(nav).get(0);
        assertThat(header.get("type")).isEqualTo("text");
        assertThat(header.get("text")).isEqualTo("Acme");
    }

    @Test
    void menuLogo_rendersImageAsTitle() {
        Map<String, Object> menu = ShellLayoutBuilder.menu(
                "Acme", "/logo-dark.svg", List.of(), "user", List.of(), "default", Palette.DARK);

        Map<String, Object> title = items(menu).get(0);
        assertThat(title.get("type")).isEqualTo("image");
        assertThat(title.get("image_url")).isEqualTo("/logo-dark.svg");
    }
}
