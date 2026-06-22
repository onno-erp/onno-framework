package su.onno.auth.spi;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SsoProvider} carries an optional brand icon (full-color by default, monochrome opt-in) and
 * an optional full button label. The 3-arg and 4-arg convenience constructors must keep existing
 * callers (OIDC registrations, pre-icon connectors) compiling and behaving unchanged, while the
 * canonical constructor lets a connector tint a monochrome mark and supply a ready-made label.
 */
class SsoProviderTest {

    @Test
    void threeArgConstructorLeavesTheOptionalFieldsAtTheirDefaults() {
        SsoProvider p = new SsoProvider("telegram", "Telegram", "/api/auth/telegram/start");

        assertThat(p.id()).isEqualTo("telegram");
        assertThat(p.label()).isEqualTo("Telegram");
        assertThat(p.authorizationUrl()).isEqualTo("/api/auth/telegram/start");
        assertThat(p.iconUrl()).isNull();
        assertThat(p.monochrome()).isFalse();
        assertThat(p.buttonLabel()).isNull();
    }

    @Test
    void fourArgConstructorCarriesTheIconUrlInFullColorByDefault() {
        SsoProvider p = new SsoProvider(
                "telegram", "Telegram", "/api/auth/telegram/start", "/api/auth/telegram/logo.svg");

        assertThat(p.iconUrl()).isEqualTo("/api/auth/telegram/logo.svg");
        // Full color by default; a connector opts into the tinted monochrome treatment explicitly.
        assertThat(p.monochrome()).isFalse();
        assertThat(p.buttonLabel()).isNull();
    }

    @Test
    void canonicalConstructorCarriesMonochromeAndButtonLabel() {
        SsoProvider p = new SsoProvider("telegram", "Telegram", "/api/auth/telegram/start",
                "/api/auth/telegram/logo.svg", true, "Войти через Telegram");

        assertThat(p.monochrome()).isTrue();
        assertThat(p.buttonLabel()).isEqualTo("Войти через Telegram");
    }

    @Test
    void equalityAndHashCodeAccountForTheIconUrl() {
        // The 3-arg form must equal the 4-arg form with a null icon (backward-compatible identity),
        // and differ once an icon is supplied.
        SsoProvider noIcon = new SsoProvider("telegram", "Telegram", "/start");
        SsoProvider sameNoIcon = new SsoProvider("telegram", "Telegram", "/start", null);
        SsoProvider withIcon = new SsoProvider("telegram", "Telegram", "/start", "/logo.svg");

        assertThat(noIcon).isEqualTo(sameNoIcon);
        assertThat(noIcon).hasSameHashCodeAs(sameNoIcon);
        assertThat(noIcon).isNotEqualTo(withIcon);
        assertThat(withIcon.toString()).contains("/logo.svg");
    }
}
