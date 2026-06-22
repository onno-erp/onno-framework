package su.onno.ui;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "onno.ui")
public class UiProperties {

    /** Master switch for the UI starter. Also gated on a {@code MetadataRegistry} bean being present. */
    private boolean enabled = true;

    /**
     * URL prefix the SPA is mounted under. Baked into the served {@code index.html} (and returned as
     * {@code basePath} from {@code GET /api/config}) so the web client adopts it as its router
     * basename and deep-link prefix; the bare root redirects here. Default {@code /ui}; set to
     * {@code /} to mount the app at the web root.
     */
    private String path = "/ui";

    /** When true, every mutating REST call is rejected with {@code 403 UI is in read-only mode}. */
    private boolean readOnly = false;

    /** Free-form theme key/values served verbatim from {@code GET /api/theme}. */
    private Map<String, String> theme = new LinkedHashMap<>();

    /**
     * Overrides for the framework's own chrome strings — action buttons, confirmation dialogs, the
     * login screen, empty/loading states, and client-side validation messages. Keys come from
     * {@link UiMessages#DEFAULTS} (e.g. {@code login.title}, {@code action.new}); each value replaces
     * the English default. The resolved map renders the server-side DivKit chrome and is handed to
     * the web client via {@code GET /api/config}, so a one-language deployment can fully localize the
     * shell without patching framework code. Because the keys contain dots, quote them in YAML so
     * they bind as literal map keys (e.g. {@code "action.new": "Новый"} nested under
     * {@code onno.ui.messages}); in a properties file use bracket notation instead
     * ({@code onno.ui.messages[action.new]=Новый}).
     */
    private Map<String, String> messages = new LinkedHashMap<>();

    /** App-settings page (the {@code @Constant} editor); opt-in via {@code onno.ui.settings.*}. */
    private Settings settings = new Settings();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public Map<String, String> getTheme() {
        return theme;
    }

    public void setTheme(Map<String, String> theme) {
        this.theme = theme;
    }

    public Map<String, String> getMessages() {
        return messages;
    }

    public void setMessages(Map<String, String> messages) {
        this.messages = messages;
    }

    public Settings getSettings() {
        return settings;
    }

    public void setSettings(Settings settings) {
        this.settings = settings;
    }

    /**
     * The built-in Settings page — the {@code @Constant} editor surfaced at {@code /settings} with
     * an auto-injected admin nav entry. Opt-in: off by default so an app shows it only when it wants
     * one (an app can still author its own {@code Page} at {@code "/settings"}, and drop individual
     * constant toggles onto any page with {@code PageBuilder.constants(...)}, regardless of this flag).
     */
    public static class Settings {

        /** Whether to surface the built-in Settings page and its admin nav entry. */
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
