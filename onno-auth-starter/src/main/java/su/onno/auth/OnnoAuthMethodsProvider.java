package su.onno.auth;

import su.onno.auth.spi.AuthMethods;
import su.onno.auth.spi.AuthMethodsProvider;
import su.onno.auth.spi.SsoProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * Derives the available {@link AuthMethods} from the active {@link OnnoAuthProperties.Mode} and the
 * configured OIDC client registrations, so the UI module can render a server-driven login screen
 * without depending on this module or Spring Security.
 */
class OnnoAuthMethodsProvider implements AuthMethodsProvider {

    private final OnnoAuthProperties properties;
    private final ObjectProvider<ClientRegistrationRepository> clientRegistrations;

    OnnoAuthMethodsProvider(OnnoAuthProperties properties,
                            ObjectProvider<ClientRegistrationRepository> clientRegistrations) {
        this.properties = properties;
        this.clientRegistrations = clientRegistrations;
    }

    @Override
    public AuthMethods authMethods() {
        return switch (properties.getMode()) {
            case IN_MEMORY -> new AuthMethods(true, List.of(), null, "in-memory");
            case RESOURCE_SERVER -> new AuthMethods(false, List.of(), null, "resource-server");
            case OIDC -> {
                OnnoAuthProperties.ResolvedOidc oidc = properties.getOidc().resolved();
                yield new AuthMethods(false, ssoProviders(oidc), oidc.logoutPath(), "oidc");
            }
        };
    }

    /**
     * Enumerates SSO options. An {@link org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository}
     * is {@code Iterable<ClientRegistration>}, so every registration becomes a button; for any other
     * repository (not iterable) we fall back to the single preset/configured registration id.
     */
    private List<SsoProvider> ssoProviders(OnnoAuthProperties.ResolvedOidc oidc) {
        ClientRegistrationRepository repo = clientRegistrations.getIfAvailable();
        List<SsoProvider> providers = new ArrayList<>();
        if (repo instanceof Iterable<?> iterable) {
            for (Object entry : iterable) {
                if (entry instanceof ClientRegistration reg) {
                    providers.add(toProvider(reg.getRegistrationId(), reg.getClientName()));
                }
            }
        }
        if (providers.isEmpty()) {
            String id = oidc.registrationId();
            ClientRegistration reg = repo == null ? null : repo.findByRegistrationId(id);
            providers.add(toProvider(id, reg == null ? null : reg.getClientName()));
        }
        return providers;
    }

    private static SsoProvider toProvider(String id, String clientName) {
        // Spring sets clientName to the issuer URI under issuer-uri discovery, which makes a poor
        // button label — fall back to a humanized registration id unless a real name was set.
        boolean usable = clientName != null && !clientName.isBlank() && !clientName.startsWith("http");
        return new SsoProvider(id, usable ? clientName : humanize(id), "/oauth2/authorization/" + id);
    }

    private static String humanize(String id) {
        if (id == null || id.isBlank()) {
            return id;
        }
        String spaced = id.replace('-', ' ').replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
