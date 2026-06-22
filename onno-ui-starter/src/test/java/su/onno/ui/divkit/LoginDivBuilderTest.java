package su.onno.ui.divkit;

import su.onno.auth.spi.AuthMethods;
import su.onno.auth.spi.SsoProvider;
import su.onno.ui.UiMessages;
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
                "onno://auth/sso/keycloak?to=" + encode("/oauth2/authorization/keycloak"),
                "onno://auth/sso/telegram?to=" + encode("/api/auth/telegram/start"));
    }

    @Test
    void rendersAProviderBrandMarkOnTheRightWhenIconUrlIsPresent() {
        // Password enabled, so the SSO button is the ghost (secondary) variant whose label uses the
        // normal text color rather than the on-primary "page" color.
        AuthMethods methods = new AuthMethods(true, List.of(
                new SsoProvider("telegram", "Telegram", "/api/auth/telegram/start",
                        "/api/auth/telegram/logo.svg")),
                "/logout", "oidc");

        Map<String, Object> card = LoginDivBuilder.login(methods, Palette.of("light"));
        List<Map<String, Object>> icons = ssoIcons(card);

        assertThat(icons).singleElement().satisfies(icon -> {
            Map<String, Object> props = customProps(icon);
            assertThat(props.get("src")).isEqualTo("/api/auth/telegram/logo.svg");
            // Full color by default: the client shows the logo as-is, keeping its brand colors.
            assertThat(props.get("monochrome")).isEqualTo(false);
            // The button foreground is still carried (used only when monochrome), and a box size is set.
            assertThat(props.get("color")).isEqualTo(Palette.of("light").text());
            assertThat(icon).containsKey("width").containsKey("height");
        });
        // The mark is the last item in its button row ("on the right of the label").
        assertThat(iconIsAfterLabel(card)).isTrue();
    }

    @Test
    void monochromeProviderTintsTheMarkToTheButtonForeground() {
        // A password-less single provider is the primary button, so its foreground is the on-primary
        // "page" color; a monochrome mark is tinted to that so it reads on the accent fill.
        AuthMethods methods = new AuthMethods(false, List.of(
                new SsoProvider("acme", "Acme", "/oauth2/authorization/acme",
                        "/api/auth/acme/logo.svg", true, null)),
                "/logout", "oidc");

        Map<String, Object> icon = ssoIcons(LoginDivBuilder.login(methods, Palette.of("light"))).get(0);
        Map<String, Object> props = customProps(icon);

        assertThat(props.get("monochrome")).isEqualTo(true);
        assertThat(props.get("color")).isEqualTo(Palette.of("light").page());
    }

    @Test
    void buttonLabelOverridesTheContinueWithFramingVerbatim() {
        // An already-localized phrase is shown as-is, not re-wrapped into "Continue with Войти …".
        AuthMethods methods = new AuthMethods(false, List.of(
                new SsoProvider("telegram", "Telegram", "/api/auth/telegram/start",
                        "/api/auth/telegram/logo.svg", false, "Войти через Telegram")),
                "/logout", "oidc");

        List<String> texts = textValues(LoginDivBuilder.login(methods, Palette.of("light")));

        assertThat(texts).contains("Войти через Telegram");
        assertThat(texts).doesNotContain("Continue with Telegram");
    }

    /** True when an {@code onno-sso-icon} is the last item (after the text label) inside an SSO button row. */
    @SuppressWarnings("unchecked")
    private static boolean iconIsAfterLabel(Object node) {
        if (node instanceof Map<?, ?> map) {
            Object action = map.get("action");
            Object items = map.get("items");
            if (action instanceof Map<?, ?> a && String.valueOf(a.get("url")).startsWith("onno://auth/sso/")
                    && items instanceof List<?> list && list.size() >= 2) {
                Object last = list.get(list.size() - 1);
                return last instanceof Map<?, ?> m && "onno-sso-icon".equals(m.get("custom_type"));
            }
            for (Object v : ((Map<String, Object>) map).values()) {
                if (iconIsAfterLabel(v)) {
                    return true;
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object v : list) {
                if (iconIsAfterLabel(v)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Every {@code text} block value on the card, in document order. */
    @SuppressWarnings("unchecked")
    private static List<String> textValues(Object node) {
        List<String> out = new ArrayList<>();
        if (node instanceof Map<?, ?> map) {
            if ("text".equals(map.get("type")) && map.get("text") instanceof String s) {
                out.add(s);
            }
            for (Object v : ((Map<String, Object>) map).values()) {
                out.addAll(textValues(v));
            }
        } else if (node instanceof List<?> list) {
            for (Object v : list) {
                out.addAll(textValues(v));
            }
        }
        return out;
    }

    @Test
    void omitsTheIconWhenNoIconUrlIsSupplied() {
        AuthMethods methods = new AuthMethods(false, List.of(
                new SsoProvider("keycloak", "Keycloak", "/oauth2/authorization/keycloak")),
                "/logout", "oidc");

        assertThat(ssoIcons(LoginDivBuilder.login(methods, Palette.of("light")))).isEmpty();
    }

    @Test
    void rendersIconsGenericallyForAnyProviderThatSuppliesOneAndSkipsThoseThatDont() {
        AuthMethods methods = new AuthMethods(false, List.of(
                new SsoProvider("keycloak", "Keycloak", "/oauth2/authorization/keycloak"),
                new SsoProvider("telegram", "Telegram", "/api/auth/telegram/start",
                        "/api/auth/telegram/logo.svg"),
                new SsoProvider("github", "GitHub", "/oauth2/authorization/github",
                        "/api/auth/github/logo.svg")),
                "/logout", "oidc");

        List<Map<String, Object>> icons = ssoIcons(LoginDivBuilder.login(methods, Palette.of("light")));

        // Only the two providers that supplied an iconUrl get an icon — nothing is hardcoded to a id.
        assertThat(icons).extracting(LoginDivBuilderTest::customProps)
                .extracting(props -> props.get("src"))
                .containsExactly("/api/auth/telegram/logo.svg", "/api/auth/github/logo.svg");
    }

    // ---- Two-step picker: when both a password and SSO are offered, login splits into steps. ----

    @Test
    void whenPasswordAndSsoCoexistTheDefaultStepIsAPickerWithNoCredentialsForm() {
        AuthMethods methods = new AuthMethods(true, List.of(
                new SsoProvider("telegram", "Telegram", "/api/auth/telegram/start",
                        "/api/auth/telegram/logo.svg")),
                null, "in-memory");

        Map<String, Object> card = LoginDivBuilder.login(methods, Palette.of("light"));

        // The picker offers a "choose password" method button and the SSO button — but not the
        // credentials form, which is deferred to the next step.
        assertThat(actionUrls(card)).contains("onno://auth/password");
        assertThat(ssoActionUrls(card)).hasSize(1);
        assertThat(hasCustomType(card, "onno-login-form")).isFalse();
        assertThat(actionUrls(card)).doesNotContain("onno://auth/back");
    }

    @Test
    void thePasswordStepShowsTheFormBehindABackLinkAndHidesTheSsoButtons() {
        AuthMethods methods = new AuthMethods(true, List.of(
                new SsoProvider("telegram", "Telegram", "/api/auth/telegram/start",
                        "/api/auth/telegram/logo.svg")),
                null, "in-memory");

        Map<String, Object> card = LoginDivBuilder.login(
                methods, Palette.of("light"), UiMessages.defaults(), "password");

        assertThat(hasCustomType(card, "onno-login-form")).isTrue();
        assertThat(actionUrls(card)).contains("onno://auth/back");
        // The picker's method buttons are gone on the credentials step.
        assertThat(ssoActionUrls(card)).isEmpty();
        assertThat(actionUrls(card)).doesNotContain("onno://auth/password");
    }

    @Test
    void aPasswordOnlyScreenSkipsThePickerAndRendersTheFormInline() {
        AuthMethods methods = new AuthMethods(true, List.of(), null, "in-memory");

        // Even if a "password" step is requested, with no SSO there is no picker to step through.
        Map<String, Object> card = LoginDivBuilder.login(
                methods, Palette.of("light"), UiMessages.defaults(), "password");

        assertThat(hasCustomType(card, "onno-login-form")).isTrue();
        assertThat(actionUrls(card)).doesNotContain("onno://auth/password", "onno://auth/back");
    }

    @Test
    void anSsoOnlyScreenSkipsThePickerAndRendersTheButtonsInline() {
        AuthMethods methods = new AuthMethods(false, List.of(
                new SsoProvider("keycloak", "Keycloak", "/oauth2/authorization/keycloak")),
                "/logout", "oidc");

        Map<String, Object> card = LoginDivBuilder.login(methods, Palette.of("light"));

        assertThat(ssoActionUrls(card)).hasSize(1);
        assertThat(hasCustomType(card, "onno-login-form")).isFalse();
        assertThat(actionUrls(card)).doesNotContain("onno://auth/password", "onno://auth/back");
    }

    private static String encode(String url) {
        return URLEncoder.encode(url, StandardCharsets.UTF_8);
    }

    /** Whether any node on the card is a {@code div-custom} of the given {@code custom_type}. */
    @SuppressWarnings("unchecked")
    private static boolean hasCustomType(Object node, String type) {
        if (node instanceof Map<?, ?> map) {
            if (type.equals(map.get("custom_type"))) {
                return true;
            }
            for (Object v : ((Map<String, Object>) map).values()) {
                if (hasCustomType(v, type)) {
                    return true;
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object v : list) {
                if (hasCustomType(v, type)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Every {@code action.url} on the card, in document order (any scheme). */
    private static List<String> actionUrls(Object node) {
        List<String> urls = new ArrayList<>();
        collectActions(node, urls);
        return urls;
    }

    @SuppressWarnings("unchecked")
    private static void collectActions(Object node, List<String> urls) {
        if (node instanceof Map<?, ?> map) {
            Object action = map.get("action");
            if (action instanceof Map<?, ?> a && a.get("url") instanceof String url) {
                urls.add(url);
            }
            for (Object v : ((Map<String, Object>) map).values()) {
                collectActions(v, urls);
            }
        } else if (node instanceof List<?> list) {
            for (Object v : list) {
                collectActions(v, urls);
            }
        }
    }

    /** Every {@code onno-sso-icon} custom node on the card, in document order. */
    private static List<Map<String, Object>> ssoIcons(Object node) {
        List<Map<String, Object>> icons = new ArrayList<>();
        collectIcons(node, icons);
        return icons;
    }

    @SuppressWarnings("unchecked")
    private static void collectIcons(Object node, List<Map<String, Object>> icons) {
        if (node instanceof Map<?, ?> map) {
            if ("onno-sso-icon".equals(map.get("custom_type"))) {
                icons.add((Map<String, Object>) map);
            }
            for (Object v : ((Map<String, Object>) map).values()) {
                collectIcons(v, icons);
            }
        } else if (node instanceof List<?> list) {
            for (Object v : list) {
                collectIcons(v, icons);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> customProps(Map<String, Object> icon) {
        return (Map<String, Object>) icon.get("custom_props");
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
                    && url.startsWith("onno://auth/sso/")) {
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
