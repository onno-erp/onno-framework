package su.onno.ui.collaboration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Top-level switch for the collaboration feature pack. */
@ConfigurationProperties(prefix = "onno.collaboration")
public class CollaborationProperties {

    /** Enables collaboration server APIs and their automatically registered React UI. */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
