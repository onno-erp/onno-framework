package com.onec.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;

import com.onec.auth.spi.AuthMethodsProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OnecAuthAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            // Mirror a single-node dev app: a blank remember-me key is allowed via the opt-in. Tests
            // that need the strict (multi-node) behaviour override this back to false.
            .withPropertyValues("onec.auth.session.remember-me.allow-ephemeral-key=true")
            .withConfiguration(AutoConfigurations.of(
                    WebMvcAutoConfiguration.class,
                    SecurityAutoConfiguration.class,
                    OnecAuthAutoConfiguration.class));

    @Test
    void defaultsToInMemoryModeAndKeepsSessionChain() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("onecSecurityFilterChain");
            assertThat(context).doesNotHaveBean("onecOidcSecurityFilterChain");
            assertThat(context).doesNotHaveBean("onecResourceServerSecurityFilterChain");
            assertThat(context).hasSingleBean(UserDetailsService.class);
            assertThat(context).hasSingleBean(SecurityFilterChain.class);
            assertThat(context).hasBean("onecAuthApiController");
            assertThat(context).hasSingleBean(AuthMethodsProvider.class);
        });
    }

    @Test
    void oidcModeSelectsTheOauth2LoginChainAndDropsInMemoryUsers() {
        runner.withUserConfiguration(ClientRegistrationConfig.class)
                .withPropertyValues("onec.auth.mode=oidc")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("onecOidcSecurityFilterChain");
                    assertThat(context).doesNotHaveBean("onecSecurityFilterChain");
                    assertThat(context).doesNotHaveBean(UserDetailsService.class);
                    assertThat(context).hasSingleBean(SecurityFilterChain.class);
                    assertThat(context).hasBean("onecAuthApiController");
                    assertThat(context).hasSingleBean(AuthMethodsProvider.class);
                });
    }

    @Test
    void resourceServerModeSelectsTheStatelessJwtChain() {
        runner.withUserConfiguration(JwtDecoderConfig.class)
                .withPropertyValues("onec.auth.mode=resource-server")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("onecResourceServerSecurityFilterChain");
                    assertThat(context).doesNotHaveBean("onecSecurityFilterChain");
                    assertThat(context).doesNotHaveBean(UserDetailsService.class);
                    assertThat(context).hasSingleBean(SecurityFilterChain.class);
                    assertThat(context).hasSingleBean(AuthMethodsProvider.class);
                });
    }

    @Test
    void csrfIgnoredPathsDefaultToLoginOnly() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(OnecAuthProperties.class).getCsrfIgnoredPaths())
                    .containsExactly("/api/auth/login");
        });
    }

    @Test
    void csrfIgnoredPathsAreConfigurableAndTheChainStillBuilds() {
        runner.withPropertyValues(
                        "onec.auth.csrf-ignored-paths[0]=/api/auth/login",
                        "onec.auth.csrf-ignored-paths[1]=/api/public/**")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("onecSecurityFilterChain");
                    assertThat(context.getBean(OnecAuthProperties.class).getCsrfIgnoredPaths())
                            .containsExactly("/api/auth/login", "/api/public/**");
                });
    }

    @Test
    void inMemoryModeEnablesRememberMeByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TokenBasedRememberMeServices.class);
        });
    }

    @Test
    void rememberMeCanBeDisabledAndTheChainStillBuilds() {
        runner.withPropertyValues("onec.auth.session.remember-me.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TokenBasedRememberMeServices.class);
            assertThat(context).hasBean("onecSecurityFilterChain");
        });
    }

    @Test
    void blankRememberMeKeyWithoutOptInFailsFast() {
        // Multi-node hazard: a per-node random key makes cookies non-portable. Refuse to start.
        runner.withPropertyValues("onec.auth.session.remember-me.allow-ephemeral-key=false").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context).getFailure().hasMessageContaining("remember-me.key");
        });
    }

    @Test
    void explicitRememberMeKeyIsHonoured() {
        runner.withPropertyValues("onec.auth.session.remember-me.key=a-stable-secret").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TokenBasedRememberMeServices.class);
        });
    }

    @Test
    void oidcModeHasNoRememberMeServices() {
        runner.withUserConfiguration(ClientRegistrationConfig.class)
                .withPropertyValues("onec.auth.mode=oidc")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TokenBasedRememberMeServices.class);
                });
    }

    @Test
    void sessionTimeoutCustomizerAppliesTheConfiguredIdleWindow() {
        runner.withPropertyValues("onec.auth.session.timeout=2h").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(applyTimeoutCustomizer(context.getBean(
                    "onecSessionTimeoutCustomizer", WebServerFactoryCustomizer.class)))
                    .isEqualTo(Duration.ofHours(2));
        });
    }

    @Test
    void sessionTimeoutDefaultsToEightHours() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(applyTimeoutCustomizer(context.getBean(
                    "onecSessionTimeoutCustomizer", WebServerFactoryCustomizer.class)))
                    .isEqualTo(Duration.ofHours(8));
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Duration applyTimeoutCustomizer(WebServerFactoryCustomizer customizer) {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        ((WebServerFactoryCustomizer<ConfigurableServletWebServerFactory>) customizer).customize(factory);
        return factory.getSession().getTimeout();
    }

    @Test
    void disablingTheStarterContributesNoChain() {
        runner.withPropertyValues("onec.auth.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean("onecSecurityFilterChain");
            assertThat(context).doesNotHaveBean("onecAuthApiController");
            assertThat(context).doesNotHaveBean(AuthMethodsProvider.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class ClientRegistrationConfig {
        @Bean
        ClientRegistrationRepository clientRegistrationRepository() {
            ClientRegistration keycloak = ClientRegistration.withRegistrationId("keycloak")
                    .clientId("rentals-app")
                    .clientSecret("secret")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .scope("openid")
                    .authorizationUri("https://keycloak.local/auth")
                    .tokenUri("https://keycloak.local/token")
                    .jwkSetUri("https://keycloak.local/certs")
                    .userNameAttributeName("preferred_username")
                    .build();
            return new InMemoryClientRegistrationRepository(keycloak);
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
