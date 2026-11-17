package su.onno.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.resource.TransformedResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SPA shell ({@code index.html}) with the configured base path baked in. The web client reads
 * {@code window.__onnoBasePath} to set its router basename and to build shareable deep links, so the
 * server injects {@code <script>window.__onnoBasePath = "…";</script>} right after the opening
 * {@code <head>} tag, giving the client its mount prefix synchronously, before React Router boots
 * and before any cold-loaded deep link is resolved. A legacy {@code "__ONNO_BASE_PATH__"} placeholder
 * in the document is replaced with the same value.
 *
 * <p>The shell is the application's own UI build at {@code classpath:/onno-app/index.html} when one is
 * on the classpath, otherwise the default shell bundled with the starter at
 * {@code classpath:/static/ui/index.html}.
 *
 * <p>Built once at startup and shared by {@link SpaIndexController} (the {@code /} route) and
 * {@link SpaResourceResolver} (the HTML-navigation deep-link fallback) so both serve an
 * identically-prefixed shell.
 */
class SpaIndexHtml {

    /** The token an {@code index.html} may carry for the server to replace. Mirrors {@code base-path.ts}. */
    static final String PLACEHOLDER = "__ONNO_BASE_PATH__";

    /** Classpath directory of an application's own UI build; served in place of the default shell. */
    static final String APP_LOCATION = "onno-app/";

    /** Classpath directory of the default shell bundled with the starter. */
    static final String DEFAULT_LOCATION = "static/ui/";

    /** The opening {@code <head>} tag, with or without attributes ({@code <head lang="en">}). */
    private static final Pattern HEAD_OPEN = Pattern.compile("<head\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    private final String basePath;
    private final Resource resource;

    SpaIndexHtml(String configuredPath) {
        this(configuredPath, List.of(
                new ClassPathResource(APP_LOCATION + "index.html"),
                new ClassPathResource(DEFAULT_LOCATION + "index.html")));
    }

    /** Uses the first existing resource of {@code candidates} as the shell source. */
    SpaIndexHtml(String configuredPath, List<? extends Resource> candidates) {
        this.basePath = normalize(configuredPath);
        Resource source = candidates.stream().filter(Resource::exists).findFirst().orElse(null);
        this.resource = source == null ? null : inject(this.basePath, source);
    }

    /** The normalized base path: a leading slash and no trailing slash, or {@code "/"} for the root. */
    String basePath() {
        return basePath;
    }

    /**
     * The injected shell, or {@code null} when no shell is on the classpath (e.g. plain unit tests),
     * in which case navigation requests resolve to {@code 404}.
     */
    Resource resource() {
        return resource;
    }

    private static Resource inject(String basePath, Resource source) {
        try {
            String html = new String(source.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String literal = jsStringLiteral(basePath);
            // Replace only the quoted token so nothing else containing the bare text is touched.
            String injected = html.replace('"' + PLACEHOLDER + '"', literal);
            injected = injectIntoHead(injected, "<script>window.__onnoBasePath = " + literal + ";</script>");
            return new TransformedResource(source, injected.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // Reading a classpath resource shouldn't fail; if it does, serve the raw shell rather
            // than failing every page load. The client falls back to the web root.
            return source;
        }
    }

    /**
     * Inserts {@code markup} right after the first opening {@code <head>} tag, whatever attributes it
     * carries. A document without a head is returned unchanged.
     */
    static String injectIntoHead(String html, String markup) {
        if (markup.isEmpty()) return html;
        Matcher head = HEAD_OPEN.matcher(html);
        if (!head.find()) return html;
        return html.substring(0, head.end()) + markup + html.substring(head.end());
    }

    /**
     * A double-quoted JavaScript string literal safe to embed in an inline {@code <script>}: quotes,
     * backslashes, control characters, line separators and the HTML-significant {@code < > & '} are
     * written as {@code \}{@code uXXXX} escapes, so the value can neither end the string nor the script.
     */
    static String jsStringLiteral(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == '"' || c == '\\' || c == '<' || c == '>' || c == '&' || c == '\''
                    || c == ' ' || c == ' ') {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
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
