package com.onec.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "onec")
public class OnecProperties {

    private List<String> scanPackages = new ArrayList<>();

    private final Security security = new Security();

    public List<String> getScanPackages() {
        return scanPackages;
    }

    public void setScanPackages(List<String> scanPackages) {
        this.scanPackages = scanPackages;
    }

    public Security getSecurity() {
        return security;
    }

    /** Security-related configuration ({@code onec.security.*}). */
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
