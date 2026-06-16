package com.onec.ui;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configures the "update available" check: the running app periodically asks onec-cloud what the
 * latest published framework version is and surfaces a notice when it is newer than its own.
 *
 * <p>On by default and fail-silent — an unreachable cloud, an offline host, or an app whose own
 * version can't be determined simply yields no notice rather than an error or a wrong banner.
 */
@ConfigurationProperties(prefix = "onec.ui.update-check")
public class UpdateProperties {

    /** Master switch. When false no outbound call is ever made and the notice never appears. */
    private boolean enabled = true;

    /** The onec-cloud endpoint that announces the latest release (see onec-cloud's ReleaseController). */
    private String url = "https://cloud.onno.su/releases/v1/latest";

    /** How often to poll after the first check. Floored at 60s. */
    private Duration interval = Duration.ofHours(24);

    /** Delay before the first check, so startup is never blocked on a network round-trip. */
    private Duration initialDelay = Duration.ofMinutes(1);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Duration getInterval() {
        return interval;
    }

    public void setInterval(Duration interval) {
        this.interval = interval;
    }

    public Duration getInitialDelay() {
        return initialDelay;
    }

    public void setInitialDelay(Duration initialDelay) {
        this.initialDelay = initialDelay;
    }
}
