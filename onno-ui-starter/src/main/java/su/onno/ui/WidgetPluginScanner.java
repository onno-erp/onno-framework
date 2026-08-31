package su.onno.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

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
public class WidgetPluginScanner implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WidgetPluginScanner.class);

    private final List<String> scriptNames;
    private final List<String> styleNames;
    private final String serveLocation;
    private final Path stagedDirectory;

    public WidgetPluginScanner(String location) {
        List<Resource> scripts = scan(location, "*.js");
        List<Resource> styles = scan(location, "*.css");
        this.scriptNames = names(scripts);
        // The Gradle plugin also emits a coordinate-specific *-widgets.css (Tailwind over the widget sources); serve and
        // advertise any stylesheet alongside the modules so the SPA can inject it (see ThemeController).
        this.styleNames = names(styles);
        this.stagedDirectory = stage(scripts, styles);
        this.serveLocation = stagedDirectory == null
                ? toServeLocation(location)
                : stagedDirectory.toUri().toString();
        if (!scriptNames.isEmpty()) {
            log.info("Loaded {} custom widget plugin(s): {}{}", scriptNames.size(), scriptNames,
                    styleNames.isEmpty() ? "" : " + styles " + styleNames);
        }
    }

    /** The discovered plugin module file names (e.g. {@code EventLog.js}), sorted, deduplicated. */
    public List<String> scriptNames() {
        return scriptNames;
    }

    /** The discovered plugin stylesheet file names (e.g. {@code com-acme-app-widgets.css}), sorted, deduplicated. */
    public List<String> styleNames() {
        return styleNames;
    }

    /** The single-classpath location the resource handler serves the modules from (trailing slash). */
    public String serveLocation() {
        return serveLocation;
    }

    private static List<Resource> scan(String location, String glob) {
        String pattern = (location.endsWith("/") ? location : location + "/") + glob;
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(pattern);
            List<Resource> found = new ArrayList<>();
            for (Resource r : resources) {
                String name = r.getFilename();
                if (name != null && !name.isBlank()
                        && found.stream().noneMatch(existing -> name.equals(existing.getFilename()))) {
                    found.add(r);
                }
            }
            found.sort(Comparator.comparing(Resource::getFilename));
            return List.copyOf(found);
        } catch (IOException e) {
            // A missing directory is normal (an app with no custom widgets) — resolve to none.
            log.debug("No widget plugins found at {} ({})", pattern, e.getMessage());
            return List.of();
        }
    }

    private static List<String> names(List<Resource> resources) {
        return resources.stream().map(Resource::getFilename).toList();
    }

    /**
     * Copy classpath resources to a stable exploded directory. A dev build may replace a dependent
     * module jar in place while the server is running; serving directly from that mutable zip leaves
     * Spring holding stale offsets and produces {@code ZipFile invalid LOC header} responses.
     */
    private static Path stage(List<Resource> scripts, List<Resource> styles) {
        if (scripts.isEmpty() && styles.isEmpty()) {
            return null;
        }
        try {
            Path directory = Files.createTempDirectory("onno-widget-plugins-");
            for (Resource resource : Stream.concat(scripts.stream(), styles.stream()).toList()) {
                try (var input = resource.getInputStream()) {
                    Files.copy(input, directory.resolve(resource.getFilename()));
                }
            }
            return directory;
        } catch (IOException error) {
            throw new IllegalStateException("Could not stage widget plugins", error);
        }
    }

    @Override
    public void close() {
        if (stagedDirectory == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(stagedDirectory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException error) {
            log.debug("Could not remove staged widget plugins at {} ({})",
                    stagedDirectory, error.getMessage());
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
