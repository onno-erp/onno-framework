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
