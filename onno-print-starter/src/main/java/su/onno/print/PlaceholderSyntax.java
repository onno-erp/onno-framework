package su.onno.print;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code {{key}}} / {@code {{table.column}}} placeholder grammar shared by the Word and Excel
 * engines. Unknown placeholders are left in place so a typo is visible in the printed output.
 */
final class PlaceholderSyntax {

    static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)?)\\s*}}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_]+");

    private PlaceholderSyntax() {
    }

    static String requireKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Placeholder key must match [A-Za-z0-9_]+: " + key);
        }
        return key;
    }

    /** The table named by the first {@code {{table.column}}} placeholder in {@code text} that is bound, or null. */
    static String tableIn(String text, TemplateModel model) {
        if (text == null || text.indexOf("{{") < 0) return null;
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            String key = m.group(1);
            int dot = key.indexOf('.');
            if (dot > 0 && model.tables().containsKey(key.substring(0, dot))) return key.substring(0, dot);
        }
        return null;
    }

    /**
     * Resolves a placeholder key: {@code table.column} reads {@code row} when the table matches,
     * any other key reads the model. Returns null for an unbound key.
     */
    static TemplateValue lookup(String key, TemplateModel model, String table, TemplateModel.Row row) {
        int dot = key.indexOf('.');
        if (dot > 0) {
            if (row == null || !key.substring(0, dot).equals(table)) return null;
            return row.values().get(key.substring(dot + 1));
        }
        return model.values().get(key);
    }

    /** {@code text} with every bound placeholder replaced by its document text. */
    static String replace(String text, Function<String, TemplateValue> values, Locale locale) {
        if (text == null || text.indexOf("{{") < 0) return text;
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            TemplateValue v = values.apply(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(v == null ? m.group() : v.asText(locale)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Matches in {@code text}, in order. */
    static List<MatchResultView> matches(String text) {
        Matcher m = PLACEHOLDER.matcher(text);
        return m.results().map(r -> new MatchResultView(r.start(), r.end(), r.group(1))).toList();
    }

    record MatchResultView(int start, int end, String key) {
    }
}
