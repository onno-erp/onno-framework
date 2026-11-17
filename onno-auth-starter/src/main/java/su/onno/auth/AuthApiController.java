package su.onno.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import su.onno.auth.users.AccountUserDetails;
import su.onno.auth.spi.LoginMethod;
import su.onno.auth.spi.LoginRejectedException;
import su.onno.auth.users.UserAccounts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/auth")
class AuthApiController {

    /** Non-null only in the password modes; OIDC / resource-server modes have no password manager. */
    private final AuthenticationManager authenticationManager;
    /** Non-null only when remember-me is enabled (in-memory mode); issues/clears the persistent cookie. */
    private final RememberMeServices rememberMeServices;
    private final OnnoAuthProperties properties;
    /** Non-null only in database mode; records the last sign-in time. */
    private final UserAccounts userAccounts;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();
    /** Non-null only in the password modes: resolves the account an application login method names. */
    private final UserDetailsService userDetailsService;
    /** Application-defined login methods ({@link LoginMethod}) by id. */
    private final Map<String, LoginMethod> loginMethods;

    private static final Pattern METHOD_ID = Pattern.compile("[a-z0-9][a-z0-9-]*");

    AuthApiController(AuthenticationManager authenticationManager,
                      RememberMeServices rememberMeServices,
                      OnnoAuthProperties properties) {
        this(authenticationManager, rememberMeServices, properties, null);
    }

    AuthApiController(AuthenticationManager authenticationManager,
                      RememberMeServices rememberMeServices,
                      OnnoAuthProperties properties,
                      UserAccounts userAccounts) {
        this(authenticationManager, rememberMeServices, properties, userAccounts, null, List.of());
    }

    AuthApiController(AuthenticationManager authenticationManager,
                      RememberMeServices rememberMeServices,
                      OnnoAuthProperties properties,
                      UserAccounts userAccounts,
                      UserDetailsService userDetailsService,
                      List<LoginMethod> loginMethods) {
        this.authenticationManager = authenticationManager;
        this.rememberMeServices = rememberMeServices;
        this.properties = properties;
        this.userAccounts = userAccounts;
        this.userDetailsService = userDetailsService;
        this.loginMethods = indexLoginMethods(loginMethods);
    }

    private static Map<String, LoginMethod> indexLoginMethods(List<LoginMethod> methods) {
        Map<String, LoginMethod> byId = new LinkedHashMap<>();
        for (LoginMethod method : methods) {
            String id = method.id();
            if (id == null || !METHOD_ID.matcher(id).matches()) {
                throw new IllegalStateException("LoginMethod " + method.getClass().getName()
                        + " has an invalid id '" + id + "': use lower-case letters, digits and dashes");
            }
            if (byId.putIfAbsent(id, method) != null) {
                throw new IllegalStateException("Two LoginMethod beans share the id '" + id + "'");
            }
        }
        return Map.copyOf(byId);
    }

    @GetMapping("/me")
    AuthUser me(Authentication authentication) {
        AuthMode mode = authMode();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return AuthUser.anonymous(mode);
        }
        return AuthUser.from(authentication, mode);
    }

    @PostMapping("/login")
    ResponseEntity<AuthUser> login(@RequestBody LoginRequest body,
                                   HttpServletRequest request,
                                   HttpServletResponse response) {
        // OIDC / resource-server modes authenticate against Keycloak, not this endpoint. Tell the
        // SPA so it can route the user to the right place instead of failing silently.
        if (authenticationManager == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(AuthUser.anonymous(authMode()));
        }
        if (body == null || body.username() == null || body.password() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        try {
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));

            return ResponseEntity.ok(signIn(authentication, body.remember(), request, response));
        } catch (AuthenticationException ex) {
            // Bad credentials, an unknown user and a disabled account all answer the same 401, so
            // the endpoint does not reveal which accounts exist or are disabled.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    /**
     * The first step of an application {@link LoginMethod} — e.g. send a one-time code. Answers the
     * method's (non-secret) result, {@code 404} for an unknown method, and {@code 401} with the
     * method's message when it refuses.
     */
    @PostMapping("/login/{method}/challenge")
    ResponseEntity<Map<String, Object>> challenge(@PathVariable String method,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        LoginMethod loginMethod = loginMethods.get(method);
        if (loginMethod == null) {
            return ResponseEntity.notFound().build();
        }
        if (userDetailsService == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        try {
            Map<String, Object> result = loginMethod.challenge(body == null ? Map.of() : body);
            return ResponseEntity.ok(result == null ? Map.of() : result);
        } catch (LoginRejectedException ex) {
            return rejected(ex.getMessage());
        }
    }

    /**
     * Signs in through an application {@link LoginMethod}: the method verifies the posted
     * credentials and names the account; the session is then established exactly as for a
     * password login. An unknown, disabled or locked account answers the same {@code 401} as wrong
     * credentials. A {@code remember} boolean in the body opts out of remember-me like
     * {@link #login}.
     */
    @PostMapping("/login/{method}")
    ResponseEntity<?> loginWith(@PathVariable String method,
                                @RequestBody(required = false) Map<String, Object> body,
                                HttpServletRequest request,
                                HttpServletResponse response) {
        LoginMethod loginMethod = loginMethods.get(method);
        if (loginMethod == null) {
            return ResponseEntity.notFound().build();
        }
        // OIDC / resource-server: the identity provider owns sign-in and there is no local account
        // store to resolve the method's user against.
        if (userDetailsService == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(AuthUser.anonymous(authMode()));
        }
        Map<String, Object> credentials = body == null ? Map.of() : body;
        String username;
        try {
            username = loginMethod.authenticate(credentials);
        } catch (LoginRejectedException ex) {
            return rejected(ex.getMessage());
        }
        if (username == null || username.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        UserDetails user;
        try {
            user = userDetailsService.loadUserByUsername(username);
            new AccountStatusUserDetailsChecker().check(user);
        } catch (AuthenticationException ex) { // unknown (UsernameNotFound), disabled, locked
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                user, null, user.getAuthorities());
        Boolean remember = credentials.get("remember") instanceof Boolean b ? b : null;
        return ResponseEntity.ok(signIn(authentication, remember, request, response));
    }

    private static ResponseEntity<Map<String, Object>> rejected(String message) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(message == null || message.isBlank() ? Map.of() : Map.of("message", message));
    }

    /** Stores an authenticated principal as the session's security context, as every login path does. */
    private AuthUser signIn(Authentication authentication, Boolean rememberRequested,
                            HttpServletRequest request, HttpServletResponse response) {
        // This controller performs authentication outside Spring Security's login filters, so
        // their SessionFixationProtectionStrategy is not invoked for us. Rotate any session
        // established before authentication before storing the privileged security context.
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);

        // Persist the login beyond the session's idle timeout unless the caller opted out. The
        // remember-me cookie re-authenticates a later request whose session has lapsed, so the
        // user isn't bounced to the login screen on the next action after a parked tab.
        boolean remember = rememberRequested == null || rememberRequested;
        if (remember && rememberMeServices != null) {
            rememberMeServices.loginSuccess(request, response, authentication);
        }

        if (userAccounts != null) {
            userAccounts.recordLogin(authentication.getName());
        }
        return AuthUser.from(authentication, authMode());
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response,
                                Authentication authentication) {
        // Cancel the remember-me cookie first (while the request still carries it) so logout is
        // final — otherwise the persistent cookie would silently sign the user back in.
        if (rememberMeServices instanceof LogoutHandler logoutHandler) {
            logoutHandler.logout(request, response, authentication);
        }
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * The current session's CSRF token, for cookie-based modes ({@code in-memory} / {@code oidc}).
     * The token also rides in the {@code XSRF-TOKEN} cookie, which browser SPAs read directly — but
     * a native mobile client can't: iOS hides {@code Set-Cookie} from JS and there is no
     * {@code document.cookie}. This endpoint hands the token to such clients so they can echo it in
     * the {@code headerName} header on mutating requests. The {@link CsrfCookieFilter} has already
     * materialized the token (request attribute) by the time this runs. {@code token} is null in
     * {@code resource-server} mode, which is stateless and has CSRF disabled (bearer tokens aren't
     * CSRF-prone), so callers there don't need it.
     */
    @GetMapping("/csrf")
    CsrfTokenResponse csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token == null) {
            return new CsrfTokenResponse(null, null, null);
        }
        return new CsrfTokenResponse(token.getToken(), token.getHeaderName(), token.getParameterName());
    }

    private AuthMode authMode() {
        OnnoAuthProperties.Mode mode = properties.getMode();
        if (mode != OnnoAuthProperties.Mode.OIDC) {
            return new AuthMode(modeName(mode), null, null);
        }
        // OIDC: loginUrl is the authorization redirect; logoutUrl is the RP-initiated-logout
        // endpoint (a full-page GET that also ends the IdP SSO session). The other modes clear the
        // local session via POST /api/auth/logout and have neither.
        OnnoAuthProperties.ResolvedOidc oidc = properties.getOidc().resolved();
        String loginUrl = "/oauth2/authorization/" + oidc.registrationId();
        return new AuthMode(modeName(mode), loginUrl, oidc.logoutPath());
    }

    private static String modeName(OnnoAuthProperties.Mode mode) {
        return switch (mode) {
            case IN_MEMORY -> "in-memory";
            case DATABASE -> "database";
            case OIDC -> "oidc";
            case RESOURCE_SERVER -> "resource-server";
        };
    }

    /**
     * @param remember whether to issue a persistent remember-me cookie (in-memory mode). Null is
     *                 treated as {@code true} — remember by default; pass {@code false} to opt out.
     */
    record LoginRequest(String username, String password, Boolean remember) {
    }

    private record AuthMode(String mode, String loginUrl, String logoutUrl) {
    }

    /**
     * The session CSRF token and the names to submit it under, mirroring Spring's {@link CsrfToken}.
     *
     * @param token         the raw token value to echo back; null in resource-server (CSRF-off) mode
     * @param headerName    the request header to send the token in (default {@code X-XSRF-TOKEN})
     * @param parameterName the form-parameter alternative (default {@code _csrf})
     */
    record CsrfTokenResponse(String token, String headerName, String parameterName) {
    }

    /**
     * @param authenticated whether a session/principal is established
     * @param username      the authenticated principal name (empty when anonymous)
     * @param roles         granted authorities
     * @param mode          active auth backend: {@code in-memory}, {@code database}, {@code oidc},
     *                      or {@code resource-server}
     * @param loginUrl      where the SPA should send the user to log in; non-null only in OIDC mode
     * @param logoutUrl     full-page RP-initiated-logout endpoint; non-null only in OIDC mode
     * @param passwordChangeRequired the account signed in with a one-time password and must set a
     *                      new one (database mode); every other API call answers {@code 403} until it does
     */
    record AuthUser(boolean authenticated, String username, List<String> roles,
                    String mode, String loginUrl, String logoutUrl, boolean passwordChangeRequired) {
        static AuthUser anonymous(AuthMode mode) {
            return new AuthUser(false, "", List.of(), mode.mode(), mode.loginUrl(), mode.logoutUrl(), false);
        }

        static AuthUser from(Authentication authentication, AuthMode mode) {
            List<String> roles = authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .toList();
            boolean changeRequired = authentication.getPrincipal() instanceof AccountUserDetails account
                    && account.passwordChangeRequired();
            return new AuthUser(true, authentication.getName(), roles,
                    mode.mode(), mode.loginUrl(), mode.logoutUrl(), changeRequired);
        }
    }
}
