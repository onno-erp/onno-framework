package su.onno.mcp;

import su.onno.access.AccessSubject;
import su.onno.ui.AccessSubjectResolver;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

/**
 * Bridges the authenticated Spring Security principal into the MCP tool-call context.
 *
 * <p>The MCP servlet transport runs {@link #extract} on the servlet request thread,
 * <em>after</em> the Spring Security filter chain has populated
 * {@link SecurityContextHolder} for that request. We capture the {@link Authentication}
 * there and stash it in the {@link McpTransportContext}, which the SDK propagates into
 * the (possibly reactive) tool-call processing. Tool handlers then read it back via
 * {@link #principal(McpSyncServerExchange)} — never from the thread-local, which may not
 * survive the hop to a Reactor scheduler thread.
 *
 * <p>This makes every tool execute as the connecting user, so the existing
 * {@code UiAccessService} deny-by-default role checks apply unchanged. The user's full
 * {@link AccessSubject} (roles, identity record, contributed attributes) is resolved here too, on
 * the request thread, so every tool also runs inside the user's record scope.
 */
public class McpPrincipalContext implements McpTransportContextExtractor<HttpServletRequest> {

    /** Key under which the captured {@link Principal} is stored in the transport context. */
    public static final String PRINCIPAL_KEY = "onno.principal";

    /** Key under which the resolved {@link AccessSubject} is stored in the transport context. */
    public static final String SUBJECT_KEY = "onno.subject";

    private final AccessSubjectResolver subjects;

    public McpPrincipalContext() {
        this(null);
    }

    public McpPrincipalContext(AccessSubjectResolver subjects) {
        this.subjects = subjects;
    }

    @Override
    public McpTransportContext extract(HttpServletRequest request) {
        Map<String, Object> values = new HashMap<>();
        SecurityContext context = SecurityContextHolder.getContext();
        Authentication authentication = context == null ? null : context.getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal())) {
            values.put(PRINCIPAL_KEY, authentication);
            if (subjects != null) {
                values.put(SUBJECT_KEY, subjects.resolve(authentication));
            }
        }
        return McpTransportContext.create(values);
    }

    /**
     * The access subject captured for the current tool call. An anonymous call — or one made
     * without a subject resolver — yields a user with no roles (denied everything), never the
     * trusted {@link AccessSubject#system()} subject.
     */
    public static AccessSubject subject(McpSyncServerExchange exchange) {
        McpTransportContext context = exchange == null ? null : exchange.transportContext();
        Object value = context == null ? null : context.get(SUBJECT_KEY);
        if (value instanceof AccessSubject subject) {
            return subject;
        }
        Principal principal = principal(exchange);
        return AccessSubject.user(principal == null ? null : principal.getName(), rolesOf(principal));
    }

    private static java.util.Set<String> rolesOf(Principal principal) {
        java.util.Set<String> roles = new java.util.LinkedHashSet<>();
        if (principal instanceof Authentication auth) {
            auth.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        }
        return roles;
    }

    /**
     * Reads the authenticated principal captured for the current tool call, or {@code null}
     * when the request was anonymous. A {@code null} principal is denied everything by
     * {@code UiAccessService} (deny by default), which is the desired behavior.
     */
    public static Principal principal(McpSyncServerExchange exchange) {
        if (exchange == null) {
            return null;
        }
        McpTransportContext context = exchange.transportContext();
        if (context == null) {
            return null;
        }
        Object value = context.get(PRINCIPAL_KEY);
        return value instanceof Principal p ? p : null;
    }
}
