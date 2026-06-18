package su.onno.print;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "onno.print")
public class PrintProperties {

    /** Master switch for the print starter (PDF rendering endpoints and services). */
    private boolean enabled = true;

    /** Packages scanned for {@link PrintTemplate}. Defaults to the application's base packages. */
    private List<String> basePackages = new ArrayList<>();

    /** Character encoding used when rendering HTML templates. */
    private String encoding = "UTF-8";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getBasePackages() {
        return basePackages;
    }

    public void setBasePackages(List<String> basePackages) {
        this.basePackages = basePackages;
    }

    public String getEncoding() {
        return encoding;
    }

    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }
}
