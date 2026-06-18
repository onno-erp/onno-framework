package su.onno.cluster.pg;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "onno.cluster")
public class OnnoClusterProperties {

    /**
     * Master switch for the cross-node event bus. When {@code false}, a local-only no-op bus is used
     * and live-UI (SSE) updates do not propagate between nodes.
     */
    private boolean enabled = true;

    /**
     * Postgres {@code LISTEN}/{@code NOTIFY} channel carrying cross-node entity-change notices. Must be
     * a bare identifier ({@code [A-Za-z0-9_]}); an invalid value falls back to the default.
     */
    private String channel = "onno_cluster_events";

    /**
     * Stable id identifying this node when filtering out its own {@code NOTIFY} echoes. Defaults to a
     * random per-JVM UUID; set it only if you want a deterministic id in logs.
     */
    private String nodeId;

    /**
     * How long the listener blocks waiting for notifications before looping to re-check for shutdown.
     * Bounds shutdown latency; does not affect delivery speed.
     */
    private Duration pollTimeout = Duration.ofSeconds(5);

    /** Upper bound on the exponential backoff between reconnect attempts after the listener drops. */
    private Duration reconnectBackoffMax = Duration.ofSeconds(30);

    /**
     * Soft cap (bytes) kept below Postgres's 8000-byte {@code NOTIFY} limit. A larger event first drops
     * its natural key, then degrades to a coarse "something changed" notice rather than failing.
     */
    private int maxPayloadBytes = 7000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public Duration getPollTimeout() {
        return pollTimeout;
    }

    public void setPollTimeout(Duration pollTimeout) {
        this.pollTimeout = pollTimeout;
    }

    public Duration getReconnectBackoffMax() {
        return reconnectBackoffMax;
    }

    public void setReconnectBackoffMax(Duration reconnectBackoffMax) {
        this.reconnectBackoffMax = reconnectBackoffMax;
    }

    public int getMaxPayloadBytes() {
        return maxPayloadBytes;
    }

    public void setMaxPayloadBytes(int maxPayloadBytes) {
        this.maxPayloadBytes = maxPayloadBytes;
    }
}
