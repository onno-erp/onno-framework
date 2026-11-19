package su.onno.crm.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

/**
 * The HTML of a formatted email reply, made safe to store and send. A reply editor's formatting only: paragraphs and
 * line breaks, bold, italic, underline, strikethrough, inline code, links (http, https and mailto), lists, quotes and
 * headings. Everything else (styles, classes, images, tables, scripts, handlers) is dropped, its text kept.
 */
public final class ReplyHtml {
    /** Larger formatted replies are refused; the plain-text body is limited separately. */
    public static final int MAX_LENGTH = 100_000;

    private static final Safelist SAFELIST = Safelist.none()
            .addTags("p", "div", "br", "b", "strong", "i", "em", "u", "s", "strike", "code", "pre",
                    "a", "ul", "ol", "li", "blockquote", "h1", "h2", "h3")
            .addAttributes(":all", "dir")
            .addAttributes("a", "href")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addEnforcedAttribute("a", "rel", "noopener noreferrer")
            .addEnforcedAttribute("a", "target", "_blank")
            .preserveRelativeLinks(false);

    private ReplyHtml() {}

    /** The sanitized fragment, or null when there is none, no text survives, or it is longer than {@link #MAX_LENGTH}. */
    public static String sanitize(String html) {
        if (html == null || html.isBlank() || html.length() > MAX_LENGTH) return null;
        Document clean = new Cleaner(SAFELIST).clean(Jsoup.parseBodyFragment(html));
        clean.outputSettings().prettyPrint(false);
        if (clean.body().text().isBlank()) return null;
        String body = clean.body().html().strip();
        return body.isEmpty() || body.length() > MAX_LENGTH ? null : body;
    }
}
