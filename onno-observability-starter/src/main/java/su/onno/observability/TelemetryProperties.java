package su.onno.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.Set;

@ConfigurationProperties(prefix = "onno.telemetry")
public class TelemetryProperties {

    /** Master switch. Disabled by default; onno-cloud enables it through injected environment variables. */
    private boolean enabled;

    /** Collector base URL, for example {@code https://cloud.onno.su}. */
    private String endpoint;

    /** Stable tenant slug understood by the collector. */
    private String tenant;

    /** Server-side bearer credential. Never exposed to the browser. */
    private String token;

    /** Immutable cloud deployment id attached to every batch. */
    private String deploymentId;

    /** Application/image version shown in deployment comparisons. */
    private String applicationVersion;

    /** onno-framework version used by this application. */
    private String frameworkVersion;

    /** Maximum number of events held in memory while the collector is unavailable. */
    private int queueCapacity = 2_000;

    /** Maximum events delivered in one HTTP request. */
    private int batchSize = 100;

    /** Delay between delivery attempts in milliseconds. */
    private long flushIntervalMs = 5_000;

    /** HTTP request timeout in milliseconds. */
    private long requestTimeoutMs = 10_000;

    /** Fraction of browser sessions captured, from 0 through 1. */
    private double browserSampleRate = 1.0;

    /** Low-cardinality dimension keys allowed to leave the application. */
    private Set<String> allowedDimensions = new LinkedHashSet<>(Set.of(
            "action", "component", "device", "entityKind", "entityName", "errorType", "method", "role",
            "channel", "format", "status"));

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getTenant() { return tenant; }
    public void setTenant(String tenant) { this.tenant = tenant; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getDeploymentId() { return deploymentId; }
    public void setDeploymentId(String deploymentId) { this.deploymentId = deploymentId; }
    public String getApplicationVersion() { return applicationVersion; }
    public void setApplicationVersion(String applicationVersion) { this.applicationVersion = applicationVersion; }
    public String getFrameworkVersion() { return frameworkVersion; }
    public void setFrameworkVersion(String frameworkVersion) { this.frameworkVersion = frameworkVersion; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public long getFlushIntervalMs() { return flushIntervalMs; }
    public void setFlushIntervalMs(long flushIntervalMs) { this.flushIntervalMs = flushIntervalMs; }
    public long getRequestTimeoutMs() { return requestTimeoutMs; }
    public void setRequestTimeoutMs(long requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }
    public double getBrowserSampleRate() { return browserSampleRate; }
    public void setBrowserSampleRate(double browserSampleRate) { this.browserSampleRate = browserSampleRate; }
    public Set<String> getAllowedDimensions() { return allowedDimensions; }
    public void setAllowedDimensions(Set<String> allowedDimensions) { this.allowedDimensions = allowedDimensions; }
}
