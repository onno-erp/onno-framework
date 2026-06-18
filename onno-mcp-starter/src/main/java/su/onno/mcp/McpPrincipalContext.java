package su.onno.mcp;

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
 * {@code UiAccessService} deny-by-default role checks apply unchanged.
 */
public class McpPrincipalContext implements McpTransportContextExtractor<HttpServletRequest> {

    /** Key under which the captured {@link Principal} is stored in the transport context. */
    public static final String PRINCIPAL_KEY = "onno.principal";

    @Override
    public McpTransportContext extract(HttpServletRequest request) {
        Map<String, Object> values = new HashMap<>();
        SecurityContext context = SecurityContextHolder.getContext();
        Authentication authentication = context == null ? null : context.getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal())) {
            values.put(PRINCIPAL_KEY, authentication);
        }
        return McpTransportContext.create(values);
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
