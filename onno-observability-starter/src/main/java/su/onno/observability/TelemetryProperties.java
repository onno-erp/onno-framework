package su.onno.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.Set;

@ConfigurationProperties(prefix = "onno.telemetry")
public class TelemetryProperties {

    /** Master switch. Disabled by default; onno-cloud enables it through injected environment variables. */
    private boolean enabled;

    /** Fraction of browser sessions captured, from 0 through 1. */
    private double browserSampleRate = 1.0;

    /** Low-cardinality dimension keys allowed to leave the application. */
    private Set<String> allowedDimensions = new LinkedHashSet<>(Set.of(
            "action", "component", "device", "entityKind", "entityName", "errorType", "method", "role",
            "channel", "currency", "format", "status"));

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public double getBrowserSampleRate() { return browserSampleRate; }
    public void setBrowserSampleRate(double browserSampleRate) { this.browserSampleRate = browserSampleRate; }
    public Set<String> getAllowedDimensions() { return allowedDimensions; }
    public void setAllowedDimensions(Set<String> allowedDimensions) { this.allowedDimensions = allowedDimensions; }
}
