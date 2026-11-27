package su.onno.web;

import su.onno.service.WebProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * The UI's configuration, each piece served once: {@code /api/theme} (the {@code onno.ui.theme}
 * tokens), {@code /api/branding} (app name, logos, marks, favicon and palette — public, because the
 * login screen paints it before sign-in) and {@code /api/config} (read-only mode, locale, chrome
 * strings and the update notice). The business model ({@code /api/ui/model}) carries none
 * of these.
 */
@RestController
@RequestMapping("/api")
public class UiConfigController {

    private final UiProperties properties;
    private final WebProperties webProperties;
    private final BrandingConfig branding;
    private final UiMessages messages;
    private final ObjectProvider<UpdateChecker> updateChecker;

    public UiConfigController(UiProperties properties, WebProperties webProperties, UiMessages messages,
                          ObjectProvider<UpdateChecker> updateChecker) {
        this.properties = properties;
        this.webProperties = webProperties;
        this.branding = properties.getBranding().toConfig();
        this.messages = messages;
        this.updateChecker = updateChecker;
    }

    @GetMapping("/theme")
    public Map<String, String> getTheme() {
        return properties.getTheme();
    }

    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("readOnly", webProperties.isReadOnly());
        out.put("listExportRoles", webProperties.getList().getExportRoles());
        out.put("locale", properties.getLocale());
        // The display zone for Instant fields (onno.ui.time-zone). Omitted when unset: the client
        // then renders instants in the viewer's browser zone.
        if (!properties.getTimeZone().isEmpty()) {
            out.put("timeZone", properties.getTimeZone());
        }
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
        return out;
    }

    /**
     * The consumer's branding for the web client to apply at runtime: the app name
     * (→ document title), the logo (→ login screen), the favicon, and the per-mode
     * brand palette. The React shell paints all of it itself — the page title, favicon,
     * login mark, and the island/tab accent drawn from its own tokens.
     */
    @GetMapping("/branding")
    public Map<String, Object> getBranding() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("appName", branding.appName());
        out.put("logoUrl", branding.logoUrl());
        out.put("logoUrlDark", branding.logoUrlDark());
        out.put("logoWidth", branding.logoWidth());
        out.put("logoHeight", branding.logoHeight());
        out.put("markUrl", branding.markUrl());
        out.put("markUrlDark", branding.markUrlDark());
        out.put("markFramed", branding.markFramed());
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
