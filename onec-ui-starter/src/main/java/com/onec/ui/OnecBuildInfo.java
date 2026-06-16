package com.onec.ui;

import java.io.InputStream;
import java.util.Properties;

/**
 * The onec-framework version this build was produced from, baked into {@code
 * META-INF/onec-build.properties} by the {@code processResources} step. Read once, statically, so it
 * is cheap to consult.
 *
 * <p>Returns an empty string when the version is unknown — e.g. running from an IDE that skipped the
 * Gradle resource-filtering, leaving the {@code ${onecVersion}} token unsubstituted. Callers treat
 * "unknown" as "can't compare", which keeps the update check from ever signalling a false positive.
 */
public final class OnecBuildInfo {

    private static final String VERSION = load();

    private OnecBuildInfo() {
    }

    /** The framework version, or {@code ""} when it could not be determined. */
    public static String version() {
        return VERSION;
    }

    private static String load() {
        try (InputStream in = OnecBuildInfo.class.getResourceAsStream("/META-INF/onec-build.properties")) {
            if (in == null) {
                return "";
            }
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("onec.version", "").trim();
            // An un-substituted Gradle token means resource filtering didn't run — treat as unknown.
            return v.startsWith("${") ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }
}
