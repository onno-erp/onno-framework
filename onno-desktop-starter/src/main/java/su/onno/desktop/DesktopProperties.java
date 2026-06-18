package su.onno.desktop;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Infrastructure toggles for desktop mode. Behaviour (title, window, tray) lives
 * in a typed {@link DesktopApp} bean, per the config-as-code principle; only
 * environment/launch concerns are exposed as bound properties here.
 */
@ConfigurationProperties(prefix = "onno.desktop")
public class DesktopProperties {

    /** Whether the desktop endpoints and data relocation are active. */
    private boolean enabled = true;

    /**
     * Per-user data home the shell passes at launch (via {@code --onno.desktop.home}).
     * When set, an embedded H2 file datasource is relocated under {@code <home>/data}
     * so the database lives in the OS app-data directory rather than next to the
     * binary. Left unset during {@code bootRun}/dev, so normal runs are untouched.
     */
    private String home = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHome() {
        return home;
    }

    public void setHome(String home) {
        this.home = home;
    }
}
