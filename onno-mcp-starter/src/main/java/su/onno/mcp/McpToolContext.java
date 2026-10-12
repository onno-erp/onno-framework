package su.onno.mcp;

import su.onno.access.AccessSubject;

import io.modelcontextprotocol.server.McpSyncServerExchange;

import java.security.Principal;

/**
 * Authenticated call context injectable into an {@link McpTool} method.
 *
 * <p>{@link #subject()} is the caller's record-scoped {@link AccessSubject}: pass it to the query
 * services or {@code RecordAccess} when the tool reads or changes data, so the tool obeys the same
 * record policies as the UI. A method may also declare an {@link AccessSubject} parameter directly.
 */
public record McpToolContext(Principal principal, McpSyncServerExchange exchange, AccessSubject subject) {

    /** Context predating {@link #subject()}: the subject is read from the exchange. */
    public McpToolContext(Principal principal, McpSyncServerExchange exchange) {
        this(principal, exchange, McpPrincipalContext.subject(exchange));
    }
}
