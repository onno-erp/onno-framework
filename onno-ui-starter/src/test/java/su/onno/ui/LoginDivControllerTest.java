package su.onno.ui;

import su.onno.auth.spi.AuthMethods;
import su.onno.auth.spi.AuthMethodsContributor;
import su.onno.auth.spi.AuthMethodsProvider;
import su.onno.auth.spi.SsoProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The login endpoint composes its methods from one base {@link AuthMethodsProvider} plus any number
 * of additive {@link AuthMethodsContributor}s — and must never throw when bean wiring is ambiguous,
 * since it renders the (public) login page.
 */
class LoginDivControllerTest {

    @Test
    void degradesToPasswordScreenWhenNoProviderIsPresent() {
        LoginDivController controller = new LoginDivController(provider(), contributor(), UiMessages.defaults(), BrandingConfig.defaults());

        AuthMethods m = controller.resolveMethods();

        assertThat(m.passwordEnabled()).isTrue();
        assertThat(m.mode()).isEqualTo("in-memory");
        assertThat(m.providers()).isEmpty();
    }

    @Test
    void loginCardMergesTheBrandingPalette() {
        // #191: the public login card must merge the app's brand palette like the authenticated shell.
        // Recolor the `text` slot (the card title uses p.text()) and prove it reaches the rendered card.
        BrandPalette brand = new BrandPalette(null, null, null, "#EA825D", null, null, null);
        BrandingConfig branding = new BrandingConfig(null, null, null, null, null, null, brand, brand);

        String branded = new LoginDivController(provider(), contributor(), UiMessages.defaults(), branding)
                .login("light", null).toString();
        String neutral = new LoginDivController(provider(), contributor(), UiMessages.defaults(), BrandingConfig.defaults())
                .login("light", null).toString();

        assertThat(branded).contains("#EA825D");        // brand color reaches the card
        assertThat(neutral).doesNotContain("#EA825D");  // unbranded card stays neutral
    }

    @Test
    void usesTheSoleProviderUnchangedWhenNoContributors() {
        AuthMethods base = new AuthMethods(false,
                List.of(new SsoProvider("keycloak", "Keycloak", "/oauth2/authorization/keycloak")),
                "/logout", "oidc");
        LoginDivController controller = new LoginDivController(provider(() -> base), contributor(), UiMessages.defaults(), BrandingConfig.defaults());

        assertThat(controller.resolveMethods()).isSameAs(base);
    }

    @Test
    void appendsContributorSsoProvidersAfterTheBaseAndKeepsTheScalarFields() {
        AuthMethodsProvider base = () -> new AuthMethods(false,
                List.of(new SsoProvider("keycloak", "Keycloak", "/oauth2/authorization/keycloak")),
                "/logout", "oidc");
        AuthMethodsContributor telegram = () ->
                List.of(new SsoProvider("telegram", "Telegram", "/api/auth/telegram/start"));

        AuthMethods m = new LoginDivController(provider(base), contributor(telegram), UiMessages.defaults(), BrandingConfig.defaults()).resolveMethods();

        // Scalar fields stay authoritative from the base provider.
        assertThat(m.passwordEnabled()).isFalse();
        assertThat(m.mode()).isEqualTo("oidc");
        assertThat(m.logoutUrl()).isEqualTo("/logout");
        // The contributed button is appended after the base's own SSO list.
        assertThat(m.providers()).extracting(SsoProvider::id).containsExactly("keycloak", "telegram");
        assertThat(m.providers()).extracting(SsoProvider::authorizationUrl)
                .containsExactly("/oauth2/authorization/keycloak", "/api/auth/telegram/start");
    }

    @Test
    void mergesMultipleContributorsOntoAPasswordOnlyBase() {
        AuthMethodsProvider base = () -> new AuthMethods(true, List.of(), null, "in-memory");
        AuthMethodsContributor telegram = () ->
                List.of(new SsoProvider("telegram", "Telegram", "/api/auth/telegram/start"));
        AuthMethodsContributor github = () ->
                List.of(new SsoProvider("github", "GitHub", "/oauth2/authorization/github"));

        AuthMethods m = new LoginDivController(provider(base), contributor(telegram, github), UiMessages.defaults(), BrandingConfig.defaults()).resolveMethods();

        assertThat(m.passwordEnabled()).isTrue();
        assertThat(m.providers()).extracting(SsoProvider::id).containsExactly("telegram", "github");
    }

    @Test
    void toleratesAContributorThatReturnsNull() {
        AuthMethodsProvider base = () -> new AuthMethods(true, List.of(), null, "in-memory");
        AuthMethodsContributor nullSafe = () -> null;
        AuthMethodsContributor real = () ->
                List.of(new SsoProvider("telegram", "Telegram", "/api/auth/telegram/start"));

        AuthMethods m = new LoginDivController(provider(base), contributor(nullSafe, real), UiMessages.defaults(), BrandingConfig.defaults()).resolveMethods();

        assertThat(m.providers()).extracting(SsoProvider::id).containsExactly("telegram");
    }

    @Test
    void doesNotThrowWhenMultipleProvidersAreRegisteredAndTakesTheFirst() {
        AuthMethodsProvider first = () -> new AuthMethods(true, List.of(), null, "first");
        AuthMethodsProvider second = () -> new AuthMethods(false, List.of(), null, "second");
        LoginDivController controller = new LoginDivController(provider(first, second), contributor(), UiMessages.defaults(), BrandingConfig.defaults());

        assertThatCode(controller::resolveMethods).doesNotThrowAnyException();
        assertThat(controller.resolveMethods().mode()).isEqualTo("first");
    }

    // --- Minimal ObjectProvider fakes: the controller only ever calls orderedStream(). ---

    @SafeVarargs
    private static ObjectProvider<AuthMethodsProvider> provider(AuthMethodsProvider... beans) {
        return ordered(beans);
    }

    @SafeVarargs
    private static ObjectProvider<AuthMethodsContributor> contributor(AuthMethodsContributor... beans) {
        return ordered(beans);
    }

    @SafeVarargs
    private static <T> ObjectProvider<T> ordered(T... beans) {
        List<T> list = List.of(beans);
        return new ObjectProvider<>() {
            @Override
            public Stream<T> orderedStream() {
                return list.stream();
            }

            @Override
            public Stream<T> stream() {
                return list.stream();
            }

            @Override
            public T getObject() {
                if (list.size() != 1) {
                    throw new UnsupportedOperationException("not used by the controller");
                }
                return list.get(0);
            }
        };
    }
}
