package su.onno.print;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings of {@code onno-print-starter}. */
@ConfigurationProperties(prefix = "onno.print")
public class OnnoPrintProperties {

    /**
     * Master switch for print forms. When false, no PrintService, print endpoints or record "Print"
     * actions are registered, even if PrintForm beans exist.
     */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
