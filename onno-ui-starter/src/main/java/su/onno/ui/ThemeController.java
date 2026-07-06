package su.onno.ui;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

@RestController
@RequestMapping("/api")
public class ThemeController {

    private final UiProperties properties;
    private final BrandingConfig branding;
    private final UiMessages messages;
    private final ObjectProvider<UpdateChecker> updateChecker;
    private final ObjectProvider<WidgetPluginScanner> widgetPlugins;

    public ThemeController(UiProperties properties, UiLayout layout, UiMessages messages,
                          ObjectProvider<UpdateChecker> updateChecker,
                          ObjectProvider<WidgetPluginScanner> widgetPlugins) {
        this.properties = properties;
        this.branding = layout.shell().branding();
        this.messages = messages;
        this.updateChecker = updateChecker;
        this.widgetPlugins = widgetPlugins;
    }

    @GetMapping("/theme")
    public Map<String, String> getTheme() {
        return properties.getTheme();
    }

    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("readOnly", properties.isReadOnly());
        out.put("basePath", properties.getPath());
        // The resolved chrome strings (English defaults + onno.ui.messages overrides). The web
        // client overlays this on its bundled defaults so the whole shell — buttons, dialogs,
        // login, empty/loading states, validation — speaks the app's configured language.
        out.put("messages", messages.asMap());
        // When the update check is enabled, hand the client the last-known result so it can render
        // (or hide) the "update available" banner. Absent when the checker bean is disabled.
        UpdateChecker checker = updateChecker.getIfAvailable();
        if (checker != null) {
            UpdateStatus s = checker.status();
            Map<String, Object> update = new LinkedHashMap<>();
            update.put("available", s.updateAvailable());
            update.put("current", s.currentVersion());
            update.put("latest", s.latestVersion());
            update.put("url", s.releaseUrl());
            out.put("update", update);
        }
        // Custom widget plugins to load at boot: each classpath module under {path}/plugins/<name>,
        // plus any configured external URLs. The client dynamic-imports these; each self-registers
        // its widget type(s). Omitted (never null) when plugins are disabled or none are present.
        List<String> pluginScripts = pluginScripts();
        if (!pluginScripts.isEmpty()) {
            out.put("pluginScripts", pluginScripts);
        }
        return out;
    }

    private List<String> pluginScripts() {
        List<String> urls = new ArrayList<>();
        WidgetPluginScanner scanner = widgetPlugins.getIfAvailable();
        if (scanner != null) {
            String base = "/".equals(properties.getPath()) ? "" : properties.getPath();
            for (String name : scanner.scriptNames()) {
                urls.add(base + "/plugins/" + name);
            }
        }
        urls.addAll(properties.getPlugins().getExtraUrls());
        return urls;
    }

    /**
     * The consumer's branding for the web client to apply at runtime: the app name
     * (→ document title), the logo (→ login screen), the favicon, and the per-mode
     * brand palette. The DivKit chrome renders the logo and palette server-side; this
     * endpoint covers the parts the React shell owns and paints itself — the page
     * title, favicon, login mark, and the island/tab accent (which the React shell
     * draws from its own tokens, not from server-rendered DivKit).
     */
    @GetMapping("/branding")
    public Map<String, Object> getBranding() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("appName", branding.appName());
        out.put("logoUrl", branding.logoUrl());
        out.put("logoUrlDark", branding.logoUrlDark());
        out.put("logoWidth", branding.logoWidth());
        out.put("logoHeight", branding.logoHeight());
        out.put("faviconUrl", branding.faviconUrl());
        Map<String, Object> palette = new LinkedHashMap<>();
        palette.put("light", paletteMap(branding.light()));
        palette.put("dark", paletteMap(branding.dark()));
        out.put("palette", palette);
        return out;
    }

    /** A BrandPalette as a map of only the slots the consumer actually overrode (others omitted). */
    private static Map<String, String> paletteMap(BrandPalette p) {
        Map<String, String> m = new LinkedHashMap<>();
        BiConsumer<String, String> put = (k, v) -> {
            if (v != null && !v.isBlank()) {
                m.put(k, v);
            }
        };
        put.accept("page", p.page());
        put.accept("surface", p.surface());
        put.accept("border", p.border());
        put.accept("text", p.text());
        put.accept("muted", p.muted());
        put.accept("primary", p.primary());
        put.accept("primarySoft", p.primarySoft());
        return m;
    }
}
