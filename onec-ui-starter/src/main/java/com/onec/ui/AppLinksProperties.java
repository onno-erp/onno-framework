package com.onec.ui;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * App-association data served from the two {@code /.well-known} files so a native mobile app can
 * claim this domain's {@code https://} links (iOS Universal Links, Android App Links) and open
 * directly instead of the browser. Both lists are empty by default, in which case the corresponding
 * endpoint answers {@code 404} — universal links are opt-in and require deployment-specific identity
 * and signing values that only the operator has.
 *
 * <pre>
 * onec:
 *   app-links:
 *     apple-app-ids:                 # iOS: "&lt;TeamID&gt;.&lt;bundleId&gt;"
 *       - "ABCDE12345.su.onno.onec.mobile"
 *     android:                       # Android: package + signing-cert SHA-256 fingerprint(s)
 *       - package-name: su.onno.onec.mobile
 *         sha256-cert-fingerprints:
 *           - "AB:CD:EF:..."
 * </pre>
 *
 * @see WellKnownController
 */
@ConfigurationProperties(prefix = "onec.app-links")
public class AppLinksProperties {

    /**
     * Apple {@code appID}s (each {@code TeamID.bundleId}) allowed to handle this domain's links,
     * served in {@code /.well-known/apple-app-site-association}. Empty → that endpoint 404s.
     */
    private List<String> appleAppIds = new ArrayList<>();

    /**
     * Android apps allowed to handle this domain's links, served in {@code /.well-known/assetlinks.json}.
     * Empty → that endpoint 404s.
     */
    private List<AndroidApp> android = new ArrayList<>();

    public List<String> getAppleAppIds() {
        return appleAppIds;
    }

    public void setAppleAppIds(List<String> appleAppIds) {
        this.appleAppIds = appleAppIds;
    }

    public List<AndroidApp> getAndroid() {
        return android;
    }

    public void setAndroid(List<AndroidApp> android) {
        this.android = android;
    }

    /** One Android app's identity in an {@code assetlinks.json} statement. */
    public static class AndroidApp {

        /** The application package name, e.g. {@code su.onno.onec.mobile}. */
        private String packageName;

        /**
         * SHA-256 fingerprints of the signing certificate(s) (upper-case, colon-separated). Multiple
         * allow rotating keys or distinct debug/release/upload certs to all verify the same domain.
         */
        private List<String> sha256CertFingerprints = new ArrayList<>();

        public String getPackageName() {
            return packageName;
        }

        public void setPackageName(String packageName) {
            this.packageName = packageName;
        }

        public List<String> getSha256CertFingerprints() {
            return sha256CertFingerprints;
        }

        public void setSha256CertFingerprints(List<String> sha256CertFingerprints) {
            this.sha256CertFingerprints = sha256CertFingerprints;
        }
    }
}
