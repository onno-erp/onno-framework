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
     * Create an index for every column a record policy scopes by (if missing), so a scoped list
     * never degrades to a full table scan. Skipped when {@code onno.schema.mode} does not resolve
     * to {@code apply} (blank counts as {@code apply}); the statements are then logged as a
     * warning. On PostgreSQL the indexes are built in the background with
     * {@code CREATE INDEX CONCURRENTLY}, so neither startup nor writes wait, and an index left
     * invalid by an interrupted build is rebuilt; other databases build them at startup.
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
