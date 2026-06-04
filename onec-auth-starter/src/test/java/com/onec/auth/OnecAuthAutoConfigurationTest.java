package com.onec.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
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

import com.onec.auth.spi.AuthMethodsProvider;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OnecAuthAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    WebMvcAutoConfiguration.class,
                    SecurityAutoConfiguration.class,
                    OnecAuthAutoConfiguration.class));

    @Test
    void defaultsToInMemoryModeAndKeepsSessionChain() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("oneCSecurityFilterChain");
            assertThat(context).doesNotHaveBean("oneCOidcSecurityFilterChain");
            assertThat(context).doesNotHaveBean("oneCResourceServerSecurityFilterChain");
            assertThat(context).hasSingleBean(UserDetailsService.class);
            assertThat(context).hasSingleBean(SecurityFilterChain.class);
            assertThat(context).hasBean("oneCAuthApiController");
            assertThat(context).hasSingleBean(AuthMethodsProvider.class);
        });
    }

    @Test
    void oidcModeSelectsTheOauth2LoginChainAndDropsInMemoryUsers() {
        runner.withUserConfiguration(ClientRegistrationConfig.class)
                .withPropertyValues("onec.auth.mode=oidc")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("oneCOidcSecurityFilterChain");
                    assertThat(context).doesNotHaveBean("oneCSecurityFilterChain");
                    assertThat(context).doesNotHaveBean(UserDetailsService.class);
                    assertThat(context).hasSingleBean(SecurityFilterChain.class);
                    assertThat(context).hasBean("oneCAuthApiController");
                    assertThat(context).hasSingleBean(AuthMethodsProvider.class);
                });
    }

    @Test
    void resourceServerModeSelectsTheStatelessJwtChain() {
        runner.withUserConfiguration(JwtDecoderConfig.class)
                .withPropertyValues("onec.auth.mode=resource-server")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("oneCResourceServerSecurityFilterChain");
                    assertThat(context).doesNotHaveBean("oneCSecurityFilterChain");
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
                    assertThat(context).hasBean("oneCSecurityFilterChain");
                    assertThat(context.getBean(OnecAuthProperties.class).getCsrfIgnoredPaths())
                            .containsExactly("/api/auth/login", "/api/public/**");
                });
    }

    @Test
    void disablingTheStarterContributesNoChain() {
        runner.withPropertyValues("onec.auth.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean("oneCSecurityFilterChain");
            assertThat(context).doesNotHaveBean("oneCAuthApiController");
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
