package su.onno.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Loads a built-in chrome message bundle for {@code onno.ui.locale} — the base layer between the
 * English {@link UiMessages#DEFAULTS} and a deployment's explicit {@code onno.ui.messages} overrides.
 *
 * <p>A bundle is a UTF-8 {@code .properties} file of the same keys as {@link UiMessages#DEFAULTS}. Two
 * classpath locations are consulted, later wins so a consumer can override a shipped locale (or add a
 * new one) without touching the framework:
 * <ol>
 *   <li>{@code su/onno/ui/messages/messages-<locale>.properties} — bundled with onno (ships {@code ru}).</li>
 *   <li>{@code onno/messages/messages-<locale>.properties} — the consumer app's own file, wins.</li>
 * </ol>
 *
 * <p>{@code "en"} (and a blank locale) returns an empty map — English is the code defaults, no file. A
 * missing bundle is a no-op (the resolver simply falls back to the English defaults).
 */
public final class UiMessageBundles {

    private static final Logger log = LoggerFactory.getLogger(UiMessageBundles.class);

    private static final String BUNDLED = "su/onno/ui/messages/messages-%s.properties";
    private static final String CONSUMER = "onno/messages/messages-%s.properties";

    private UiMessageBundles() {
    }

    /**
     * The message overrides for {@code locale}, merged from the bundled then the consumer file (consumer
     * wins). Empty for {@code "en"}, blank, or an unknown locale.
     */
    public static Map<String, String> load(String locale) {
        Map<String, String> out = new LinkedHashMap<>();
        if (locale == null || locale.isBlank() || "en".equalsIgnoreCase(locale.trim())) {
            return out; // English is the code DEFAULTS — no bundle file to load.
        }
        String key = locale.trim().toLowerCase(Locale.ROOT);
        boolean found = merge(out, String.format(BUNDLED, key));
        found |= merge(out, String.format(CONSUMER, key));
        if (!found) {
            log.warn("onno.ui.locale='{}' but no message bundle found (looked for {} and {}); "
                    + "falling back to English defaults.", locale, String.format(BUNDLED, key),
                    String.format(CONSUMER, key));
        }
        return out;
    }

    /** Overlay one properties file onto {@code out} if present. Returns whether the file existed. */
    private static boolean merge(Map<String, String> out, String path) {
        ClassPathResource res = new ClassPathResource(path);
        if (!res.exists()) {
            return false;
        }
        try (InputStream is = res.getInputStream();
             InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            Properties props = new Properties();
            props.load(reader); // load(Reader) honours the reader's UTF-8 charset (unlike load(InputStream))
            props.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
            return true;
        } catch (IOException e) {
            log.warn("onno-ui: failed to read message bundle {}: {}", path, e.toString());
            return false;
        }
    }
}
