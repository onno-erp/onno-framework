package su.onno.ui;

import su.onno.auth.spi.AuthMethods;
import su.onno.auth.spi.AuthMethodsContributor;
import su.onno.auth.spi.AuthMethodsProvider;
import su.onno.auth.spi.SsoProvider;
import su.onno.ui.divkit.LoginDivBuilder;
import su.onno.ui.divkit.Palette;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Serves the server-driven (DivKit) login screen at {@code GET /api/divkit/login}. Public — it
 * renders before sign-in. The base methods come from the auth module via the
 * {@link AuthMethodsProvider} SPI (password flag, mode, logout URL, and its own SSO list); when no
 * provider bean is present (the auth starter is absent), it degrades to a password-only screen.
 *
 * <p>On top of the base, every {@link AuthMethodsContributor} bean adds its SSO options
 * <em>additively</em>, so a connector acting as an identity provider can surface a sign-in button
 * without displacing the base provider.
 */
@RestController
@RequestMapping("/api/divkit")
public class LoginDivController {

    private final ObjectProvider<AuthMethodsProvider> authMethods;
    private final ObjectProvider<AuthMethodsContributor> contributors;
    private final UiMessages messages;

    public LoginDivController(ObjectProvider<AuthMethodsProvider> authMethods,
                              ObjectProvider<AuthMethodsContributor> contributors,
                              UiMessages messages) {
        this.authMethods = authMethods;
        this.contributors = contributors;
        this.messages = messages;
    }

    @GetMapping("/login")
    public Map<String, Object> login(@RequestParam(required = false) String theme) {
        return LoginDivBuilder.login(resolveMethods(), Palette.of(theme), messages);
    }

    // Visible for testing.
    AuthMethods resolveMethods() {
        AuthMethods base = baseMethods();
        List<SsoProvider> contributed = contributedProviders();
        if (contributed.isEmpty()) {
            return base;
        }
        List<SsoProvider> providers = new ArrayList<>(base.providers());
        providers.addAll(contributed);
        return new AuthMethods(base.passwordEnabled(), providers, base.logoutUrl(), base.mode());
    }

    /**
     * The base methods from the single {@link AuthMethodsProvider}. Resolves through
     * {@code orderedStream().findFirst()} rather than {@code getIfAvailable()} so a stray second
     * provider bean never raises {@code NoUniqueBeanDefinitionException} at render time — the first
     * (ordered / {@code @Primary}) one wins. Falls back to a plain password screen when the auth
     * starter is absent.
     */
    private AuthMethods baseMethods() {
        AuthMethodsProvider provider = authMethods.orderedStream().findFirst().orElse(null);
        return provider != null ? provider.authMethods()
                : new AuthMethods(true, List.of(), null, "in-memory");
    }

    private List<SsoProvider> contributedProviders() {
        List<SsoProvider> result = new ArrayList<>();
        contributors.orderedStream().forEach(contributor -> {
            List<SsoProvider> ssoProviders = contributor.ssoProviders();
            if (ssoProviders != null) {
                result.addAll(ssoProviders);
            }
        });
        return result;
    }
}
