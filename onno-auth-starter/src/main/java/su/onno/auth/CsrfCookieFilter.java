package su.onno.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Materializes the deferred CSRF token on every request so the {@code XSRF-TOKEN} cookie is
 * present after the first response to a fresh client. Without this filter the cookie is only
 * written when something downstream reads the token.
 *
 * <p>Public so applications that wire their own {@link org.springframework.security.web.SecurityFilterChain}
 * can reuse it without copy-pasting (issue #30).
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token != null) {
            token.getToken();
        }
        filterChain.doFilter(request, response);
    }
}
