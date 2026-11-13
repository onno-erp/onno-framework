package su.onno.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import su.onno.auth.users.AuthCodePurpose;
import su.onno.auth.users.DatabaseUsers;
import su.onno.auth.users.DuplicateLoginException;
import su.onno.auth.users.UserAccount;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The HTTP surface of {@code onno.auth.mode=database}, next to the shared {@code /api/auth/login}:
 * anonymous password-reset and one-time-code endpoints, a self-service password change, and the
 * {@code ADMIN}-only account administration under {@code /api/auth/users}.
 *
 * <p>Unlike the login endpoint, every {@code POST}/{@code PUT} here is CSRF-protected like the rest
 * of the API: read the token from the {@code XSRF-TOKEN} cookie (or {@code GET /api/auth/csrf}) and
 * echo it in {@code X-XSRF-TOKEN}.
 *
 * <p>The anonymous endpoints never tell an attacker whether a login exists. Requests always answer
 * {@code 202}; a failed redemption answers one generic error whatever the cause.
 */
@RestController
@RequestMapping("/api/auth")
class DatabaseAuthController {

    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    private final DatabaseUsers users;
    private final AuthApiController authApi;
    private final UserDetailsService userDetailsService;
    /** Non-null when remember-me is enabled; a code login then also issues the persistent cookie. */
    private final RememberMeServices rememberMeServices;
    private final OnnoAuthProperties.Database settings;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    DatabaseAuthController(DatabaseUsers users, AuthApiController authApi,
                           UserDetailsService userDetailsService, RememberMeServices rememberMeServices,
                           OnnoAuthProperties.Database settings) {
        this.users = users;
        this.authApi = authApi;
        this.userDetailsService = userDetailsService;
        this.rememberMeServices = rememberMeServices;
        this.settings = settings;
    }

    // ------------------------------------------------------------------ password reset

    @PostMapping("/password-reset/request")
    ResponseEntity<Void> requestPasswordReset(@RequestBody(required = false) LoginBody body) {
        if (!settings.isPasswordResetEnabled()) {
            return ResponseEntity.notFound().build();
        }
        if (body != null) {
            users.requestCode(body.username(), AuthCodePurpose.PASSWORD_RESET);
        }
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/password-reset/confirm")
    ResponseEntity<Map<String, String>> confirmPasswordReset(@RequestBody(required = false) ResetBody body) {
        if (!settings.isPasswordResetEnabled()) {
            return ResponseEntity.notFound().build();
        }
        if (body == null) {
            return invalidCode();
        }
        try {
            return users.resetPassword(body.username(), body.code(), body.password())
                    ? ResponseEntity.noContent().build()
                    : invalidCode();
        } catch (IllegalArgumentException weakPassword) {
            // Checked before the token is looked at, so it says nothing about the token or the login.
            return error(HttpStatus.BAD_REQUEST, "invalid_password", weakPassword.getMessage());
        }
    }

    // ------------------------------------------------------------------ one-time login code

    @PostMapping("/code/request")
    ResponseEntity<Void> requestLoginCode(@RequestBody(required = false) LoginBody body) {
        if (!settings.isCodeLoginEnabled()) {
            return ResponseEntity.notFound().build();
        }
        if (body != null) {
            users.requestCode(body.username(), AuthCodePurpose.LOGIN);
        }
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/code/login")
    ResponseEntity<?> loginWithCode(@RequestBody(required = false) CodeLoginBody body,
                                    HttpServletRequest request, HttpServletResponse response) {
        if (!settings.isCodeLoginEnabled()) {
            return ResponseEntity.notFound().build();
        }
        Optional<UserAccount> account = body == null ? Optional.empty()
                : users.redeemLoginCode(body.username(), body.code());
        if (account.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        UserDetails details;
        try {
            details = userDetailsService.loadUserByUsername(account.get().login());
        } catch (UsernameNotFoundException disabledMeanwhile) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                details, null, details.getAuthorities());

        // Same session handling as the password login: rotate a pre-authentication session id
        // (fixation), store the context, and optionally issue the remember-me cookie.
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        boolean remember = body.remember() == null || body.remember();
        if (remember && rememberMeServices != null) {
            rememberMeServices.loginSuccess(request, response, authentication);
        }
        return ResponseEntity.ok(authApi.me(authentication));
    }

    // ------------------------------------------------------------------ self-service

    @PostMapping("/password")
    ResponseEntity<Map<String, String>> changePassword(@RequestBody(required = false) ChangePasswordBody body,
                                                       Authentication authentication) {
        if (body == null) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request", "A request body is required");
        }
        try {
            return users.changePassword(authentication.getName(), body.currentPassword(), body.newPassword())
                    ? ResponseEntity.noContent().build()
                    : error(HttpStatus.FORBIDDEN, "wrong_password", "The current password is wrong");
        } catch (IllegalArgumentException weakPassword) {
            return error(HttpStatus.BAD_REQUEST, "invalid_password", weakPassword.getMessage());
        }
    }

    // ------------------------------------------------------------------ administration (ADMIN)

    @GetMapping("/users")
    ResponseEntity<?> listUsers(Authentication authentication) {
        if (!isAdmin(authentication)) {
            return forbidden();
        }
        return ResponseEntity.ok(users.list().stream().map(UserView::of).toList());
    }

    @PostMapping("/users")
    ResponseEntity<?> createUser(@RequestBody CreateUserBody body, Authentication authentication) {
        if (!isAdmin(authentication)) {
            return forbidden();
        }
        try {
            UserAccount created = users.create(body.username(), body.password(), body.roles());
            return ResponseEntity.status(HttpStatus.CREATED).body(UserView.of(created));
        } catch (DuplicateLoginException duplicate) {
            return error(HttpStatus.CONFLICT, "duplicate_login", duplicate.getMessage());
        } catch (IllegalArgumentException invalid) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request", invalid.getMessage());
        }
    }

    /** Partial update: only the fields present in the body change. */
    @PutMapping("/users/{id}")
    ResponseEntity<?> updateUser(@PathVariable UUID id, @RequestBody UpdateUserBody body,
                                 Authentication authentication) {
        if (!isAdmin(authentication)) {
            return forbidden();
        }
        Optional<UserAccount> existing = users.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // An administrator cannot lock themselves out (and, with a single admin, everyone else).
        boolean self = existing.get().login().equalsIgnoreCase(authentication.getName());
        if (self && (Boolean.FALSE.equals(body.enabled())
                || (body.roles() != null && !body.roles().contains("ADMIN")))) {
            return error(HttpStatus.CONFLICT, "self_lockout",
                    "You cannot disable your own account or remove your own ADMIN role");
        }
        try {
            if (body.roles() != null) {
                users.setRoles(id, body.roles());
            }
            if (body.password() != null) {
                users.setPassword(id, body.password());
            }
            if (body.enabled() != null) {
                users.setEnabled(id, body.enabled());
            }
        } catch (IllegalArgumentException invalid) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request", invalid.getMessage());
        }
        return ResponseEntity.ok(UserView.of(users.findById(id).orElseThrow()));
    }

    /** Sends the user a password-reset token through the {@code AuthCodeSender} (also an invite). */
    @PostMapping("/users/{id}/password-reset")
    ResponseEntity<?> sendPasswordReset(@PathVariable UUID id, Authentication authentication) {
        if (!isAdmin(authentication)) {
            return forbidden();
        }
        Optional<UserAccount> existing = users.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        users.requestCode(existing.get().login(), AuthCodePurpose.PASSWORD_RESET);
        return ResponseEntity.accepted().build();
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> ADMIN_AUTHORITY.equals(a.getAuthority()));
    }

    private static ResponseEntity<Map<String, String>> forbidden() {
        return error(HttpStatus.FORBIDDEN, "forbidden", "The ADMIN role is required");
    }

    private static ResponseEntity<Map<String, String>> invalidCode() {
        return error(HttpStatus.BAD_REQUEST, "invalid_code", "The code is invalid or has expired");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("error", code, "message", message));
    }

    record LoginBody(String username) {
    }

    record ResetBody(String username, String code, String password) {
    }

    /** @param remember null is treated as {@code true}, as on {@code /api/auth/login} */
    record CodeLoginBody(String username, String code, Boolean remember) {
    }

    record ChangePasswordBody(String currentPassword, String newPassword) {
    }

    record CreateUserBody(String username, String password, List<String> roles) {
    }

    record UpdateUserBody(Boolean enabled, List<String> roles, String password) {
    }

    /** An account as the admin API shows it — never the password hash. */
    record UserView(UUID id, String username, boolean enabled, List<String> roles, boolean hasPassword,
                    Instant createdAt) {
        static UserView of(UserAccount account) {
            return new UserView(account.id(), account.login(), account.enabled(),
                    account.roles().stream().sorted().toList(), account.hasPassword(), account.createdAt());
        }
    }
}
