package su.onno.ui;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
     * The chrome language: a built-in message bundle to base every chrome string on, so a deployment
     * localizes the whole shell with one line instead of a full {@code onno.ui.messages} map. Resolution
     * layers three levels, later wins: the English {@link UiMessages#DEFAULTS} → the {@code locale}
     * bundle → any explicit {@link #getMessages() onno.ui.messages} per-key override.
     * {@code "en"} (the default) uses the built-in English defaults with no bundle file. Other values
     * load {@code classpath:/su/onno/ui/messages/messages-<locale>.properties} (a UTF-8 properties file);
     * onno ships {@code ru}. An app can add its own locale — or override a shipped one — by putting
     * {@code messages-<locale>.properties} on the classpath at {@code onno/messages/} (that location wins
     * over the bundled file). A missing bundle is a no-op (falls back to the English defaults).
     */
    private String locale = "en";

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
     *
     * NOTE: the key set is fixed. Only keys present in {@link UiMessages#DEFAULTS} are ever read — an
     * unknown key (a guessed {@code form.save} / {@code list.new}) is stored but never consumed, so it
     * silently does nothing. The authoritative list lives in {@code UiMessages.DEFAULTS} (real keys are
     * {@code action.save} — whose default is "Write" — {@code action.new}, {@code list.search}, …).
     * Grep that class before writing a localization pass.
     */
    private Map<String, String> messages = new LinkedHashMap<>();

    /** Dashboard rendering tuning (how the home/Page widget grid resolves its KPI tiles). */
    private Dashboard dashboard = new Dashboard();

    /** Login-screen options — currently the optional one-tap demo accounts. */
    private Login login = new Login();

    /** List/table grid defaults — how every list feeds rows unless an {@code EntityView} overrides. */
    private ListView list = new ListView();

    /** Custom widget plugins — consumer-authored React widgets loaded into the SPA at boot. */
    private Plugins plugins = new Plugins();

    /** Bulk-action tuning — how a multi-row selection runs its server action / delete over N records. */
    private Batch batch = new Batch();

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

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public Map<String, String> getMessages() {
        return messages;
    }

    public void setMessages(Map<String, String> messages) {
        this.messages = messages;
    }

    public Dashboard getDashboard() {
        return dashboard;
    }

    public void setDashboard(Dashboard dashboard) {
        this.dashboard = dashboard;
    }

    public Login getLogin() {
        return login;
    }

    public void setLogin(Login login) {
        this.login = login;
    }

    public ListView getList() {
        return list;
    }

    public void setList(ListView list) {
        this.list = list;
    }

    public Plugins getPlugins() {
        return plugins;
    }

    public void setPlugins(Plugins plugins) {
        this.plugins = plugins;
    }

    public Batch getBatch() {
        return batch;
    }

    public void setBatch(Batch batch) {
        this.batch = batch;
    }

    /**
     * Dashboard rendering tuning. A dashboard's {@code count}/{@code metric} tiles each resolve a
     * server-side aggregate (one SQL query per tile). The renderer resolves them concurrently and
     * de-duplicates identical (entity, metric, field, filter) queries, so a 12-tile dashboard no
     * longer pays 12 sequential round-trips.
     */
    public static class Dashboard {

        /**
         * Maximum number of widget aggregates resolved in parallel per dashboard render. Bounded so a
         * wide dashboard can't exhaust the JDBC connection pool — keep it comfortably below the
         * datasource's {@code maximum-pool-size}. {@code 1} forces the old sequential behaviour.
         */
        private int widgetParallelism = 8;

        public int getWidgetParallelism() {
            return widgetParallelism;
        }

        public void setWidgetParallelism(int widgetParallelism) {
            this.widgetParallelism = widgetParallelism;
        }
    }

    /**
     * Bulk-action tuning. When a list's multi-row selection runs a server action or a delete, the
     * request targets every selected id in one call and the server invokes the per-id handler over
     * the set. Each id runs in its own transaction (continue-on-failure), so the set can be resolved
     * concurrently.
     */
    public static class Batch {

        /**
         * Maximum number of records a bulk action resolves in parallel per request. Each id runs its
         * own transaction on a worker thread, so keep this comfortably below the datasource's
         * {@code maximum-pool-size} or the fan-out will starve the JDBC pool. {@code 1} forces the
         * old sequential behaviour. Note that parallel document deletes reverse postings concurrently,
         * which can contend on the same accumulation-register rows — lower this (or set {@code 1}) if
         * you hit lock timeouts on heavily-shared registers.
         */
        private int parallelism = 4;

        public int getParallelism() {
            return parallelism;
        }

        public void setParallelism(int parallelism) {
            this.parallelism = parallelism;
        }
    }

    /**
     * Login-screen options. The one setting today is {@code demo-accounts}: a list of one-tap sign-in
     * shortcuts the login screen renders above the password form, each filling and submitting the
     * credentials for you.
     *
     * <p><b>Demo/evaluation only.</b> The passwords are handed to the browser in the login payload, so
     * enable this only for a public demo or a throwaway sandbox — never for a deployment holding real
     * data. Leave the list empty (the default) to show a plain password form.</p>
     */
    public static class Login {

        /** One-tap sign-in shortcuts shown on the login screen; empty (default) shows none. */
        private java.util.List<DemoAccount> demoAccounts = new java.util.ArrayList<>();

        public java.util.List<DemoAccount> getDemoAccounts() {
            return demoAccounts;
        }

        public void setDemoAccounts(java.util.List<DemoAccount> demoAccounts) {
            this.demoAccounts = demoAccounts;
        }
    }

    /**
     * List/table grid defaults, applied to every entity list unless its {@link EntityView} overrides
     * them via {@link ListSpec#feed}/{@link ListSpec#pageSize}. Two knobs: which pagination engine a
     * list uses by default, and how many rows it fetches per window/page.
     */
    public static class ListView {

        /**
         * Default feed mode for lists that don't declare one. {@code INFINITE} (the out-of-the-box
         * default) cursor-scrolls a keyset stream — fast at any depth, no exact total; {@code PAGED}
         * shows numbered offset pages with a Prev/Next pager and an exact total. Bind from config as
         * {@code onno.ui.list.default-feed: paged} (relaxed/case-insensitive).
         */
        private ListSpec.FeedMode defaultFeed = ListSpec.FeedMode.INFINITE;

        /**
         * Default rows fetched per window (infinite) or per page (paged), for lists that don't set
         * their own. Clamped to the server's list ceiling (500). Default {@code 50}.
         */
        private int pageSize = 50;

        public ListSpec.FeedMode getDefaultFeed() {
            return defaultFeed;
        }

        public void setDefaultFeed(ListSpec.FeedMode defaultFeed) {
            this.defaultFeed = defaultFeed;
        }

        public int getPageSize() {
            return pageSize;
        }

        public void setPageSize(int pageSize) {
            this.pageSize = pageSize;
        }
    }

    /** A single one-tap demo login: a button {@code label} plus the {@code username}/{@code password} it submits. */
    public static class DemoAccount {

        /** The button text, e.g. {@code "Admin"} or {@code "Store manager"}. */
        private String label = "";

        /** The username submitted on tap. */
        private String username = "";

        /** The password submitted on tap. Sent to the browser — demo use only. */
        private String password = "";

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    /**
     * Custom widget plugins. A consumer authors a React widget (a {@code .tsx} compiled by the
     * {@code su.onno.widgets} Gradle plugin into {@code onno-plugins/<name>.js} on the classpath);
     * the SPA loads every such module at boot and each self-registers its widget type via
     * {@code window.onno.registerWidget}. The scanned scripts (plus any {@link #extraUrls}) are
     * advertised to the client as {@code pluginScripts} from {@code GET /api/config} and served
     * under {@code {onno.ui.path}/plugins/**}.
     */
    public static class Plugins {

        /** Whether to scan for, serve, and advertise custom widget plugins. */
        private boolean enabled = true;

        /**
         * Classpath location holding the compiled plugin modules ({@code *.js}). The Gradle plugin
         * emits here; change it only if you stage plugin bundles somewhere non-standard. Must be a
         * {@code classpath:}/{@code classpath*:} location ending in {@code /}.
         */
        private String location = "classpath*:/onno-plugins/";

        /**
         * Extra absolute plugin-module URLs to load in addition to the classpath ones — e.g. a
         * CDN-hosted widget or one served by another app. Appended verbatim to {@code pluginScripts}.
         */
        private List<String> extraUrls = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getLocation() {
            return location;
        }

        public void setLocation(String location) {
            this.location = location;
        }

        public List<String> getExtraUrls() {
            return extraUrls;
        }

        public void setExtraUrls(List<String> extraUrls) {
            this.extraUrls = extraUrls;
        }
    }
}
