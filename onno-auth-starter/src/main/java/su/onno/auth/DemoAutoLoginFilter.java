package su.onno.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates anonymous API requests as one configured in-memory user. This is intentionally a
 * server-side demo facility: no password is sent to the browser, and the normal RBAC checks still
 * see the configured user's authorities.
 */
final class DemoAutoLoginFilter extends OncePerRequestFilter {

    private final String username;
    private final List<GrantedAuthority> authorities;
    private final SecurityContextRepository contextRepository;

    DemoAutoLoginFilter(String username, UserDetailsService userDetailsService,
                        SecurityContextRepository contextRepository) {
        UserDetails user = userDetailsService.loadUserByUsername(username);
        this.username = user.getUsername();
        this.authorities = List.copyOf(user.getAuthorities());
        this.contextRepository = contextRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (current == null || !current.isAuthenticated()) {
            UsernamePasswordAuthenticationToken authentication =
                    UsernamePasswordAuthenticationToken.authenticated(username, null, authorities);
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            contextRepository.saveContext(context, request, response);
        }
        filterChain.doFilter(request, response);
    }
}
