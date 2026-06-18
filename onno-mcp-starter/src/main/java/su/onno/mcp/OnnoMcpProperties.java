package su.onno.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the onno MCP server.
 *
 * <p>The server exposes the application's business model (catalogs, documents,
 * accumulation registers) as Model Context Protocol tools, generated generically
 * from the {@code MetadataRegistry} and enforced through the same
 * {@code UiAccessService} role model as the web UI.
 */
@ConfigurationProperties(prefix = "onno.mcp")
public class OnnoMcpProperties {

    /** Master switch. When false, no MCP transport, server, or tools are contributed. */
    private boolean enabled = true;

    /**
     * Servlet path the streamable-HTTP MCP transport is mounted at. MCP clients connect here.
     */
    private String endpoint = "/mcp";

    /** Expose write tools (create/update catalog and document records). */
    private boolean writesEnabled = true;

    /**
     * Expose posting tools (post/unpost a document, posting preview). Posting has ledger
     * side-effects, so this is gated separately from ordinary writes.
     */
    private boolean postingEnabled = true;

    /** Name advertised to MCP clients in the initialize handshake. */
    private String serverName = "onno";

    /** Version advertised to MCP clients. */
    private String serverVersion = "0.1.0";

    /**
     * Optional instructions string sent to clients describing how to use the server.
     * When blank, a sensible default is generated.
     */
    private String instructions = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public boolean isWritesEnabled() {
        return writesEnabled;
    }

    public void setWritesEnabled(boolean writesEnabled) {
        this.writesEnabled = writesEnabled;
    }

    public boolean isPostingEnabled() {
        return postingEnabled;
    }

    public void setPostingEnabled(boolean postingEnabled) {
        this.postingEnabled = postingEnabled;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public void setServerVersion(String serverVersion) {
        this.serverVersion = serverVersion;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }
}
