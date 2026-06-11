package com.onec.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "onec.auth")
public class OnecAuthProperties {

    /**
     * Master switch for the auth starter. When false, no SecurityFilterChain is contributed
     * and the application can wire its own.
     */
    private boolean enabled = true;

    /**
     * Which authentication backend the starter wires. Selecting a mode only changes how
     * identities are authenticated — the {@code /api/**}-requires-auth model is the same
     * across all of them.
     *
     * <ul>
     *   <li>{@link Mode#IN_MEMORY} (default) — username/password against {@code onec.auth.users},
     *       session cookie, JSON {@code /api/auth/login}. Zero external dependencies.</li>
     *   <li>{@link Mode#OIDC} — server-side OpenID Connect authorization-code login against any
     *       standard provider (Keycloak, Zitadel, …). Keeps the session-cookie model; "login"
     *       becomes a redirect to {@code /oauth2/authorization/{registrationId}}. Configure the
     *       provider with the standard {@code spring.security.oauth2.client.*} properties.</li>
     *   <li>{@link Mode#RESOURCE_SERVER} — stateless bearer-token validation. The client obtains
     *       tokens from the IdP directly and sends {@code Authorization: Bearer ...}. Configure
     *       with {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}.</li>
     * </ul>
     */
    private Mode mode = Mode.IN_MEMORY;

    /**
     * OIDC settings — provider preset, role-claim mapping, and logout — shared by the
     * {@link Mode#OIDC} and {@link Mode#RESOURCE_SERVER} modes. Ignored in {@link Mode#IN_MEMORY}.
     */
    @NestedConfigurationProperty
    private Oidc oidc = new Oidc();

    /**
     * Public API/config endpoints permitted without authentication so the login screen can
     * render and authenticate. The SPA shell itself (everything outside {@code /api/**}) is
     * public by default; only {@code /api/**} requires a session.
     */
    private List<String> publicPaths = new ArrayList<>(List.of(
            "/error",
            "/api/theme",
            "/api/config",
            // Branding (app name, logo, favicon) the SPA applies before sign-in so the login
            // screen carries the consumer's brand. Non-sensitive, like theme/config.
            "/api/branding",
            "/api/auth/login",
            // Current auth state + the login methods to offer (mode, SSO loginUrl, logoutUrl). Must
            // be reachable unauthenticated so the SPA can decide what to render before sign-in;
            // returns only non-sensitive routing info (anonymous state when not logged in).
            "/api/auth/me",
            // The server-driven (DivKit) login screen. Public so it can render before sign-in.
            "/api/divkit/login",
            // Desktop shell liveness + window manifest. The native shell polls these
            // before any user can log in, so they must be reachable unauthenticated;
            // both are non-sensitive (readiness probe + window geometry/title).
            "/api/desktop/ready",
            "/api/desktop/manifest"));

    /**
     * Request paths exempted from CSRF protection in the cookie-based modes
     * ({@link Mode#IN_MEMORY} and {@link Mode#OIDC}). Defaults to just the login endpoint. Add a
     * path here to expose an anonymous, CSRF-free {@code POST} (e.g. a public lead/intake form)
     * without having to override the whole {@code SecurityFilterChain} (issue #30). Ant patterns
     * are supported (e.g. {@code /api/public/**}). Ignored in {@link Mode#RESOURCE_SERVER}, where
     * CSRF is already disabled.
     */
    private List<String> csrfIgnoredPaths = new ArrayList<>(List.of("/api/auth/login"));

    /**
     * In-memory user accounts. Empty by default — the consuming app supplies them via
     * {@code onec.auth.users[*]}. Production deployments should disable in-memory users
     * and configure their own UserDetailsService. Only used in {@link Mode#IN_MEMORY}.
     */
    private List<User> users = new ArrayList<>();

    /**
     * Session longevity and persistence for the cookie-based modes ({@link Mode#IN_MEMORY} and
     * {@link Mode#OIDC}). Controls the idle timeout and (in-memory only) remember-me. Ignored in
     * {@link Mode#RESOURCE_SERVER}, which is stateless.
     */
    @NestedConfigurationProperty
    private Session session = new Session();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public Oidc getOidc() {
        return oidc;
    }

    public void setOidc(Oidc oidc) {
        this.oidc = oidc;
    }

    public List<String> getPublicPaths() {
        return publicPaths;
    }

    public void setPublicPaths(List<String> publicPaths) {
        this.publicPaths = publicPaths;
    }

    public List<String> getCsrfIgnoredPaths() {
        return csrfIgnoredPaths;
    }

    public void setCsrfIgnoredPaths(List<String> csrfIgnoredPaths) {
        this.csrfIgnoredPaths = csrfIgnoredPaths;
    }

    public List<User> getUsers() {
        return users;
    }

    public void setUsers(List<User> users) {
        this.users = users;
    }

    public Session getSession() {
        return session;
    }

    public void setSession(Session session) {
        this.session = session;
    }

    public enum Mode {
        /** Username/password against {@code onec.auth.users}. */
        IN_MEMORY,
        /** Server-side OpenID Connect authorization-code login (session cookie). */
        OIDC,
        /** Stateless JWT bearer-token validation. */
        RESOURCE_SERVER
    }

    public static class User {
        private String username;
        private String password;
        private List<String> roles = new ArrayList<>();

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public List<String> getRoles() {
            return roles;
        }

        public void setRoles(List<String> roles) {
            this.roles = roles;
        }
    }

    /**
     * Session longevity for the cookie-based modes. The idle {@link #timeout} is applied to the
     * servlet container (overriding Spring Boot's 30-minute default) and slides on every request,
     * so an actively-used tab never expires mid-session. {@link #rememberMe} adds a persistent
     * login cookie in {@link Mode#IN_MEMORY} so a session that lapses (closed browser, idle past
     * the timeout) is silently re-established on the next request instead of bouncing to login.
     */
    public static class Session {

        /**
         * Idle session timeout for the cookie-based modes, applied to the servlet container. Slides
         * on each request. Defaults to 8 hours (a working day) instead of Spring's 30 minutes so
         * parked-but-open tabs don't silently lose their session. Ignored in
         * {@link Mode#RESOURCE_SERVER}.
         */
        private Duration timeout = Duration.ofHours(8);

        @NestedConfigurationProperty
        private RememberMe rememberMe = new RememberMe();

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public RememberMe getRememberMe() {
            return rememberMe;
        }

        public void setRememberMe(RememberMe rememberMe) {
            this.rememberMe = rememberMe;
        }
    }

    /**
     * Persistent "remember me" login for {@link Mode#IN_MEMORY}. When enabled, a successful
     * password login also issues a signed remember-me cookie; a request that arrives after the
     * session has expired is re-authenticated from that cookie (via Spring's
     * {@code RememberMeAuthenticationFilter}) for up to {@link #validity}, so the user stays signed
     * in across the idle timeout and browser restarts. Has no effect in the OIDC or
     * resource-server modes, where the IdP owns session continuity.
     */
    public static class RememberMe {

        /** Whether to issue and honour the persistent remember-me cookie. */
        private boolean enabled = true;

        /** How long the remember-me cookie stays valid. Defaults to 14 days. */
        private Duration validity = Duration.ofDays(14);

        /**
         * Secret that signs the remember-me cookie. Set a stable, non-guessable value in production
         * so cookies survive restarts and can't be forged. When blank, a random key is generated at
         * startup — remember-me still works within a single run, but every restart invalidates
         * outstanding cookies (and a warning is logged).
         */
        private String key;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getValidity() {
            return validity;
        }

        public void setValidity(Duration validity) {
            this.validity = validity;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }
    }

    /**
     * OIDC configuration for the {@link Mode#OIDC} and {@link Mode#RESOURCE_SERVER} modes. The
     * authentication plumbing is plain Spring Security OAuth2 — the only provider-specific concern
     * is how token claims map onto Spring authorities, which differs per IdP (Keycloak puts roles
     * under {@code realm_access.roles}; Zitadel under {@code urn:zitadel:iam:org:project:roles},
     * keyed by role name). A {@link Provider} preset fills sensible defaults; everything can be
     * overridden explicitly.
     */
    public static class Oidc {

        /** Provider preset that supplies default registration id, principal claim, and role sources. */
        private Provider provider = Provider.KEYCLOAK;

        /**
         * {@code spring.security.oauth2.client.registration.*} id used to build the login URL
         * ({@code /oauth2/authorization/{registrationId}}) surfaced to the SPA. Defaults from the
         * preset (e.g. {@code keycloak}, {@code zitadel}); required for {@link Provider#CUSTOM}.
         */
        private String registrationId;

        /** Token claim used as the authenticated principal name. Defaults from the preset. */
        private String principalClaim;

        /**
         * Path the SPA navigates to for RP-initiated logout in OIDC mode. A GET here clears the
         * local session and redirects to the IdP's end-session endpoint. Surfaced to the SPA via
         * {@code /api/auth/me} as {@code logoutUrl}.
         */
        private String logoutPath = "/logout";

        /**
         * Where the IdP sends the browser after ending its session. {@code {baseUrl}} expands to
         * the app's own origin (scheme://host:port), so the user lands back on the SPA shell. This
         * value must be registered under the IdP client's valid post-logout redirect URIs.
         */
        private String postLogoutRedirectUri = "{baseUrl}";

        /** How token claims map onto Spring Security authorities. */
        @NestedConfigurationProperty
        private Roles roles = new Roles();

        public Provider getProvider() {
            return provider;
        }

        public void setProvider(Provider provider) {
            this.provider = provider;
        }

        public String getRegistrationId() {
            return registrationId;
        }

        public void setRegistrationId(String registrationId) {
            this.registrationId = registrationId;
        }

        public String getPrincipalClaim() {
            return principalClaim;
        }

        public void setPrincipalClaim(String principalClaim) {
            this.principalClaim = principalClaim;
        }

        public String getLogoutPath() {
            return logoutPath;
        }

        public void setLogoutPath(String logoutPath) {
            this.logoutPath = logoutPath;
        }

        public String getPostLogoutRedirectUri() {
            return postLogoutRedirectUri;
        }

        public void setPostLogoutRedirectUri(String postLogoutRedirectUri) {
            this.postLogoutRedirectUri = postLogoutRedirectUri;
        }

        public Roles getRoles() {
            return roles;
        }

        public void setRoles(Roles roles) {
            this.roles = roles;
        }

        /**
         * Applies the {@link Provider} preset to produce the effective, fully-populated settings the
         * autoconfig and {@link ClaimRoleConverter} consume. Explicit values always win; preset
         * defaults only fill what was left null/empty. Validates configuration (e.g. Keycloak
         * client-roles without a client-id, or CUSTOM without a registration-id).
         */
        public ResolvedOidc resolved() {
            String regId = registrationId;
            String principal = principalClaim;
            List<RoleSource> sources = new ArrayList<>(roles.getSources());
            switch (provider) {
                case KEYCLOAK -> {
                    if (isBlank(regId)) regId = "keycloak";
                    if (isBlank(principal)) principal = "preferred_username";
                    if (sources.isEmpty()) {
                        if (roles.isRealmRoles()) {
                            sources.add(new RoleSource("realm_access", Shape.ARRAY));
                        }
                        if (roles.isClientRoles()) {
                            if (isBlank(roles.getClientId())) {
                                throw new IllegalStateException(
                                        "onec.auth.oidc.roles.client-roles is enabled but client-id is not set");
                            }
                            sources.add(new RoleSource("resource_access." + roles.getClientId(), Shape.ARRAY));
                        }
                    }
                }
                case ZITADEL -> {
                    if (isBlank(regId)) regId = "zitadel";
                    if (isBlank(principal)) principal = "preferred_username";
                    if (sources.isEmpty()) {
                        sources.add(new RoleSource("urn:zitadel:iam:org:project:roles", Shape.OBJECT_KEYS));
                    }
                }
                case CUSTOM -> {
                    if (isBlank(regId)) {
                        throw new IllegalStateException(
                                "onec.auth.oidc.provider=custom requires onec.auth.oidc.registration-id");
                    }
                    if (isBlank(principal)) principal = "preferred_username";
                }
            }
            return new ResolvedOidc(regId, principal, logoutPath, postLogoutRedirectUri,
                    roles.getPrefix(), List.copyOf(sources));
        }

        private static boolean isBlank(String s) {
            return s == null || s.isBlank();
        }
    }

    /** Provider presets. Each fills default registration id, principal claim, and role sources. */
    public enum Provider {
        /** Roles under {@code realm_access.roles} (+ optional {@code resource_access.<client>.roles}). */
        KEYCLOAK,
        /** Roles under {@code urn:zitadel:iam:org:project:roles}, keyed by role name. */
        ZITADEL,
        /** No defaults — supply {@code registration-id}, {@code principal-claim}, and {@code roles.sources}. */
        CUSTOM
    }

    /** How token claims map onto Spring Security authorities. */
    public static class Roles {

        /**
         * Explicit role sources. When empty, the {@link Provider} preset supplies defaults. Each
         * source names a claim and the shape of its value.
         */
        private List<RoleSource> sources = new ArrayList<>();

        /** Prefix prepended to each mapped role so {@code hasRole(..)} works (Spring convention). */
        private String prefix = "ROLE_";

        /** Keycloak preset: map realm-level roles ({@code realm_access.roles}). */
        private boolean realmRoles = true;

        /** Keycloak preset: also map client-level roles ({@code resource_access.<clientId>.roles}). */
        private boolean clientRoles = false;

        /** Keycloak preset: client id whose roles are mapped; required when {@code clientRoles} is true. */
        private String clientId;

        public List<RoleSource> getSources() {
            return sources;
        }

        public void setSources(List<RoleSource> sources) {
            this.sources = sources;
        }

        public String getPrefix() {
            return prefix;
        }

        public void setPrefix(String prefix) {
            this.prefix = prefix;
        }

        public boolean isRealmRoles() {
            return realmRoles;
        }

        public void setRealmRoles(boolean realmRoles) {
            this.realmRoles = realmRoles;
        }

        public boolean isClientRoles() {
            return clientRoles;
        }

        public void setClientRoles(boolean clientRoles) {
            this.clientRoles = clientRoles;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }
    }

    /**
     * One claim that carries roles, and the shape of its value. A mutable bean so Spring can bind
     * {@code onec.auth.oidc.roles.sources[*]} entries.
     */
    public static class RoleSource {

        /**
         * Claim path. Resolved as a literal top-level key first (so single keys that contain dots,
         * like Zitadel's {@code urn:zitadel:iam:org:project:roles}, work), then by dotted-path
         * walking of nested maps (e.g. {@code realm_access.roles}).
         */
        private String claim;

        /** The shape of the claim's value. */
        private Shape shape = Shape.ARRAY;

        public RoleSource() {
        }

        public RoleSource(String claim, Shape shape) {
            this.claim = claim;
            this.shape = shape;
        }

        public String getClaim() {
            return claim;
        }

        public void setClaim(String claim) {
            this.claim = claim;
        }

        public Shape getShape() {
            return shape;
        }

        public void setShape(Shape shape) {
            this.shape = shape;
        }
    }

    /** The shape of a role claim's value. */
    public enum Shape {
        /**
         * A JSON array of role strings, or a map containing a {@code roles} array (Keycloak's
         * {@code realm_access}/{@code resource_access.<client>} wrappers).
         */
        ARRAY,
        /** A JSON object whose keys are the role names (Zitadel's project-roles claim). */
        OBJECT_KEYS
    }

    /**
     * Effective OIDC settings after the {@link Provider} preset has been applied. Consumed by the
     * autoconfig (registration id / principal / logout) and {@link ClaimRoleConverter} (sources /
     * prefix).
     */
    public record ResolvedOidc(String registrationId, String principalClaim, String logoutPath,
                               String postLogoutRedirectUri, String rolePrefix,
                               List<RoleSource> roleSources) {
    }
}
