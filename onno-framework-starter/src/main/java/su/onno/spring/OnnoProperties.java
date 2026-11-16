package su.onno.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "onno")
public class OnnoProperties {

    /**
     * Packages scanned for {@code @Catalog}, {@code @Document}, {@code @AccumulationRegister},
     * {@code @InformationRegister}, {@code @Enumeration}, and {@code @Constant} types. Leave unset
     * to scan from your {@code @SpringBootApplication} package. This is the core scan property;
     * there is no {@code onno.base-packages} alias.
     */
    private List<String> scanPackages = new ArrayList<>();

    private final Security security = new Security();

    private final Schema schema = new Schema();

    private final Repository repository = new Repository();

    private final PeriodClose periodClose = new PeriodClose();
    private final History history = new History();

    public List<String> getScanPackages() {
        return scanPackages;
    }

    public void setScanPackages(List<String> scanPackages) {
        this.scanPackages = scanPackages;
    }

    public Security getSecurity() {
        return security;
    }

    public Schema getSchema() {
        return schema;
    }

    public Repository getRepository() {
        return repository;
    }

    public History getHistory() {
        return history;
    }

    /** Record change history ({@code onno.history.*}). */
    public static class History {

        /**
         * Whether field-level change history is recorded for catalogs and documents that opt in with
         * {@code @Catalog(history = true)} / {@code @Document(history = true)}. Entities that have
         * not opted in are never recorded. Set to {@code false} to stop recording everywhere (the
         * {@code onno_record_history} table is then not created and the history panel and
         * {@code /api/history} endpoint disappear) without touching the model.
         */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public PeriodClose getPeriodClose() {
        return periodClose;
    }

    /** Accounting period close ({@code onno.period-close.*}). */
    public static class PeriodClose {

        /**
         * Enables period close: documents dated on or before a closed date cannot be created,
         * changed, deleted, posted, reposted or unposted. With no period closed nothing is
         * rejected. Set to {@code false} to remove the service and every check.
         */
        private boolean enabled = true;

        /**
         * Roles allowed to close and reopen periods through the UI page and {@code /api/period-close}.
         * {@code ADMIN} is always allowed.
         */
        private List<String> manageRoles = new ArrayList<>();

        /**
         * Roles whose signed-in holders may change documents inside a closed period without
         * reopening it. Each bypassed operation is written to the audit trail. Empty by default:
         * nobody is exempt, not even {@code ADMIN} — list {@code ADMIN} here to exempt administrators.
         */
        private List<String> overrideRoles = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getManageRoles() {
            return manageRoles;
        }

        public void setManageRoles(List<String> manageRoles) {
            this.manageRoles = manageRoles;
        }

        public List<String> getOverrideRoles() {
            return overrideRoles;
        }

        public void setOverrideRoles(List<String> overrideRoles) {
            this.overrideRoles = overrideRoles;
        }
    }

    /** Repository guardrails ({@code onno.repository.*}). */
    public static class Repository {

        /**
         * Boot-time check that flags catalog/document repository finders which may return
         * soft-deleted ({@code deletionMark = true}) rows into business logic: {@code warn} (default
         * — log a warning), {@code strict} (fail startup), or {@code off}. A finder is exempt when it
         * is deletion-scoped (a {@code ...AndDeletionMarkFalse} derived query, a {@code @Query}
         * filtering {@code deletion_mark}, or a delegate to {@code findAllActive()} /
         * {@code findActiveBy*}) or annotated {@code @su.onno.repository.IncludesDeleted}.
         */
        private String deletionCheck = "warn";

        public String getDeletionCheck() {
            return deletionCheck;
        }

        public void setDeletionCheck(String deletionCheck) {
            this.deletionCheck = deletionCheck;
        }
    }

    /** Schema lifecycle configuration ({@code onno.schema.*}). */
    public static class Schema {

        /**
         * What to do about differences between the metadata model and the database at
         * startup: {@code apply} (default — execute safe changes, report destructive ones),
         * {@code plan} (log the plan, change nothing), {@code validate} (fail startup on any
         * difference or unapplied migration), or {@code off}.
         */
        private String mode = "apply";

        /**
         * Allow {@code apply} to execute data-losing changes (dropped tables/columns,
         * narrowing type changes). Off by default: such changes are logged and skipped.
         */
        private boolean allowDestructive = false;

        /**
         * Which PostgreSQL trigram ({@code pg_trgm}) indexes back the list/ref-picker substring
         * search: {@code auto} (default — the system code/description/number columns plus every
         * attribute declared {@code @Attribute(searchIndex = true)}) or {@code off} (none). Indexes
         * the framework owns but no longer declares are dropped, and missing ones are built with
         * {@code CREATE INDEX CONCURRENTLY} on a background thread after startup. Search still
         * covers every String attribute; this only decides which columns are index-backed.
         */
        private String searchIndexes = "auto";

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public boolean isAllowDestructive() {
            return allowDestructive;
        }

        public void setAllowDestructive(boolean allowDestructive) {
            this.allowDestructive = allowDestructive;
        }

        public String getSearchIndexes() {
            return searchIndexes;
        }

        public void setSearchIndexes(String searchIndexes) {
            this.searchIndexes = searchIndexes;
        }
    }

    /** Security-related configuration ({@code onno.security.*}). */
    public static class Security {

        /**
         * Encryption key for {@code @Attribute(secret = true)} values. Any passphrase works
         * (it is hashed to a 256-bit AES key). Required only when an entity declares a secret
         * attribute; supply it from an environment variable, never hard-code it.
         */
        private String secretKey;

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }
    }
}
