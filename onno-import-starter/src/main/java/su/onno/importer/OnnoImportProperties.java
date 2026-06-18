package su.onno.importer;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for generic CSV imports.
 */
@ConfigurationProperties(prefix = "onno.import")
public class OnnoImportProperties {

    /** Master switch for import endpoints and services. */
    private boolean enabled = true;

    /** Maximum accepted CSV file size in bytes. Defaults to 5 MiB. */
    private long maxFileBytes = 5L * 1024L * 1024L;

    /** Maximum data rows returned from preview. */
    private int previewRows = 20;

    /** Maximum data rows processed by one import request. */
    private int maxRows = 10_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getMaxFileBytes() {
        return maxFileBytes;
    }

    public void setMaxFileBytes(long maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }

    public int getPreviewRows() {
        return previewRows;
    }

    public void setPreviewRows(int previewRows) {
        this.previewRows = previewRows;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }
}
