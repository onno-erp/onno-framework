package su.onno.auth.users;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.rememberme.AbstractRememberMeServices;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * HTTP surface of {@link UserAccounts} in {@code onno.auth.mode=database}: the holder's own
 * password change (any signed-in account) and account administration ({@code ADMIN} only).
 *
 * <pre>
 * GET   /api/auth/password-policy            the active policy (for the change-password form)
 * POST  /api/auth/password                   {currentPassword, newPassword} — change own password
 * GET   /api/auth/users                      list accounts                         (ADMIN)
 * POST  /api/auth/users                      {username, roles, password?} — create (ADMIN)
 * PATCH /api/auth/users/{id}                 {roles?, enabled?}                     (ADMIN)
 * POST  /api/auth/users/{id}/reset-password  issue a one-time password             (ADMIN)
 * POST  /api/auth/users/{id}/sign-out        end every session of the account      (ADMIN)
 * </pre>
 *
 * A generated one-time password is returned exactly once, in the response that created it.
 */
@RestController
@RequestMapping("/api/auth")
public class UserAccountsController {

    /** Spring Security's default remember-me cookie name, which the starter does not change. */
    private static final String REMEMBER_ME_COOKIE =
            AbstractRememberMeServices.SPRING_SECURITY_REMEMBER_ME_COOKIE_KEY;

    private final UserAccounts accounts;
    private final UserDetailsService userDetailsService;
    private final RememberMeServices rememberMeServices;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public UserAccountsController(UserAccounts accounts, UserDetailsService userDetailsService,
                                  RememberMeServices rememberMeServices) {
        this.accounts = accounts;
        this.userDetailsService = userDetailsService;
        this.rememberMeServices = rememberMeServices;
    }

    @GetMapping("/password-policy")
    PolicyView policy() {
        return new PolicyView(accounts.policy().minLength());
    }

    @PostMapping("/password")
    ResponseEntity<Void> changePassword(@RequestBody ChangePasswordRequest body, Authentication authentication,
                                        HttpServletRequest request, HttpServletResponse response) {
        if (!(authentication != null && authentication.getPrincipal() instanceof AccountUserDetails)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (body == null) {
            return ResponseEntity.badRequest().build();
        }
        accounts.changePassword(authentication.getName(), body.currentPassword(), body.newPassword());

        // The change moved the security stamp, which would sign this very session out on its next
        // request. Re-issue the session principal (and the remember-me cookie, if this browser
        // had one) under the new stamp so the holder stays signed in here — and only here.
        UserDetails fresh = userDetailsService.loadUserByUsername(authentication.getName());
        Authentication renewed = UsernamePasswordAuthenticationToken.authenticated(
                fresh, null, fresh.getAuthorities());
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(renewed);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        if (rememberMeServices != null && hasCookie(request, REMEMBER_ME_COOKIE)) {
            rememberMeServices.loginSuccess(request, response, renewed);
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users")
    List<AccountView> list(Authentication authentication) {
        requireAdmin(authentication);
        return accounts.list().stream().map(AccountView::of).toList();
    }

    @PostMapping("/users")
    ResponseEntity<CreatedView> create(@RequestBody CreateRequest body, Authentication authentication) {
        requireAdmin(authentication);
        if (body == null) {
            return ResponseEntity.badRequest().build();
        }
        if (body.password() == null || body.password().isEmpty()) {
            UserAccounts.Created created = accounts.createWithTemporaryPassword(body.username(), body.roles());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new CreatedView(AccountView.of(created.account()), created.temporaryPassword()));
        }
        // An administrator chose this password, so the holder still has to replace it.
        UserAccount account = accounts.create(body.username(), body.roles(), body.password(), true);
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreatedView(AccountView.of(account), null));
    }

    @PatchMapping("/users/{id}")
    AccountView update(@PathVariable UUID id, @RequestBody UpdateRequest body, Authentication authentication) {
        requireAdmin(authentication);
        UserAccount account = accounts.find(id).orElseThrow(() ->
                new UserAccountException(UserAccountException.Reason.NOT_FOUND, "Account not found."));
        if (body != null && body.roles() != null && !body.roles().equals(account.roles())) {
            account = accounts.setRoles(id, body.roles());
        }
        if (body != null && body.enabled() != null && body.enabled() != account.enabled()) {
            account = accounts.setEnabled(id, body.enabled());
        }
        return AccountView.of(account);
    }

    @PostMapping("/users/{id}/reset-password")
    TemporaryPasswordView resetPassword(@PathVariable UUID id, Authentication authentication) {
        requireAdmin(authentication);
        return new TemporaryPasswordView(accounts.resetPassword(id));
    }

    @PostMapping("/users/{id}/sign-out")
    AccountView signOut(@PathVariable UUID id, Authentication authentication) {
        requireAdmin(authentication);
        return AccountView.of(accounts.signOutEverywhere(id));
    }

    @ExceptionHandler(UserAccountException.class)
    ResponseEntity<Map<String, String>> refused(UserAccountException e) {
        HttpStatus status = switch (e.reason()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID -> HttpStatus.BAD_REQUEST;
            case CONFLICT -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(Map.of("error", e.reason().name().toLowerCase(),
                "message", e.getMessage()));
    }

    @ExceptionHandler(PasswordPolicy.InvalidPasswordException.class)
    ResponseEntity<Map<String, String>> weakPassword(PasswordPolicy.InvalidPasswordException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "password_policy", "message", e.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<Map<String, String>> forbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "forbidden", "message", "Only administrators can manage accounts."));
    }

    private static void requireAdmin(Authentication authentication) {
        boolean admin = authentication != null && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(("ROLE_" + UserAccounts.ADMIN_ROLE)::equals);
        if (!admin) {
            throw new ForbiddenException();
        }
    }

    private static boolean hasCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return true;
            }
        }
        return false;
    }

    static final class ForbiddenException extends RuntimeException {
        ForbiddenException() {
            super(null, null, false, false);
        }
    }

    record ChangePasswordRequest(String currentPassword, String newPassword) {
    }

    record CreateRequest(String username, Set<String> roles, String password) {
    }

    record UpdateRequest(Set<String> roles, Boolean enabled) {
    }

    record PolicyView(int minLength) {
    }

    record TemporaryPasswordView(String temporaryPassword) {
    }

    record CreatedView(AccountView account, String temporaryPassword) {
    }

    record AccountView(UUID id, String username, List<String> roles, boolean enabled,
                       boolean passwordChangeRequired, Instant createdAt, Instant passwordChangedAt,
                       Instant lastLoginAt) {
        static AccountView of(UserAccount a) {
            return new AccountView(a.id(), a.username(), a.roles().stream().sorted().toList(), a.enabled(),
                    a.passwordChangeRequired(), a.createdAt(), a.passwordChangedAt(), a.lastLoginAt());
        }
    }
}
