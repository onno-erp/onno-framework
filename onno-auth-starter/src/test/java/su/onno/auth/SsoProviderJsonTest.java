package su.onno.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import su.onno.auth.spi.AuthMethods;
import su.onno.auth.spi.SsoProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@link SsoProvider} JSON shape exposed to any client that reads the auth-methods list (the web
 * login page, the native mobile client). Adding {@code iconUrl} must be additive: it appears as an
 * extra nullable field and round-trips, without breaking the existing fields.
 */
class SsoProviderJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializesIconUrlAsAnExtraField() throws Exception {
        SsoProvider provider = new SsoProvider(
                "telegram", "Telegram", "/api/auth/telegram/start", "/api/auth/telegram/logo.svg");

        JsonNode json = mapper.readTree(mapper.writeValueAsString(provider));

        assertThat(json.get("id").asText()).isEqualTo("telegram");
        assertThat(json.get("label").asText()).isEqualTo("Telegram");
        assertThat(json.get("authorizationUrl").asText()).isEqualTo("/api/auth/telegram/start");
        assertThat(json.get("iconUrl").asText()).isEqualTo("/api/auth/telegram/logo.svg");
    }

    @Test
    void serializesAbsentIconAsNull() throws Exception {
        SsoProvider provider = new SsoProvider("keycloak", "Keycloak", "/oauth2/authorization/keycloak");

        JsonNode json = mapper.readTree(mapper.writeValueAsString(provider));

        // The field is present (so clients can rely on its shape) and explicitly null.
        assertThat(json.has("iconUrl")).isTrue();
        assertThat(json.get("iconUrl").isNull()).isTrue();
    }

    @Test
    void roundTripsThroughTheAuthMethodsEnvelope() throws Exception {
        AuthMethods methods = new AuthMethods(false, List.of(
                new SsoProvider("keycloak", "Keycloak", "/oauth2/authorization/keycloak"),
                new SsoProvider("telegram", "Telegram", "/api/auth/telegram/start",
                        "/api/auth/telegram/logo.svg")),
                "/logout", "oidc");

        AuthMethods restored = mapper.readValue(mapper.writeValueAsString(methods), AuthMethods.class);

        assertThat(restored.providers()).extracting(SsoProvider::iconUrl)
                .containsExactly(null, "/api/auth/telegram/logo.svg");
    }

    @Test
    void roundTripsTheMonochromeFlagAndButtonLabel() throws Exception {
        SsoProvider provider = new SsoProvider("telegram", "Telegram", "/api/auth/telegram/start",
                "/api/auth/telegram/logo.svg", true, "Войти через Telegram");

        SsoProvider restored = mapper.readValue(mapper.writeValueAsString(provider), SsoProvider.class);

        assertThat(restored.monochrome()).isTrue();
        assertThat(restored.buttonLabel()).isEqualTo("Войти через Telegram");
    }
}
