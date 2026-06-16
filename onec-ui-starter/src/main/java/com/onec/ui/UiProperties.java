package com.onec.ui;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "onec.ui")
public class UiProperties {

    /** Master switch for the UI starter. Also gated on a {@code MetadataRegistry} bean being present. */
    private boolean enabled = true;

    /** SPA base path, returned as {@code basePath} from {@code GET /api/config}. */
    private String path = "/ui";

    /** When true, every mutating REST call is rejected with {@code 403 UI is in read-only mode}. */
    private boolean readOnly = false;

    /** Free-form theme key/values served verbatim from {@code GET /api/theme}. */
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
