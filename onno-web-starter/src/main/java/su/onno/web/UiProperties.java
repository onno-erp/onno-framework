package su.onno.web;

import su.onno.service.WebProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the UI looks like: where it is mounted, its branding, language and theme, and the login
 * screen. How the web layer behaves (read-only mode, batches, list feeds, live reload) is
 * {@link WebProperties} ({@code onno.web.*}).
 */
@ConfigurationProperties(prefix = "onno.ui")
public class UiProperties {

    /**
     * URL prefix the SPA is mounted under. Baked into the served {@code index.html} so the web
     * client adopts it as its router basename and deep-link prefix; the bare root redirects here.
     * Default {@code /ui}; set to {@code /} to mount the app at the web root.
     */
    private String path = "/ui";

    /** Free-form theme key/values served verbatim from {@code GET /api/theme}. */
    private Map<String, String> theme = new LinkedHashMap<>();

    /**
     * The chrome language: a built-in message bundle to base every chrome string on, so a deployment
     * localizes the whole shell with one line instead of a full {@code onno.ui.messages} map. Resolution
     * layers three levels, later wins: the English {@link UiMessages#DEFAULTS} → the {@code locale}
     * bundle → any explicit {@link #getMessages() onno.ui.messages} per-key override.
     * {@code "en"} (the default) uses the built-in English defaults with no bundle file. Other values
     * load {@code classpath:/su/onno/web/messages/messages-<locale>.properties} (a UTF-8 properties file);
     * onno ships {@code ru}. An app can add its own locale — or override a shipped one — by putting
     * {@code messages-<locale>.properties} on the classpath at {@code onno/messages/} (that location wins
     * over the bundled file). A missing bundle is a no-op (falls back to the English defaults).
     */
    private String locale = "en";

    /**
     * The display time zone for {@code Instant} fields: an IANA zone id such as
     * {@code Europe/Moscow}. An {@code Instant} is stored and transported as an absolute UTC moment;
     * the web client renders and edits it as wall time in this zone, so every viewer of a
     * single-site deployment sees the same clock. Empty (the default) uses each viewer's browser
     * zone. {@code LocalDate}, {@code LocalDateTime} and {@code LocalTime} fields are zone-less wall
     * values and are never shifted. An unknown zone id fails startup.
     */
    private String timeZone = "";

    /**
     * Overrides for the framework's own chrome strings — action buttons, confirmation dialogs, the
     * login screen, empty/loading states, and client-side validation messages. Keys come from
     * {@link UiMessages#DEFAULTS} (e.g. {@code login.title}, {@code action.new}); each value replaces
     * the English default. The resolved map labels the server-built descriptors and is handed to
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

    /** Login-screen options — currently the optional one-tap demo accounts. */
    private Login login = new Login();

    /** App branding: name, logos and brand colors the web client applies to its chrome. */
    private Branding branding = new Branding();

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
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

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        String zone = timeZone == null ? "" : timeZone.trim();
        if (!zone.isEmpty()) {
            try {
                java.time.ZoneId.of(zone);
            } catch (java.time.DateTimeException invalid) {
                throw new IllegalArgumentException(
                        "onno.ui.time-zone must be an IANA zone id (e.g. Europe/Moscow): " + zone, invalid);
            }
        }
        this.timeZone = zone;
    }

    public Map<String, String> getMessages() {
        return messages;
    }

    public void setMessages(Map<String, String> messages) {
        this.messages = messages;
    }

    public Login getLogin() {
        return login;
    }

    public Branding getBranding() {
        return branding;
    }

    public void setBranding(Branding branding) {
        this.branding = branding;
    }

    public void setLogin(Login login) {
        this.login = login;
    }

    /** App branding: name, logos and brand colors the web client applies to its chrome. */
    public static class Branding {
        /** App name shown in the shell and on the login screen; blank uses "onno". */
        private String appName;
        /** Logo image URL for the light theme. */
        private String logoUrl;
        /** Logo image URL for the dark theme; blank reuses the light logo. */
        private String logoUrlDark;
        /** Fixed logo width in px; unset keeps the intrinsic aspect ratio. */
        private Integer logoWidth;
        /** Fixed logo height in px. */
        private Integer logoHeight;
        /** Favicon URL. */
        private String faviconUrl;
        /** Compact square mark for the collapsed navigation rail (light theme). */
        private String markUrl;
        /** Compact mark for the dark theme; blank reuses the light mark. */
        private String markUrlDark;
        /** Whether the compact mark sits in a framed tile. */
        private boolean markFramed = true;
        /** Light-theme color overrides (page, surface, border, text, muted, primary, primary-soft), as hex. */
        private Map<String, String> light = new LinkedHashMap<>();
        /** Dark-theme color overrides, same keys as {@code light}. */
        private Map<String, String> dark = new LinkedHashMap<>();

        public String getAppName() { return appName; }
        public void setAppName(String appName) { this.appName = appName; }
        public String getLogoUrl() { return logoUrl; }
        public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }
        public String getLogoUrlDark() { return logoUrlDark; }
        public void setLogoUrlDark(String logoUrlDark) { this.logoUrlDark = logoUrlDark; }
        public Integer getLogoWidth() { return logoWidth; }
        public void setLogoWidth(Integer logoWidth) { this.logoWidth = logoWidth; }
        public Integer getLogoHeight() { return logoHeight; }
        public void setLogoHeight(Integer logoHeight) { this.logoHeight = logoHeight; }
        public String getFaviconUrl() { return faviconUrl; }
        public void setFaviconUrl(String faviconUrl) { this.faviconUrl = faviconUrl; }
        public String getMarkUrl() { return markUrl; }
        public void setMarkUrl(String markUrl) { this.markUrl = markUrl; }
        public String getMarkUrlDark() { return markUrlDark; }
        public void setMarkUrlDark(String markUrlDark) { this.markUrlDark = markUrlDark; }
        public boolean isMarkFramed() { return markFramed; }
        public void setMarkFramed(boolean markFramed) { this.markFramed = markFramed; }
        public Map<String, String> getLight() { return light; }
        public void setLight(Map<String, String> light) { this.light = light; }
        public Map<String, String> getDark() { return dark; }
        public void setDark(Map<String, String> dark) { this.dark = dark; }

        /** The resolved branding record the controllers serve. */
        public BrandingConfig toConfig() {
            return new BrandingConfig(appName, logoUrl, logoUrlDark, logoWidth, logoHeight, faviconUrl,
                    palette(light), palette(dark), markUrl, markUrlDark, markFramed);
        }

        private static BrandPalette palette(Map<String, String> m) {
            if (m == null || m.isEmpty()) {
                return BrandPalette.empty();
            }
            return new BrandPalette(m.get("page"), m.get("surface"), m.get("border"), m.get("text"),
                    m.get("muted"), m.get("primary"),
                    m.getOrDefault("primary-soft", m.get("primarySoft")));
        }
    }

    /**
     * Login-screen options. The one setting today is {@code demo-accounts}: one-tap sign-in
     * shortcuts the login screen renders above the password form.
     *
     * <p><b>Demo/evaluation only.</b> The passwords are handed to the browser in the login payload, so
     * enable this only for a public demo or a throwaway sandbox — never for a deployment holding real
     * data.</p>
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
}
