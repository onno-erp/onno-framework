package su.onno.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import su.onno.auth.users.DatabaseUsers;

import java.io.IOException;

/**
 * Ends the session of a user whose account was disabled (or deleted) after they signed in. Without
 * it, "disable user" would only bite at the next login — up to a full session timeout later. The
 * account check is cached ({@code onno.auth.database.account-recheck-interval}), so it adds no query
 * to most requests. Runs before the anonymous filter: once the context is cleared the request simply
 * continues as anonymous and {@code /api/**} answers the usual {@code 401}.
 */
final class DatabaseAccountStatusFilter extends OncePerRequestFilter {

    private final DatabaseUsers users;

    DatabaseAccountStatusFilter(DatabaseUsers users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                && !users.isActive(authentication.getName())) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            SecurityContextHolder.clearContext();
        }
        chain.doFilter(request, response);
    }
}
