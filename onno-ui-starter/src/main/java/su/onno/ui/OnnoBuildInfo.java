package su.onno.ui;

import java.io.InputStream;
import java.util.Properties;

/**
 * The onno-framework version this build was produced from, baked into {@code
 * META-INF/onno-build.properties} by the {@code processResources} step. Read once, statically, so it
 * is cheap to consult.
 *
 * <p>Returns an empty string when the version is unknown — e.g. running from an IDE that skipped the
 * Gradle resource-filtering, leaving the {@code ${onnoVersion}} token unsubstituted. Callers treat
 * "unknown" as "can't compare", which keeps the update check from ever signalling a false positive.
 */
public final class OnnoBuildInfo {

    private static final String VERSION = load();

    private OnnoBuildInfo() {
    }

    /** The framework version, or {@code ""} when it could not be determined. */
    public static String version() {
        return VERSION;
    }

    private static String load() {
        try (InputStream in = OnnoBuildInfo.class.getResourceAsStream("/META-INF/onno-build.properties")) {
            if (in == null) {
                return "";
            }
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("onno.version", "").trim();
            // An un-substituted Gradle token means resource filtering didn't run — treat as unknown.
            return v.startsWith("${") ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }
}
