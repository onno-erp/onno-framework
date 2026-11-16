package su.onno.auth;

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
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;

import su.onno.auth.spi.AuthMethodsProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OnnoAuthAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            // Mirror a single-node dev app: a blank remember-me key is allowed via the opt-in. Tests
            // that need the strict (multi-node) behaviour override this back to false.
            .withPropertyValues("onno.auth.session.remember-me.allow-ephemeral-key=true")
            .withConfiguration(AutoConfigurations.of(
                    WebMvcAutoConfiguration.class,
                    SecurityAutoConfiguration.class,
                    OnnoAuthAutoConfiguration.class));

    @Test
    void defaultsToInMemoryModeAndKeepsSessionChain() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("onnoSecurityFilterChain");
            assertThat(context).doesNotHaveBean("onnoOidcSecurityFilterChain");
            assertThat(context).doesNotHaveBean("onnoResourceServerSecurityFilterChain");
            assertThat(context).hasSingleBean(UserDetailsService.class);
            assertThat(context).hasSingleBean(SecurityFilterChain.class);
            assertThat(context).hasBean("onnoAuthApiController");
            assertThat(context).hasSingleBean(AuthMethodsProvider.class);
        });
    }

    @Test
    void oidcModeSelectsTheOauth2LoginChainAndDropsInMemoryUsers() {
        runner.withUserConfiguration(ClientRegistrationConfig.class)
                .withPropertyValues("onno.auth.mode=oidc")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("onnoOidcSecurityFilterChain");
                    assertThat(context).doesNotHaveBean("onnoSecurityFilterChain");
                    assertThat(context).doesNotHaveBean(UserDetailsService.class);
                    assertThat(context).hasSingleBean(SecurityFilterChain.class);
                    assertThat(context).hasBean("onnoAuthApiController");
                    assertThat(context).hasSingleBean(AuthMethodsProvider.class);
                });
    }

    @Test
    void resourceServerModeSelectsTheStatelessJwtChain() {
        runner.withUserConfiguration(JwtDecoderConfig.class)
                .withPropertyValues("onno.auth.mode=resource-server")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("onnoResourceServerSecurityFilterChain");
                    assertThat(context).doesNotHaveBean("onnoSecurityFilterChain");
                    assertThat(context).doesNotHaveBean(UserDetailsService.class);
                    assertThat(context).hasSingleBean(SecurityFilterChain.class);
                    assertThat(context).hasSingleBean(AuthMethodsProvider.class);
                });
    }

    @Test
    void defaultPublicPathsRemainAccessible() {
        runner.run(context -> {
            var properties = context.getBean(OnnoAuthProperties.class);
            assertThat(properties.getAdditionalPublicPaths()).isEmpty();
            assertThat(properties.effectivePublicPaths()).containsExactly(
                    "/error", "/api/theme", "/api/config", "/api/branding", "/api/auth/login",
                    "/api/auth/me", "/api/auth/csrf", "/api/ui/login",
                    "/api/desktop/ready", "/api/desktop/manifest");
            assertPublicPaths(context.getBean(SecurityFilterChain.class), properties.effectivePublicPaths());
        });
    }

    @Test
    void additionalAndLegacyPublicPathsPreserveBootstrapInEveryMode() {
        for (String mode : List.of("in-memory", "oidc", "resource-server")) {
            for (String property : List.of("additional-public-paths", "public-paths")) {
                runner.withUserConfiguration(ClientRegistrationConfig.class, JwtDecoderConfig.class)
                        .withPropertyValues("onno.auth.mode=" + mode,
                                "onno.auth." + property + "[0]=/api/public/**")
                        .run(context -> {
                            assertThat(context).hasNotFailed();
                            var chain = context.getBean(SecurityFilterChain.class);
                            assertPublicPaths(chain, List.of("/api/public/example", "/api/config", "/api/auth/login"));
                            var request = new MockHttpServletRequest("GET", "/api/private");
                            request.setServletPath("/api/private");
                            var response = new MockHttpServletResponse();
                            new FilterChainProxy(List.of(chain)).doFilter(request, response, new MockFilterChain());
                            assertThat(response.getStatus()).isEqualTo(401);
                        });
            }
        }
    }

    @Test
    void emptyLegacyPublicPathsCannotRemoveFrameworkPaths() {
        runner.withPropertyValues("onno.auth.public-paths=").run(context -> {
            var properties = context.getBean(OnnoAuthProperties.class);
            assertThat(properties.getPublicPaths()).isEmpty();
            assertPublicPaths(context.getBean(SecurityFilterChain.class), properties.effectivePublicPaths());
        });
    }

    @Test
    void effectivePublicPathsCombineBothPropertiesAndRemoveDuplicates() {
        runner.withPropertyValues("onno.auth.public-paths[0]=/api/legacy",
                        "onno.auth.additional-public-paths[0]=/api/extra",
                        "onno.auth.additional-public-paths[1]=/api/config")
                .run(context -> {
                    var paths = context.getBean(OnnoAuthProperties.class).effectivePublicPaths();
                    assertThat(paths).contains("/api/legacy", "/api/extra", "/api/config").doesNotHaveDuplicates();
                    assertPublicPaths(context.getBean(SecurityFilterChain.class), paths);
                });
    }

    private static void assertPublicPaths(SecurityFilterChain chain, List<String> paths) throws Exception {
        for (String path : paths) {
            var request = new MockHttpServletRequest(path.equals("/api/auth/login") ? "POST" : "GET", path);
            request.setServletPath(path);
            var response = new MockHttpServletResponse();
            var downstream = new MockFilterChain();
            new FilterChainProxy(List.of(chain)).doFilter(request, response, downstream);
            assertThat(downstream.getRequest()).as("Anonymous request to %s", path).isNotNull();
        }
    }

    @Test
    void csrfIgnoredPathsDefaultToLoginOnly() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(OnnoAuthProperties.class).getCsrfIgnoredPaths())
                    .containsExactly("/api/auth/login");
        });
    }

    @Test
    void csrfTokenEndpointIsPublicSoClientsCanBootstrapTheToken() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(OnnoAuthProperties.class).getPublicPaths())
                    .contains("/api/auth/csrf");
        });
    }

    @Test
    void csrfIgnoredPathsAreConfigurableAndTheChainStillBuilds() {
        runner.withPropertyValues(
                        "onno.auth.csrf-ignored-paths[0]=/api/auth/login",
                        "onno.auth.csrf-ignored-paths[1]=/api/public/**")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("onnoSecurityFilterChain");
                    assertThat(context.getBean(OnnoAuthProperties.class).getCsrfIgnoredPaths())
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
        runner.withPropertyValues("onno.auth.session.remember-me.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TokenBasedRememberMeServices.class);
            assertThat(context).hasBean("onnoSecurityFilterChain");
        });
    }

    @Test
    void demoAutoLoginAuthenticatesAnonymousApiRequestsAsConfiguredUser() {
        runner.withPropertyValues(
                        "onno.auth.users[0].username=manager@example.test",
                        "onno.auth.users[0].password=manager",
                        "onno.auth.users[0].roles[0]=MANAGER",
                        "onno.auth.demo.auto-login-username=manager@example.test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
                    request.setServletPath("/api/auth/me");
                    MockHttpServletResponse response = new MockHttpServletResponse();

                    new FilterChainProxy(List.of(context.getBean(SecurityFilterChain.class)))
                            .doFilter(request, response, new MockFilterChain());

                    var session = request.getSession(false);
                    assertThat(session).isNotNull();
                    SecurityContext saved = (SecurityContext) session.getAttribute(
                            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
                    assertThat(saved.getAuthentication().getName()).isEqualTo("manager@example.test");
                    assertThat(saved.getAuthentication().getAuthorities())
                            .extracting(Object::toString)
                            .containsExactly("ROLE_MANAGER");
                });
    }

    @Test
    void demoAutoLoginFailsFastWhenConfiguredUserDoesNotExist() {
        runner.withPropertyValues("onno.auth.demo.auto-login-username=missing@example.test")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().hasMessageContaining("missing@example.test");
                });
    }

    @Test
    void configuredFrameAncestorsUseCspAndRemoveDenyHeader() {
        runner.withPropertyValues(
                        "onno.auth.embedding.frame-ancestors[0]='self'",
                        "onno.auth.embedding.frame-ancestors[1]=https://landing.example",
                        "onno.auth.embedding.cross-site-cookies=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
                    request.setServletPath("/");
                    // Force CookieCsrfTokenRepository's raw Set-Cookie path. MockServlet 6's
                    // addCookie renderer drops the standard SameSite attribute that Tomcat keeps.
                    ((MockServletContext) request.getServletContext()).setMajorVersion(5);
                    MockHttpServletResponse response = new MockHttpServletResponse();

                    new FilterChainProxy(List.of(context.getBean(SecurityFilterChain.class)))
                            .doFilter(request, response, new MockFilterChain());

                    assertThat(response.getHeader("Content-Security-Policy"))
                            .isEqualTo("frame-ancestors 'self' https://landing.example");
                    assertThat(response.getHeader("X-Frame-Options")).isNull();
                    assertThat(response.getHeaders("Set-Cookie"))
                            .anyMatch(cookie -> cookie.startsWith("XSRF-TOKEN=")
                                    && cookie.contains("Secure") && cookie.contains("SameSite=None"));
                });
    }

    @Test
    void blankRememberMeKeyWithoutOptInFailsFast() {
        // Multi-node hazard: a per-node random key makes cookies non-portable. Refuse to start.
        runner.withPropertyValues("onno.auth.session.remember-me.allow-ephemeral-key=false").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context).getFailure().hasMessageContaining("remember-me.key");
        });
    }

    @Test
    void explicitRememberMeKeyIsHonoured() {
        runner.withPropertyValues("onno.auth.session.remember-me.key=a-stable-secret").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TokenBasedRememberMeServices.class);
        });
    }

    @Test
    void oidcModeHasNoRememberMeServices() {
        runner.withUserConfiguration(ClientRegistrationConfig.class)
                .withPropertyValues("onno.auth.mode=oidc")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TokenBasedRememberMeServices.class);
                });
    }

    @Test
    void sessionTimeoutCustomizerAppliesTheConfiguredIdleWindow() {
        runner.withPropertyValues("onno.auth.session.timeout=2h").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(applyTimeoutCustomizer(context.getBean(
                    "onnoSessionTimeoutCustomizer", WebServerFactoryCustomizer.class)))
                    .isEqualTo(Duration.ofHours(2));
        });
    }

    @Test
    void sessionTimeoutDefaultsToEightHours() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(applyTimeoutCustomizer(context.getBean(
                    "onnoSessionTimeoutCustomizer", WebServerFactoryCustomizer.class)))
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
        runner.withPropertyValues("onno.auth.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean("onnoSecurityFilterChain");
            assertThat(context).doesNotHaveBean("onnoAuthApiController");
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
