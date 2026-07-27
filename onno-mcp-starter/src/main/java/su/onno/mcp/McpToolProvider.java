package su.onno.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.List;

/**
 * Programmatic extension point for contributing tools to the onno MCP server.
 *
 * <p>Applications normally use {@link McpTool}; implement this interface when direct access to
 * the MCP Java SDK tool specification is needed.
 */
@FunctionalInterface
public interface McpToolProvider {

    List<SyncToolSpecification> tools();
}
