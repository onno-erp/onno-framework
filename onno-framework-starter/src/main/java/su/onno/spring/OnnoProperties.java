package su.onno.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "onno")
public class OnnoProperties {

    /**
     * Packages scanned for {@code @Catalog}, {@code @Document}, {@code @AccumulationRegister},
     * {@code @InformationRegister}, {@code @Enumeration}, and {@code @Constant} types. Leave unset
     * to scan from your {@code @SpringBootApplication} package. This is the core scan property —
     * <strong>not</strong> {@code onno.base-packages} (which only exists for mail/print templates).
     */
    private List<String> scanPackages = new ArrayList<>();

    private final Security security = new Security();

    private final Schema schema = new Schema();

    private final Repository repository = new Repository();

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
