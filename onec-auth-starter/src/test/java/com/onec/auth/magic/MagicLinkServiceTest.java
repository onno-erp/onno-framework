package com.onec.auth.magic;

import com.onec.auth.OnecAuthProperties;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MagicLinkServiceTest {

    /** A minimal in-memory token store so the service can be tested without a database. */
    private static final class InMemoryStore implements MagicLinkTokenStore {
        private record Entry(String username, Instant expiresAt, boolean consumed) {
        }

        private final Map<String, Entry> rows = new HashMap<>();

        @Override
        public void save(String tokenHash, String username, Instant expiresAt) {
            rows.put(tokenHash, new Entry(username, expiresAt, false));
        }

        @Override
        public Optional<String> consume(String tokenHash, Instant now) {
            Entry e = rows.get(tokenHash);
            if (e == null || e.consumed() || !e.expiresAt().isAfter(now)) {
                return Optional.empty();
            }
            rows.put(tokenHash, new Entry(e.username(), e.expiresAt(), true));
            return Optional.of(e.username());
        }
    }

    private static final class CapturingSender implements MagicLinkSender {
        private String email;
        private String link;
        private Duration validity;
        private int calls;

        @Override
        public void send(String email, String link, Duration validity) {
            this.email = email;
            this.link = link;
            this.validity = validity;
            this.calls++;
        }
    }

    private static OnecAuthProperties properties() {
        OnecAuthProperties p = new OnecAuthProperties();
        OnecAuthProperties.User u = new OnecAuthProperties.User();
        u.setUsername("alice");
        u.setPassword("ignored");
        u.setEmail("alice@example.com");
        u.setRoles(List.of("ADMIN"));
        p.setUsers(new ArrayList<>(List.of(u)));
        p.getMagicLink().setEnabled(true);
        return p;
    }

    private static MagicLinkService service(OnecAuthProperties p, MagicLinkTokenStore store, MagicLinkSender sender) {
        UserDetailsService uds = new InMemoryUserDetailsManager(
                User.withUsername("alice").password("{noop}ignored").roles("ADMIN").build());
        return new MagicLinkService(new PropertiesMagicLinkUserLookup(p), store, sender, uds, p);
    }

    @Test
    void requestEmailsLinkForKnownAddressThenVerifyRoundTrips() {
        OnecAuthProperties p = properties();
        InMemoryStore store = new InMemoryStore();
        CapturingSender sender = new CapturingSender();
        MagicLinkService service = service(p, store, sender);

        service.requestLink("alice@example.com", "https://app.example.com");

        assertThat(sender.calls).isEqualTo(1);
        assertThat(sender.email).isEqualTo("alice@example.com");
        assertThat(sender.validity).isEqualTo(p.getMagicLink().getTokenValidity());
        assertThat(sender.link).startsWith("https://app.example.com/api/auth/magic/verify?token=");

        String token = sender.link.substring(sender.link.indexOf("token=") + "token=".length());
        Optional<Authentication> authentication = service.verify(token);

        assertThat(authentication).isPresent();
        assertThat(authentication.get().getName()).isEqualTo("alice");
        assertThat(authentication.get().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_ADMIN");

        // Single use: the same link can't be redeemed twice.
        assertThat(service.verify(token)).isEmpty();
    }

    @Test
    void requestForUnknownAddressSendsNothingAndStoresNothing() {
        InMemoryStore store = new InMemoryStore();
        CapturingSender sender = new CapturingSender();

        service(properties(), store, sender).requestLink("nobody@example.com", "https://app.example.com");

        assertThat(sender.calls).isZero();
        assertThat(store.rows).isEmpty();
    }

    @Test
    void verifyRejectsBlankAndUnknownTokens() {
        MagicLinkService service = service(properties(), new InMemoryStore(), new CapturingSender());
        assertThat(service.verify(null)).isEmpty();
        assertThat(service.verify("   ")).isEmpty();
        assertThat(service.verify("not-a-real-token")).isEmpty();
    }

    @Test
    void configuredBaseUrlOverridesRequestOrigin() {
        OnecAuthProperties p = properties();
        p.getMagicLink().setBaseUrl("https://public.example.com/");
        CapturingSender sender = new CapturingSender();

        service(p, new InMemoryStore(), sender).requestLink("alice@example.com", "https://internal:8080");

        assertThat(sender.link).startsWith("https://public.example.com/api/auth/magic/verify?token=");
    }
}
