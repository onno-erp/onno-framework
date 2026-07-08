package su.onno.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Discovers consumer widget-plugin modules on the classpath at startup. The {@code su.onno.widgets}
 * Gradle plugin compiles each {@code src/main/widgets/*.tsx} into {@code onno-plugins/<name>.js},
 * which ships in the app's jar; this scanner globs that location once and holds the sorted script
 * file names.
 *
 * <p>The names feed two things: {@link ThemeController} advertises them (base-path-prefixed) as
 * {@code pluginScripts} from {@code GET /api/config}, and a resource handler serves the files under
 * {@code {onno.ui.path}/plugins/**}. Scanning once at boot is fine — the classpath is fixed for the
 * life of the process.
 */
public class WidgetPluginScanner {

    private static final Logger log = LoggerFactory.getLogger(WidgetPluginScanner.class);

    private final List<String> scriptNames;
    private final List<String> styleNames;
    private final String serveLocation;

    public WidgetPluginScanner(String location) {
        this.serveLocation = toServeLocation(location);
        this.scriptNames = scan(location, "*.js");
        // The Gradle plugin also emits onno-widgets.css (Tailwind over the widget sources); serve and
        // advertise any stylesheet alongside the modules so the SPA can inject it (see ThemeController).
        this.styleNames = scan(location, "*.css");
        if (!scriptNames.isEmpty()) {
            log.info("Loaded {} custom widget plugin(s): {}{}", scriptNames.size(), scriptNames,
                    styleNames.isEmpty() ? "" : " + styles " + styleNames);
        }
    }

    /** The discovered plugin module file names (e.g. {@code EventLog.js}), sorted, deduplicated. */
    public List<String> scriptNames() {
        return scriptNames;
    }

    /** The discovered plugin stylesheet file names (e.g. {@code onno-widgets.css}), sorted, deduplicated. */
    public List<String> styleNames() {
        return styleNames;
    }

    /** The single-classpath location the resource handler serves the modules from (trailing slash). */
    public String serveLocation() {
        return serveLocation;
    }

    private static List<String> scan(String location, String glob) {
        String pattern = (location.endsWith("/") ? location : location + "/") + glob;
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(pattern);
            List<String> names = new ArrayList<>();
            for (Resource r : resources) {
                String name = r.getFilename();
                if (name != null && !name.isBlank() && !names.contains(name)) {
                    names.add(name);
                }
            }
            names.sort(Comparator.naturalOrder());
            return List.copyOf(names);
        } catch (IOException e) {
            // A missing directory is normal (an app with no custom widgets) — resolve to none.
            log.debug("No widget plugins found at {} ({})", pattern, e.getMessage());
            return List.of();
        }
    }

    // The ResourceHandler serves from a single classpath root; the scan pattern may use classpath*:
    // to see modules across jars, but serving needs a concrete `classpath:/…/` location.
    private static String toServeLocation(String location) {
        String loc = location.startsWith("classpath*:") ? "classpath:" + location.substring("classpath*:".length())
                : location;
        return loc.endsWith("/") ? loc : loc + "/";
    }
}
