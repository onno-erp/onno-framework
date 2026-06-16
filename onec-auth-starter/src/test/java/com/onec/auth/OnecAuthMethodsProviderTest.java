package com.onec.auth;

import com.onec.auth.spi.AuthMethods;
import com.onec.auth.spi.AuthMethodsProvider;
import com.onec.auth.spi.SsoProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OnecAuthMethodsProviderTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            // Single-node dev: allow the blank remember-me key via the opt-in (in-memory mode).
            .withPropertyValues("onec.auth.session.remember-me.allow-ephemeral-key=true")
            .withConfiguration(AutoConfigurations.of(
                    WebMvcAutoConfiguration.class,
                    SecurityAutoConfiguration.class,
                    OnecAuthAutoConfiguration.class));

    private static AuthMethods methods(org.springframework.context.ApplicationContext ctx) {
        return ctx.getBean(AuthMethodsProvider.class).authMethods();
    }

    @Test
    void inMemoryModeOffersPasswordAndNoProviders() {
        runner.run(context -> {
            AuthMethods m = methods(context);
            assertThat(m.mode()).isEqualTo("in-memory");
            assertThat(m.passwordEnabled()).isTrue();
            assertThat(m.magicLinkEnabled()).isFalse();
            assertThat(m.providers()).isEmpty();
            assertThat(m.logoutUrl()).isNull();
        });
    }

    @Test
    void oidcModeEnumeratesEveryRegistration() {
        runner.withUserConfiguration(TwoRegistrationsConfig.class)
                .withPropertyValues("onec.auth.mode=oidc")
                .run(context -> {
                    AuthMethods m = methods(context);
                    assertThat(m.mode()).isEqualTo("oidc");
                    assertThat(m.passwordEnabled()).isFalse();
                    assertThat(m.logoutUrl()).isEqualTo("/logout");
                    assertThat(m.providers()).extracting(SsoProvider::id)
                            .containsExactlyInAnyOrder("keycloak", "zitadel");
                    assertThat(m.providers()).extracting(SsoProvider::authorizationUrl)
                            .containsExactlyInAnyOrder(
                                    "/oauth2/authorization/keycloak",
                                    "/oauth2/authorization/zitadel");
                });
    }

    @Test
    void oidcModeFallsBackToConfiguredRegistrationWhenRepositoryIsNotIterable() {
        runner.withUserConfiguration(NonIterableRepoConfig.class)
                .withPropertyValues("onec.auth.mode=oidc")
                .run(context -> {
                    AuthMethods m = methods(context);
                    assertThat(m.providers()).singleElement().satisfies(p -> {
                        assertThat(p.id()).isEqualTo("keycloak");
                        assertThat(p.authorizationUrl()).isEqualTo("/oauth2/authorization/keycloak");
                    });
                });
    }

    @Test
    void resourceServerModeOffersNoInteractiveLogin() {
        runner.withUserConfiguration(JwtDecoderConfig.class)
                .withPropertyValues("onec.auth.mode=resource-server")
                .run(context -> {
                    AuthMethods m = methods(context);
                    assertThat(m.mode()).isEqualTo("resource-server");
                    assertThat(m.passwordEnabled()).isFalse();
                    assertThat(m.providers()).isEmpty();
                    assertThat(m.logoutUrl()).isNull();
                });
    }

    private static ClientRegistration registration(String id) {
        return ClientRegistration.withRegistrationId(id)
                .clientId(id + "-app")
                .clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid")
                .authorizationUri("https://idp.local/auth")
                .tokenUri("https://idp.local/token")
                .jwkSetUri("https://idp.local/certs")
                .userNameAttributeName("preferred_username")
                .build();
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoRegistrationsConfig {
        @Bean
        ClientRegistrationRepository clientRegistrationRepository() {
            return new InMemoryClientRegistrationRepository(registration("keycloak"), registration("zitadel"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NonIterableRepoConfig {
        // A bespoke (non-Iterable) repository: the methods provider can't enumerate it, so it must
        // fall back to the single configured/preset registration id.
        @Bean
        ClientRegistrationRepository clientRegistrationRepository() {
            ClientRegistration keycloak = registration("keycloak");
            return registrationId -> "keycloak".equals(registrationId) ? keycloak : null;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class JwtDecoderConfig {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "test")
                    .issuedAt(Instant.EPOCH)
                    .expiresAt(Instant.EPOCH.plusSeconds(60))
                    .claims(claims -> claims.putAll(Map.of()))
                    .build();
        }
    }
}
