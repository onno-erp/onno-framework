package su.onno.crm.channels.gmail;

import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

/**
 * Email HTML made safe to store and show. The allowlist keeps what newsletters and signatures use
 * for layout (tables, inline styles, legacy presentational attributes, images) and drops scripts,
 * event handlers, forms, frames, embeds and any URL outside http(s)/mailto/cid/data images. The
 * inbox additionally renders the result in a script-less sandboxed frame with its own CSP.
 */
final class EmailHtml {
    /** Larger sanitized bodies are not kept; the plain-text body still carries the message. */
    static final int MAX_LENGTH = 500_000;

    private static final Safelist SAFELIST = Safelist.relaxed()
            .addTags("center", "font", "hr", "span", "div", "small", "big", "s", "strike", "tt")
            .addAttributes(":all", "style", "align", "valign", "dir", "title", "width", "height", "bgcolor")
            .addAttributes("table", "border", "cellpadding", "cellspacing", "background")
            .addAttributes("td", "background", "nowrap")
            .addAttributes("font", "color", "face", "size")
            .addAttributes("img", "border")
            .addProtocols("img", "src", "http", "https", "cid", "data")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addEnforcedAttribute("a", "rel", "noopener noreferrer")
            .addEnforcedAttribute("a", "target", "_blank")
            .preserveRelativeLinks(false);

    private EmailHtml() {}

    /** Sanitized body HTML, or null when there is none, nothing visible survives, or it is too large. */
    static String sanitize(String html) {
        if (html == null || html.isBlank()) return null;
        Document clean = new Cleaner(SAFELIST).clean(Jsoup.parse(html));
        // data: is allowed for inline images only; a data: link or non-image payload is dropped.
        clean.select("img[src^=data:]").forEach(img -> {
            if (!img.attr("src").regionMatches(true, 0, "data:image/", 0, 11)) img.removeAttr("src");
        });
        clean.outputSettings().prettyPrint(false);
        String body = clean.body().html().strip();
        if (body.isEmpty() || body.length() > MAX_LENGTH) return null;
        if (clean.body().text().isBlank() && clean.select("img[src]").isEmpty()) return null;
        return body;
    }

    /** Readable text for an HTML-only email: block breaks kept, markup and entities resolved. */
    static String toText(String html) {
        Document doc = Jsoup.parse(html);
        doc.select("script,style,head").remove();
        // text() collapses source whitespace; a paragraph-separator marker survives it as a line break.
        doc.select("br").before(" ");
        doc.select("p,div,tr,li,h1,h2,h3,h4,h5,h6,blockquote").after(" ");
        return doc.text().replaceAll(" *  *", "\n").replaceAll("\\n{3,}", "\n\n").strip();
    }

    /** Points {@code cid:} image sources at their stored attachment URLs; unknown cids are left as is. */
    static String resolveContentIds(String html, Map<String, String> urlsByContentId) {
        if (html == null || urlsByContentId.isEmpty() || !html.contains("cid:")) return html;
        Document doc = Jsoup.parseBodyFragment(html);
        doc.select("img[src^=cid:]").forEach(img -> {
            String url = urlsByContentId.get(img.attr("src").substring(4));
            if (url != null) img.attr("src", url);
        });
        doc.outputSettings().prettyPrint(false);
        return doc.body().html();
    }
}
