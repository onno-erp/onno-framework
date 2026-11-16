package su.onno.web;

import su.onno.auth.spi.AuthMethods;
import su.onno.auth.spi.AuthMethodsContributor;
import su.onno.auth.spi.AuthMethodsProvider;
import su.onno.auth.spi.SsoProvider;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Describes the login screen at {@code GET /api/ui/login} as plain JSON — which methods are
 * offered (password, SSO providers) and any one-tap demo accounts; the client renders it. Public —
 * it renders before sign-in. The base methods come from the auth module via the
 * {@link AuthMethodsProvider} SPI (password flag, mode, logout URL, and its own SSO list); when no
 * provider bean is present (the auth starter is absent), it degrades to a password-only screen.
 *
 * <p>On top of the base, every {@link AuthMethodsContributor} bean adds its SSO options
 * <em>additively</em>, so a connector acting as an identity provider can surface a sign-in button
 * without displacing the base provider.
 */
@RestController
@RequestMapping("/api/ui")
public class UiLoginController {

    private final ObjectProvider<AuthMethodsProvider> authMethods;
    private final ObjectProvider<AuthMethodsContributor> contributors;
    private final UiMessages messages;
    private final UiProperties properties;

    public UiLoginController(ObjectProvider<AuthMethodsProvider> authMethods,
                              ObjectProvider<AuthMethodsContributor> contributors,
                              UiMessages messages,
                              UiProperties properties) {
        this.authMethods = authMethods;
        this.contributors = contributors;
        this.messages = messages;
        this.properties = properties;
    }

    @GetMapping("/login")
    public Map<String, Object> login() {
        AuthMethods methods = resolveMethods();
        List<Map<String, Object>> providers = new ArrayList<>();
        for (SsoProvider provider : methods.providers()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", provider.id());
            // The button label: a provider's full, already-localized label verbatim when set, else
            // the login.sso framing around its name.
            p.put("label", provider.buttonLabel() != null && !provider.buttonLabel().isBlank()
                    ? provider.buttonLabel()
                    : messages.format("login.sso", "provider", provider.label()));
            // Where the browser goes to start this provider's flow (OIDC convention when not supplied).
            p.put("url", provider.authorizationUrl() != null && !provider.authorizationUrl().isBlank()
                    ? provider.authorizationUrl() : "/oauth2/authorization/" + provider.id());
            p.put("iconUrl", provider.iconUrl());
            p.put("monochrome", provider.monochrome());
            providers.add(p);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("passwordEnabled", methods.passwordEnabled());
        out.put("providers", providers);
        out.put("mode", methods.mode());
        out.put("demoAccounts", demoAccounts());
        return out;
    }

    /** The configured one-tap demo accounts as plain {label, username, password} maps for the login card. */
    private List<Map<String, Object>> demoAccounts() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (UiProperties.DemoAccount a : properties.getLogin().getDemoAccounts()) {
            out.add(Map.of("label", a.getLabel(), "username", a.getUsername(), "password", a.getPassword()));
        }
        return out;
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
