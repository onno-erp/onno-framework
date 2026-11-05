package su.onno.ui;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Record-level access policies ({@code RecordAccessPolicy} beans). There is no on/off switch:
 * policies are active whenever such beans exist.
 */
@ConfigurationProperties(prefix = "onno.access.records")
public class RecordAccessProperties {

    /**
     * Log every request denied because the record lies outside the caller's record scope (the
     * 404s that are indistinguishable from a missing record) at DEBUG on
     * {@code su.onno.ui.RecordAccess}, with the subject, entity and id — for troubleshooting a
     * policy.
     */
    private boolean logDenials = false;

    /**
     * Create an index for every column a record policy scopes by (if missing) at startup, so a
     * scoped list never degrades to a full table scan. Skipped when {@code onno.schema.mode} does
     * not resolve to {@code apply} (blank counts as {@code apply}); the statements are then logged
     * as a warning. A missing index is built with a plain {@code CREATE INDEX}, which blocks writes
     * to its table while it builds; on PostgreSQL an existing one is skipped without a statement.
     */
    private boolean createIndexes = true;

    public boolean isLogDenials() {
        return logDenials;
    }

    public void setLogDenials(boolean logDenials) {
        this.logDenials = logDenials;
    }

    public boolean isCreateIndexes() {
        return createIndexes;
    }

    public void setCreateIndexes(boolean createIndexes) {
        this.createIndexes = createIndexes;
    }
}
