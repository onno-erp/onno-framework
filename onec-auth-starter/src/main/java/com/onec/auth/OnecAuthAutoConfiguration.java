package com.onec.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onec.auth.magic.JdbcMagicLinkTokenStore;
import com.onec.auth.magic.MagicLinkController;
import com.onec.auth.magic.MagicLinkSender;
import com.onec.auth.magic.MagicLinkService;
import com.onec.auth.magic.MagicLinkTokenStore;
import com.onec.auth.magic.MagicLinkUserLookup;
import com.onec.auth.magic.MailMagicLinkSender;
import com.onec.auth.magic.PropertiesMagicLinkUserLookup;
import com.onec.auth.spi.AuthMethodsProvider;
import com.onec.mail.MailService;
import org.jdbi.v3.core.Jdbi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.AbstractServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Auto-configures authentication for an Onec application. The active backend is chosen by
 * {@code onec.auth.mode} (see {@link OnecAuthProperties.Mode}); exactly one of the nested
 * configurations below contributes a {@link SecurityFilterChain}. All three keep the same
 * authorization model — {@code /api/**} requires authentication, everything else (the SPA shell)
 * is public — so swapping modes never changes which routes are protected.
 */
@AutoConfiguration(
        // Order after the beans the opt-in magic-link feature consumes via @ConditionalOnBean — the
        // DataSource (token store) and, when present, the mail starter's MailService (default sender) —
        // so those conditions see the beans instead of evaluating before they're registered. afterName
        // keeps the mail starter optional (tolerated when absent).
        after = DataSourceAutoConfiguration.class,
        afterName = "com.onec.mail.OnecMailAutoConfiguration",
        before = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
@EnableConfigurationProperties(OnecAuthProperties.class)
@ConditionalOnProperty(prefix = "onec.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OnecAuthAutoConfiguration {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(OnecAuthAutoConfiguration.class);

    /** Fixed non-secret key used only when {@code allow-ephemeral-key=true} (single-node/dev). */
    private static final String EPHEMERAL_REMEMBER_ME_KEY = "onec-dev-ephemeral-remember-me-key";

    /**
     * The {@code /api/auth/me} + login/logout controller, wired in every mode. Password login is
     * only honoured when an {@link AuthenticationManager} is present (in-memory mode); in OIDC and
     * resource-server modes that endpoint reports back that interactive password login is disabled.
     */
    @Bean
    AuthApiController onecAuthApiController(ObjectProvider<AuthenticationManager> authenticationManager,
                                            ObjectProvider<TokenBasedRememberMeServices> rememberMeServices,
                                            OnecAuthProperties properties) {
        return new AuthApiController(authenticationManager.getIfAvailable(),
                rememberMeServices.getIfAvailable(), properties);
    }

    /**
     * Describes the available login methods (mode, password, SSO providers, logout URL) for any
     * consumer that renders a server-driven login screen — chiefly the UI module, which depends on
     * the {@code com.onec.auth.spi} contract in {@code onec-framework} but not on this module.
     */
    @Bean
    @ConditionalOnMissingBean(AuthMethodsProvider.class)
    AuthMethodsProvider onecAuthMethodsProvider(
            OnecAuthProperties properties,
            ObjectProvider<ClientRegistrationRepository> clientRegistrations) {
        return new OnecAuthMethodsProvider(properties, clientRegistrations);
    }

    /**
     * Applies {@code onec.auth.session.timeout} to the servlet container, overriding Spring Boot's
     * 30-minute default so the cookie-based modes get a working-day idle window that slides on each
     * request. Inert in resource-server mode, which never creates a session. To use a different
     * value, set {@code onec.auth.session.timeout} (this customizer is the source of truth — it
     * runs after, and so wins over, {@code server.servlet.session.timeout}).
     */
    @Bean
    WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> onecSessionTimeoutCustomizer(
            OnecAuthProperties properties) {
        return factory -> {
            java.time.Duration timeout = properties.getSession().getTimeout();
            // Mutate only the timeout on the existing Session so any other server.servlet.session.*
            // configuration (cookie name, SameSite, …) is preserved. getSession() lives on the
            // concrete servlet factory, not the ConfigurableServletWebServerFactory interface.
            if (timeout != null && !timeout.isZero() && !timeout.isNegative()
                    && factory instanceof AbstractServletWebServerFactory servletFactory) {
                servletFactory.getSession().setTimeout(timeout);
            }
        };
    }

    private static void applyApiAuthorization(HttpSecurity http, OnecAuthProperties properties) throws Exception {
        String[] publicPaths = properties.getPublicPaths().toArray(String[]::new);
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers(publicPaths).permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll());
    }

    // ----------------------------------------------------------------------------------------
    // Mode: in-memory (default) — username/password against onec.auth.users, session cookie.
    // ----------------------------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "onec.auth", name = "mode", havingValue = "in-memory", matchIfMissing = true)
    static class InMemoryAuthConfiguration {

        @Bean
        @ConditionalOnMissingBean
        PasswordEncoder onecPasswordEncoder() {
            return new BCryptPasswordEncoder();
        }

        @Bean
        @ConditionalOnMissingBean
        UserDetailsService onecUserDetailsService(OnecAuthProperties properties, PasswordEncoder passwordEncoder) {
            List<UserDetails> users = properties.getUsers().stream()
                    .map(u -> {
                        if (u.getUsername() == null || u.getPassword() == null) {
                            throw new IllegalStateException(
                                    "onec.auth.users entry is missing username or password");
                        }
                        String[] roles = u.getRoles() == null ? new String[0]
                                : u.getRoles().toArray(String[]::new);
                        return (UserDetails) User.withUsername(u.getUsername())
                                .password(passwordEncoder.encode(u.getPassword()))
                                .roles(roles)
                                .build();
                    })
                    .toList();
            return new InMemoryUserDetailsManager(users);
        }

        @Bean
        @ConditionalOnMissingBean
        AuthenticationManager onecAuthenticationManager(UserDetailsService userDetailsService,
                                                        PasswordEncoder passwordEncoder) {
            DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
            provider.setUserDetailsService(userDetailsService);
            provider.setPasswordEncoder(passwordEncoder);
            return provider::authenticate;
        }

        /**
         * Persistent remember-me for in-memory mode. Issues a signed cookie on login (the
         * {@link AuthApiController} calls {@code loginSuccess}) and re-authenticates from it after
         * the session has expired, so an idle-past-timeout or reopened-browser tab is signed back
         * in silently instead of bouncing to the login screen. {@code alwaysRemember} is on because
         * the JSON login carries no form parameter to opt in per-request — the controller decides
         * whether to remember from the request body and only then issues the cookie.
         */
        @Bean
        @ConditionalOnMissingBean(TokenBasedRememberMeServices.class)
        @ConditionalOnProperty(prefix = "onec.auth.session.remember-me", name = "enabled",
                havingValue = "true", matchIfMissing = true)
        TokenBasedRememberMeServices onecRememberMeServices(UserDetailsService userDetailsService,
                                                            OnecAuthProperties properties) {
            OnecAuthProperties.RememberMe config = properties.getSession().getRememberMe();
            String key = config.getKey();
            if (key == null || key.isBlank()) {
                if (!config.isAllowEphemeralKey()) {
                    throw new IllegalStateException(
                            "onec.auth.session.remember-me.key must be set: a blank key makes the "
                                    + "framework sign remember-me cookies with a per-node secret that other "
                                    + "nodes reject, breaking login under a load balancer. Set a stable "
                                    + "secret, or set onec.auth.session.remember-me.allow-ephemeral-key=true "
                                    + "for single-node/dev, or disable remember-me with "
                                    + "onec.auth.session.remember-me.enabled=false.");
                }
                // Single-node/dev opt-in: a fixed (non-secret) key so cookies survive restarts —
                // not random, which would invalidate them on every boot.
                key = EPHEMERAL_REMEMBER_ME_KEY;
                log.warn("onec.auth.session.remember-me.key is not set; using a built-in non-secret dev "
                        + "key (allow-ephemeral-key=true). Set a stable secret for production.");
            }
            TokenBasedRememberMeServices services = new TokenBasedRememberMeServices(
                    key, userDetailsService, TokenBasedRememberMeServices.RememberMeTokenAlgorithm.SHA256);
            services.setTokenValiditySeconds((int) config.getValidity().toSeconds());
            services.setAlwaysRemember(true);
            return services;
        }

        @Bean
        @ConditionalOnMissingBean(SecurityFilterChain.class)
        SecurityFilterChain onecSecurityFilterChain(HttpSecurity http, OnecAuthProperties properties,
                                                    ObjectProvider<TokenBasedRememberMeServices> rememberMe)
                throws Exception {
            CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
            csrfHandler.setCsrfRequestAttributeName(null);

            applyApiAuthorization(http, properties);
            // Honour the remember-me cookie: its filter/provider re-authenticate a request whose
            // session has lapsed. The provider's key must match the one the cookie was signed with.
            TokenBasedRememberMeServices rms = rememberMe.getIfAvailable();
            if (rms != null) {
                http.rememberMe(rm -> rm.rememberMeServices(rms).key(rms.getKey()));
            }
            return http
                    .sessionManagement(session -> session
                            .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                    .csrf(csrf -> csrf
                            .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                            .csrfTokenRequestHandler(csrfHandler)
                            .ignoringRequestMatchers(properties.getCsrfIgnoredPaths().toArray(String[]::new)))
                    .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                    .exceptionHandling(ex -> ex.authenticationEntryPoint(new JsonAuthenticationEntryPoint()))
                    .formLogin(form -> form.disable())
                    .httpBasic(basic -> basic.disable())
                    .logout(logout -> logout.disable())
                    .build();
        }

        // ------------------------------------------------------------------------------------
        // Opt-in passwordless magic-link login. Nested inside the in-memory config so it only
        // activates in that mode (OIDC/resource-server delegate sign-in to the IdP); gated again
        // by onec.auth.magic-link.enabled. Tokens are persisted (JDBC) so links validate across a
        // horizontally-scaled deployment, and delivery defaults to the mail starter when present.
        // ------------------------------------------------------------------------------------
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnProperty(prefix = "onec.auth.magic-link", name = "enabled", havingValue = "true")
        static class MagicLinkConfiguration {

            @Bean
            @ConditionalOnBean(DataSource.class)
            @ConditionalOnMissingBean(MagicLinkTokenStore.class)
            MagicLinkTokenStore magicLinkTokenStore(DataSource dataSource) {
                JdbcMagicLinkTokenStore store = new JdbcMagicLinkTokenStore(Jdbi.create(dataSource));
                store.initSchema();
                return store;
            }

            @Bean
            @ConditionalOnMissingBean(MagicLinkUserLookup.class)
            MagicLinkUserLookup magicLinkUserLookup(OnecAuthProperties properties) {
                return new PropertiesMagicLinkUserLookup(properties);
            }

            @Bean
            @ConditionalOnMissingBean(MagicLinkService.class)
            MagicLinkService magicLinkService(MagicLinkUserLookup userLookup,
                                              ObjectProvider<MagicLinkTokenStore> tokenStore,
                                              ObjectProvider<MagicLinkSender> sender,
                                              UserDetailsService userDetailsService,
                                              OnecAuthProperties properties) {
                MagicLinkTokenStore store = tokenStore.getIfAvailable();
                if (store == null) {
                    throw new IllegalStateException(
                            "onec.auth.magic-link.enabled=true needs a DataSource so single-use tokens "
                                    + "can be persisted and validated across nodes. Configure a datasource, "
                                    + "or register your own MagicLinkTokenStore bean.");
                }
                MagicLinkSender resolvedSender = sender.getIfAvailable();
                if (resolvedSender == null) {
                    throw new IllegalStateException(
                            "onec.auth.magic-link.enabled=true needs a way to deliver the link. Add the "
                                    + "onec-mail-starter and configure a mail provider, or register your own "
                                    + "MagicLinkSender bean.");
                }
                return new MagicLinkService(userLookup, store, resolvedSender, userDetailsService, properties);
            }

            @Bean
            @ConditionalOnMissingBean(MagicLinkController.class)
            MagicLinkController magicLinkController(MagicLinkService service, OnecAuthProperties properties) {
                return new MagicLinkController(service, properties);
            }

            /** Mail-backed delivery — wired only when the mail starter is on the classpath and active. */
            @Configuration(proxyBeanMethods = false)
            @ConditionalOnClass(MailService.class)
            static class MailMagicLinkSenderConfiguration {

                @Bean
                @ConditionalOnBean(MailService.class)
                @ConditionalOnMissingBean(MagicLinkSender.class)
                MagicLinkSender mailMagicLinkSender(MailService mailService, OnecAuthProperties properties) {
                    return new MailMagicLinkSender(mailService, properties);
                }
            }
        }
    }

    // ----------------------------------------------------------------------------------------
    // Mode: oidc — server-side OpenID Connect authorization-code login (Keycloak), session cookie.
    // ----------------------------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ClientRegistration.class)
    @ConditionalOnProperty(prefix = "onec.auth", name = "mode", havingValue = "oidc")
    static class OidcLoginAuthConfiguration {

        @Bean
        @ConditionalOnMissingBean(SecurityFilterChain.class)
        SecurityFilterChain onecOidcSecurityFilterChain(HttpSecurity http,
                                                        OnecAuthProperties properties,
                                                        ClientRegistrationRepository clientRegistrations)
                throws Exception {
            CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
            csrfHandler.setCsrfRequestAttributeName(null);

            OnecAuthProperties.ResolvedOidc oidc = properties.getOidc().resolved();
            applyApiAuthorization(http, properties);
            return http
                    .sessionManagement(session -> session
                            .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                    .csrf(csrf -> csrf
                            .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                            .csrfTokenRequestHandler(csrfHandler)
                            .ignoringRequestMatchers(properties.getCsrfIgnoredPaths().toArray(String[]::new)))
                    .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                    .oauth2Login(oauth -> oauth
                            // Point at the SPA's own /login route. This suppresses Spring's
                            // default OAuth login-page generator (which would otherwise shadow the
                            // SPA at GET /login) and makes unauthenticated browser requests redirect
                            // to the server-driven DivKit login screen instead.
                            .loginPage("/login")
                            .userInfoEndpoint(userInfo -> userInfo
                                    .oidcUserService(oidcUserService(oidc))))
                    // RP-initiated logout: a full-page GET to the logout path clears the local
                    // session and then redirects the browser to the IdP's end-session endpoint
                    // (with id_token_hint), so signing out of the app also ends the IdP SSO
                    // session instead of silently re-authenticating on the next login. Spring's
                    // handler reads the end-session URL from the provider metadata and appends the
                    // id_token_hint from the current OidcUser. GET (rather than the CSRF-protected
                    // POST default) keeps the SPA trigger a simple navigation, symmetric with the
                    // login redirect; the only exposure is a forced sign-out, which is benign.
                    .logout(logout -> logout
                            .logoutRequestMatcher(new AntPathRequestMatcher(oidc.logoutPath(), "GET"))
                            .logoutSuccessHandler(oidcLogoutSuccessHandler(clientRegistrations, oidc)))
                    // /api/** answers 401 (the SPA redirects to the authorization endpoint itself);
                    // a browser hitting any other protected route is sent to Keycloak as usual.
                    .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                            new JsonAuthenticationEntryPoint(),
                            request -> {
                                String uri = request.getRequestURI();
                                return uri != null && uri.startsWith("/api/");
                            }))
                    .build();
        }

        /**
         * Redirects to the IdP's {@code end_session_endpoint} after the local session is cleared,
         * passing {@code post_logout_redirect_uri} so the browser lands back on the SPA shell. The
         * target must be registered as a valid post-logout redirect URI on the IdP client.
         */
        private OidcClientInitiatedLogoutSuccessHandler oidcLogoutSuccessHandler(
                ClientRegistrationRepository clientRegistrations, OnecAuthProperties.ResolvedOidc oidc) {
            OidcClientInitiatedLogoutSuccessHandler handler =
                    new OidcClientInitiatedLogoutSuccessHandler(clientRegistrations);
            handler.setPostLogoutRedirectUri(oidc.postLogoutRedirectUri());
            return handler;
        }

        /**
         * Loads the OIDC user, then layers IdP roles on top of the default scope authorities and
         * re-keys the principal name to the configured claim (e.g. {@code preferred_username}) so
         * the rest of the framework (e.g. the login→Employee identity link) sees a real username.
         */
        private OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService(
                OnecAuthProperties.ResolvedOidc oidc) {
            OidcUserService delegate = new OidcUserService();
            ClaimRoleConverter roleConverter = new ClaimRoleConverter(oidc);
            return userRequest -> {
                OidcUser user = delegate.loadUser(userRequest);

                Set<GrantedAuthority> authorities = new LinkedHashSet<>(user.getAuthorities());
                // Roles may live in the ID token / userinfo (Zitadel) or only in the access token
                // (Keycloak's default) — merge both so role mapping works regardless of the IdP and
                // any "add to ID token" mapper toggle.
                authorities.addAll(roleConverter.convert(user.getClaims()));
                authorities.addAll(roleConverter.convert(
                        decodeJwtClaims(userRequest.getAccessToken().getTokenValue())));

                String nameAttribute = oidc.principalClaim();
                if (user.getAttributes().get(nameAttribute) == null) {
                    log.warn("Configured principal claim '{}' is absent from the OIDC user; falling "
                            + "back to 'sub'. Check onec.auth.oidc.principal-claim.", nameAttribute);
                    nameAttribute = "sub";
                }
                return new DefaultOidcUser(authorities, user.getIdToken(), user.getUserInfo(), nameAttribute);
            };
        }
    }

    // ----------------------------------------------------------------------------------------
    // Mode: resource-server — stateless JWT bearer-token validation.
    // ----------------------------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(JwtAuthenticationConverter.class)
    @ConditionalOnProperty(prefix = "onec.auth", name = "mode", havingValue = "resource-server")
    static class ResourceServerAuthConfiguration {

        @Bean
        @ConditionalOnMissingBean(SecurityFilterChain.class)
        SecurityFilterChain onecResourceServerSecurityFilterChain(HttpSecurity http,
                                                                  OnecAuthProperties properties) throws Exception {
            applyApiAuthorization(http, properties);
            return http
                    // Bearer-token clients carry no cookies, so the session and CSRF machinery
                    // that protects the cookie-based modes is just overhead here.
                    .sessionManagement(session -> session
                            .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .csrf(csrf -> csrf.disable())
                    .oauth2ResourceServer(oauth -> oauth
                            .jwt(jwt -> jwt.jwtAuthenticationConverter(
                                    jwtAuthenticationConverter(properties.getOidc().resolved()))))
                    .exceptionHandling(ex -> ex.authenticationEntryPoint(new JsonAuthenticationEntryPoint()))
                    .formLogin(form -> form.disable())
                    .httpBasic(basic -> basic.disable())
                    .logout(logout -> logout.disable())
                    .build();
        }

        private JwtAuthenticationConverter jwtAuthenticationConverter(OnecAuthProperties.ResolvedOidc oidc) {
            ClaimRoleConverter roleConverter = new ClaimRoleConverter(oidc);
            JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
            converter.setPrincipalClaimName(oidc.principalClaim());
            converter.setJwtGrantedAuthoritiesConverter(jwt -> {
                Collection<GrantedAuthority> authorities = new LinkedHashSet<>(roleConverter.convert(jwt.getClaims()));
                // Preserve OAuth2 scopes as SCOPE_* authorities, matching Spring's default behaviour.
                Object scope = jwt.getClaim("scope");
                if (scope instanceof String s) {
                    for (String value : s.split(" ")) {
                        if (!value.isBlank()) {
                            authorities.add(new SimpleGrantedAuthority("SCOPE_" + value));
                        }
                    }
                }
                return authorities;
            });
            return converter;
        }
    }

    /**
     * Best-effort decode of a JWT's payload claims without verifying its signature — used only to
     * read role claims out of an already-trusted Keycloak access token in OIDC mode. Returns an
     * empty map for opaque (non-JWT) tokens or malformed input.
     */
    private static Map<String, Object> decodeJwtClaims(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return Map.of();
            }
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            @SuppressWarnings("unchecked")
            Map<String, Object> claims = new ObjectMapper().readValue(payload, Map.class);
            return claims;
        } catch (RuntimeException | IOException ex) {
            // Opaque (non-JWT) access token or malformed payload — no claims to merge. Logged at
            // debug so a genuinely broken token is diagnosable without noise on every opaque one.
            log.debug("Could not decode access-token claims ({}); treating as no extra claims.",
                    ex.getClass().getSimpleName());
            return Map.of();
        }
    }
}
