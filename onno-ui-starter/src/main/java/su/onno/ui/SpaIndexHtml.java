package su.onno.ui;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.resource.TransformedResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SPA shell ({@code index.html}) with the configured base path baked in. The web client reads
 * {@code window.__onnoBasePath} to set its router basename and to build shareable deep links, so the
 * server substitutes {@code onno.ui.path} into the served HTML — replacing the {@code "__ONNO_BASE_PATH__"}
 * placeholder — giving the client its mount prefix synchronously, before React Router boots and
 * before any cold-loaded deep link is resolved.
 *
 * <p>Built once at startup and shared by {@link SpaIndexController} (the {@code /} route) and
 * {@link SpaResourceResolver} (the HTML-navigation deep-link fallback) so both serve an
 * identically-prefixed shell.
 */
class SpaIndexHtml {

    /** The token left in {@code index.html} for the server to replace. Mirrors {@code base-path.ts}. */
    static final String PLACEHOLDER = "__ONNO_BASE_PATH__";

    private static final Resource SOURCE = new ClassPathResource("static/ui/index.html");

    /** The opening {@code <head>} tag, with or without attributes ({@code <head lang="en">}). */
    private static final Pattern HEAD_OPEN = Pattern.compile("<head\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    private final String basePath;
    private final Resource resource;

    SpaIndexHtml(String configuredPath) {
        this(configuredPath, null);
    }

    SpaIndexHtml(String configuredPath, WidgetPluginScanner plugins) {
        this.basePath = normalize(configuredPath);
        this.resource = inject(this.basePath, plugins);
    }

    /** The normalized base path: a leading slash and no trailing slash, or {@code "/"} for the root. */
    String basePath() {
        return basePath;
    }

    /**
     * The injected shell, or {@code null} when the frontend hasn't been built (e.g. plain unit tests),
     * matching the prior "no index.html → 404" behaviour of the resource handler.
     */
    Resource resource() {
        return resource;
    }

    private static Resource inject(String basePath, WidgetPluginScanner plugins) {
        if (!SOURCE.exists()) {
            return null;
        }
        try {
            String html = new String(SOURCE.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            // Replace the quoted token so the surrounding `window.__onnoBasePath = "…"` assignment is
            // rewritten without touching anything else that might contain the bare placeholder text.
            String injected = html.replace('"' + PLACEHOLDER + '"', '"' + basePath + '"');
            injected = injectIntoHead(injected, pluginLinks(basePath, plugins));
            return new TransformedResource(SOURCE, injected.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // Reading a classpath resource shouldn't fail; if it does, serve the raw shell rather
            // than failing every page load. The client falls back to the web root.
            return SOURCE;
        }
    }

    /**
     * Inserts {@code markup} right after the first opening {@code <head>} tag, whatever attributes it
     * carries. A document without a head is returned unchanged (the client still loads plugins from
     * {@code /api/ui/config}).
     */
    static String injectIntoHead(String html, String markup) {
        if (markup.isEmpty()) return html;
        Matcher head = HEAD_OPEN.matcher(html);
        if (!head.find()) return html;
        return html.substring(0, head.end()) + markup + html.substring(head.end());
    }

    // Same-origin assets only; external configured plugins retain their normal config-based loading.
    static String pluginLinks(String basePath, WidgetPluginScanner plugins) {
        if (plugins == null) return "";
        String base = "/".equals(basePath) ? "" : basePath;
        StringBuilder links = new StringBuilder();
        for (String name : plugins.scriptNames()) {
            String url = org.springframework.web.util.HtmlUtils.htmlEscape(base + "/plugins/" + name);
            links.append("<link rel=\"modulepreload\" href=\"").append(url)
                    .append("\" data-onno-plugin-script>");
        }
        for (String name : plugins.styleNames()) {
            String url = org.springframework.web.util.HtmlUtils.htmlEscape(base + "/plugins/" + name);
            links.append("<link rel=\"stylesheet\" href=\"").append(url)
                    .append("\" data-onno-plugin-style=\"").append(url).append("\">");
        }
        return links.toString();
    }

    /** Leading slash, no trailing slash; {@code null}/blank/{@code "/"} all collapse to {@code "/"}. */
    static String normalize(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String p = path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
