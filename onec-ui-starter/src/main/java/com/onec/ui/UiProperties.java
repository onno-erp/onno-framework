package com.onec.ui;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "onec.ui")
public class UiProperties {

    private boolean enabled = true;
    private String path = "/ui";
    private boolean readOnly = false;
    private Map<String, String> theme = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public Map<String, String> getTheme() {
        return theme;
    }

    public void setTheme(Map<String, String> theme) {
        this.theme = theme;
    }
}
