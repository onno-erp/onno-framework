package su.onno.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/auth")
class AuthApiController {

    /** Non-null only in in-memory mode; OIDC / resource-server modes have no password manager. */
    private final AuthenticationManager authenticationManager;
    /** Non-null only when remember-me is enabled (in-memory mode); issues/clears the persistent cookie. */
    private final RememberMeServices rememberMeServices;
    private final OnnoAuthProperties properties;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    AuthApiController(AuthenticationManager authenticationManager,
                      RememberMeServices rememberMeServices,
                      OnnoAuthProperties properties) {
        this.authenticationManager = authenticationManager;
        this.rememberMeServices = rememberMeServices;
        this.properties = properties;
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

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            contextRepository.saveContext(context, request, response);

            // Persist the login beyond the session's idle timeout unless the caller opted out. The
            // remember-me cookie re-authenticates a later request whose session has lapsed, so the
            // user isn't bounced to the login screen on the next action after a parked tab.
            boolean remember = body.remember() == null || body.remember();
            if (remember && rememberMeServices != null) {
                rememberMeServices.loginSuccess(request, response, authentication);
            }

            return ResponseEntity.ok(AuthUser.from(authentication, authMode()));
        } catch (BadCredentialsException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
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
     * @param mode          active auth backend: {@code in-memory}, {@code oidc}, or {@code resource-server}
     * @param loginUrl      where the SPA should send the user to log in; non-null only in OIDC mode
     * @param logoutUrl     full-page RP-initiated-logout endpoint; non-null only in OIDC mode
     */
    record AuthUser(boolean authenticated, String username, List<String> roles,
                    String mode, String loginUrl, String logoutUrl) {
        static AuthUser anonymous(AuthMode mode) {
            return new AuthUser(false, "", List.of(), mode.mode(), mode.loginUrl(), mode.logoutUrl());
        }

        static AuthUser from(Authentication authentication, AuthMode mode) {
            List<String> roles = authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .toList();
            return new AuthUser(true, authentication.getName(), roles,
                    mode.mode(), mode.loginUrl(), mode.logoutUrl());
        }
    }
}
