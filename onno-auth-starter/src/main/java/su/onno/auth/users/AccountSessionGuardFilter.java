package su.onno.auth.users;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Keeps database-mode sessions honest. On every request carrying an {@link AccountUserDetails}
 * principal it re-reads the account and:
 * <ul>
 *   <li>signs the request out (session invalidated, remember-me cookie cancelled, {@code 401} on
 *       {@code /api/**}) when the account is gone, disabled, or its security stamp moved — so
 *       disabling an account, resetting its password, changing its roles or "sign out everywhere"
 *       takes effect on the very next request, not at session expiry;</li>
 *   <li>answers {@code 403 password_change_required} for API calls other than {@code /api/auth/**}
 *       and the public paths while a password change is pending, so a one-time password only opens
 *       the change-password screen.</li>
 * </ul>
 */
public class AccountSessionGuardFilter extends OncePerRequestFilter {

    private final UserStore store;
    private final LogoutHandler rememberMe;
    private final RequestMatcher api = new AntPathRequestMatcher("/api/**");
    private final RequestMatcher allowedWhileChangeRequired;

    /**
     * @param rememberMe  cancels the remember-me cookie on a forced sign-out; may be null
     * @param publicPaths Ant patterns reachable without a session (also reachable while a password
     *                    change is pending)
     */
    public AccountSessionGuardFilter(UserStore store, LogoutHandler rememberMe, List<String> publicPaths) {
        this.store = store;
        this.rememberMe = rememberMe;
        List<RequestMatcher> allowed = new ArrayList<>();
        allowed.add(new AntPathRequestMatcher("/api/auth/**"));
        publicPaths.forEach(p -> allowed.add(new AntPathRequestMatcher(p)));
        this.allowedWhileChangeRequired = new OrRequestMatcher(allowed);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AccountUserDetails principal)) {
            chain.doFilter(request, response);
            return;
        }
        Optional<UserAccount> current = store.findById(principal.accountId());
        if (current.isEmpty() || !current.get().enabled()
                || current.get().securityStamp() != principal.securityStamp()) {
            if (rememberMe != null) {
                rememberMe.logout(request, response, authentication);
            }
            SecurityContextHolder.clearContext();
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            if (api.matches(request)) {
                writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "session_revoked",
                        "Your session has ended. Sign in again.");
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        if (current.get().passwordChangeRequired() && api.matches(request)
                && !allowedWhileChangeRequired.matches(request)) {
            writeJson(response, HttpServletResponse.SC_FORBIDDEN, "password_change_required",
                    "Set a new password to continue.");
            return;
        }
        chain.doFilter(request, response);
    }

    private static void writeJson(HttpServletResponse response, int status, String error, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"" + error + "\",\"message\":\"" + message + "\"}");
    }
}
