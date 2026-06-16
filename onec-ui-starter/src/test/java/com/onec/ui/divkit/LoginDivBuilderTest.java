package com.onec.ui.divkit;

import com.onec.auth.spi.AuthMethods;
import com.onec.auth.spi.SsoProvider;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SSO button action must carry each provider's {@code authorizationUrl} so the client redirects
 * there — the OIDC convention for an OIDC registration, but the provider's own start endpoint for an
 * additive non-OIDC contributor (e.g. a Telegram login flow).
 */
class LoginDivBuilderTest {

    @Test
    void ssoButtonActionCarriesTheAuthorizationUrlForEachProvider() {
        AuthMethods methods = new AuthMethods(false, List.of(
                new SsoProvider("keycloak", "Keycloak", "/oauth2/authorization/keycloak"),
                new SsoProvider("telegram", "Telegram", "/api/auth/telegram/start")),
                "/logout", "oidc");

        List<String> actions = ssoActionUrls(LoginDivBuilder.login(methods, Palette.of("light")));

        assertThat(actions).containsExactly(
                "onec://auth/sso/keycloak?to=" + encode("/oauth2/authorization/keycloak"),
                "onec://auth/sso/telegram?to=" + encode("/api/auth/telegram/start"));
    }

    private static String encode(String url) {
        return URLEncoder.encode(url, StandardCharsets.UTF_8);
    }

    /** Every {@code action.url} on the card that targets the SSO scheme, in document order. */
    private static List<String> ssoActionUrls(Object node) {
        List<String> urls = new ArrayList<>();
        collect(node, urls);
        return urls;
    }

    @SuppressWarnings("unchecked")
    private static void collect(Object node, List<String> urls) {
        if (node instanceof Map<?, ?> map) {
            Object action = map.get("action");
            if (action instanceof Map<?, ?> a && a.get("url") instanceof String url
                    && url.startsWith("onec://auth/sso/")) {
                urls.add(url);
            }
            for (Object v : ((Map<String, Object>) map).values()) {
                collect(v, urls);
            }
        } else if (node instanceof List<?> list) {
            for (Object v : list) {
                collect(v, urls);
            }
        }
    }
}
