package su.onno.mcp;

import io.modelcontextprotocol.server.McpSyncServerExchange;

import java.security.Principal;

/**
 * Authenticated call context injectable into an {@link McpTool} method.
 */
public record McpToolContext(Principal principal, McpSyncServerExchange exchange) {
}
