package su.onno.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import su.onno.observability.TelemetrySink;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The UI's configuration endpoints serve each piece once: branding only from {@code /api/branding},
 * read-only mode ({@code onno.web.read-only}) and the chrome language from {@code /api/config}.
 */
class UiConfigControllerTest {

    private static UiConfigController controller(UiProperties ui, WebProperties web) {
        StaticListableBeanFactory none = new StaticListableBeanFactory();
        return new UiConfigController(ui, web, UiMessages.defaults(),
                none.getBeanProvider(UpdateChecker.class), none.getBeanProvider(TelemetrySink.class));
    }

    @Test
    void configCarriesReadOnlyModeFromTheWebSettingsAndNoBranding() {
        UiProperties ui = new UiProperties();
        ui.setLocale("ru");
        ui.getBranding().setAppName("Acme");
        WebProperties web = new WebProperties();
        web.setReadOnly(true);

        Map<String, Object> config = controller(ui, web).getConfig();

        assertThat(config).containsEntry("readOnly", true).containsEntry("locale", "ru")
                .containsKey("messages")
                .doesNotContainKeys("basePath", "appName", "branding");
    }

    @Test
    void brandingIsServedFromTheUiSettings() {
        UiProperties ui = new UiProperties();
        ui.getBranding().setAppName("Acme");
        ui.getBranding().setLogoUrl("/logo.svg");
        ui.getBranding().setLogoUrlDark("/logo-dark.svg");
        ui.getBranding().getLight().put("primary", "#2563EB");

        Map<String, Object> branding = controller(ui, new WebProperties()).getBranding();

        assertThat(branding).containsEntry("appName", "Acme").containsEntry("logoUrl", "/logo.svg")
                .containsEntry("logoUrlDark", "/logo-dark.svg").containsEntry("markFramed", true);
        assertThat(branding.get("palette")).isEqualTo(Map.of(
                "light", Map.of("primary", "#2563EB"), "dark", Map.of()));
    }
}
