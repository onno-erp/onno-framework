package com.onec.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onec.auth.spi.AuthMethodsProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

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
@AutoConfiguration(before = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
@EnableConfigurationProperties(OnecAuthProperties.class)
@ConditionalOnProperty(prefix = "onec.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OnecAuthAutoConfiguration {

    /**
     * The {@code /api/auth/me} + login/logout controller, wired in every mode. Password login is
     * only honoured when an {@link AuthenticationManager} is present (in-memory mode); in OIDC and
     * resource-server modes that endpoint reports back that interactive password login is disabled.
     */
    @Bean
    AuthApiController oneCAuthApiController(ObjectProvider<AuthenticationManager> authenticationManager,
                                            OnecAuthProperties properties) {
        return new AuthApiController(authenticationManager.getIfAvailable(), properties);
    }

    /**
     * Describes the available login methods (mode, password, SSO providers, logout URL) for any
     * consumer that renders a server-driven login screen — chiefly the UI module, which depends on
     * the {@code com.onec.auth.spi} contract in {@code onec-framework} but not on this module.
     */
    @Bean
    @ConditionalOnMissingBean(AuthMethodsProvider.class)
    AuthMethodsProvider oneCAuthMethodsProvider(
            OnecAuthProperties properties,
            ObjectProvider<ClientRegistrationRepository> clientRegistrations) {
        return new OnecAuthMethodsProvider(properties, clientRegistrations);
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
        PasswordEncoder oneCPasswordEncoder() {
            return new BCryptPasswordEncoder();
        }

        @Bean
        @ConditionalOnMissingBean
        UserDetailsService oneCUserDetailsService(OnecAuthProperties properties, PasswordEncoder passwordEncoder) {
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
        AuthenticationManager oneCAuthenticationManager(UserDetailsService userDetailsService,
                                                        PasswordEncoder passwordEncoder) {
            DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
            provider.setUserDetailsService(userDetailsService);
            provider.setPasswordEncoder(passwordEncoder);
            return provider::authenticate;
        }

        @Bean
        @ConditionalOnMissingBean(SecurityFilterChain.class)
        SecurityFilterChain oneCSecurityFilterChain(HttpSecurity http, OnecAuthProperties properties)
                throws Exception {
            CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
            csrfHandler.setCsrfRequestAttributeName(null);

            applyApiAuthorization(http, properties);
            return http
                    .sessionManagement(session -> session
                            .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                    .csrf(csrf -> csrf
                            .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                            .csrfTokenRequestHandler(csrfHandler)
                            .ignoringRequestMatchers("/api/auth/login"))
                    .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                    .exceptionHandling(ex -> ex.authenticationEntryPoint(new JsonAuthenticationEntryPoint()))
                    .formLogin(form -> form.disable())
                    .httpBasic(basic -> basic.disable())
                    .logout(logout -> logout.disable())
                    .build();
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
        SecurityFilterChain oneCOidcSecurityFilterChain(HttpSecurity http,
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
                            .csrfTokenRequestHandler(csrfHandler))
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
        SecurityFilterChain oneCResourceServerSecurityFilterChain(HttpSecurity http,
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
            return Map.of();
        }
    }
}
