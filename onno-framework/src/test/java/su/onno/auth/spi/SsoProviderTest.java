package su.onno.auth.spi;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SsoProvider} carries an optional button icon. The 3-arg convenience constructor must keep
 * existing callers (OIDC registrations, pre-icon connectors) compiling and behaving unchanged, while
 * the 4-arg canonical constructor lets a connector supply a logo URL.
 */
class SsoProviderTest {

    @Test
    void threeArgConstructorLeavesIconUrlNull() {
        SsoProvider p = new SsoProvider("telegram", "Telegram", "/api/auth/telegram/start");

        assertThat(p.id()).isEqualTo("telegram");
        assertThat(p.label()).isEqualTo("Telegram");
        assertThat(p.authorizationUrl()).isEqualTo("/api/auth/telegram/start");
        assertThat(p.iconUrl()).isNull();
    }

    @Test
    void fourArgConstructorCarriesTheIconUrl() {
        SsoProvider p = new SsoProvider(
                "telegram", "Telegram", "/api/auth/telegram/start", "/api/auth/telegram/logo.svg");

        assertThat(p.iconUrl()).isEqualTo("/api/auth/telegram/logo.svg");
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
