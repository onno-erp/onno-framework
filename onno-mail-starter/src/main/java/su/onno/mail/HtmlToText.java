package su.onno.mail;

import java.util.regex.Pattern;

/**
 * Minimal HTML-to-plaintext converter used to derive a {@code text/plain} alternative from an HTML body,
 * so messages go out as proper {@code multipart/alternative}. Not a full renderer: it strips markup,
 * keeps anchor URLs, and collapses whitespace — enough to give plain-text clients readable content.
 */
final class HtmlToText {

    private static final Pattern SCRIPT_STYLE =
            Pattern.compile("<(script|style)[^>]*>.*?</\\1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern BREAK = Pattern.compile("<\\s*br\\s*/?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BLOCK_END =
            Pattern.compile("</\\s*(p|div|tr|li|h[1-6]|table|ul|ol)\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANCHOR =
            Pattern.compile("<a\\b[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]+>", Pattern.DOTALL);
    private static final Pattern MULTI_BLANK = Pattern.compile("\\n{3,}");
    private static final Pattern TRAILING_WS = Pattern.compile("[ \\t]+\\n");

    private HtmlToText() {
    }

    static String convert(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String s = html;
        s = SCRIPT_STYLE.matcher(s).replaceAll("");
        s = ANCHOR.matcher(s).replaceAll(m -> {
            String text = m.group(2).replaceAll("<[^>]+>", "").trim();
            String href = m.group(1).trim();
            return text.isEmpty() || text.equals(href) ? href : text + " (" + href + ")";
        });
        s = BREAK.matcher(s).replaceAll("\n");
        s = BLOCK_END.matcher(s).replaceAll("\n");
        s = TAG.matcher(s).replaceAll("");
        s = unescapeEntities(s);
        s = TRAILING_WS.matcher(s).replaceAll("\n");
        s = MULTI_BLANK.matcher(s).replaceAll("\n\n");
        return s.trim();
    }

    private static String unescapeEntities(String s) {
        return s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
    }
}
