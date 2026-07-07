package su.onno.ui.comments;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The on-the-wire syntax for a comment link: {@code @[Display](kind/name/id)} for a mention and
 * {@code #[Display](kind/name/id)} for a reference. Keeping the link <em>in</em> the text
 * (rather than in a side table) means {@code Comment.body} stays a single string. The
 * {@code Display} carried in the token is only a snapshot for fallback; the live display,
 * avatar and read access are resolved at render time by {@link MentionResolver}.
 *
 * <p>This class is a pure, side-effect-free parser/serializer (round-trip tested). The {@code name}
 * segment is the URL-safe route name (snake_case), so the same token body is also a navigable
 * {@code onno://} route on the client.
 */
public final class Mentions {

    /**
     * {@code @[label](kind/name/id)} or {@code #[label](kind/name/id)} — label is any run of
     * non-{@code ]} characters; name is a route segment (no slash/paren/whitespace); id is a
     * canonical UUID. Anchored loosely so a token can sit anywhere in prose.
     */
    private static final Pattern TOKEN = Pattern.compile(
            "([@#])\\[([^\\]]+)\\]\\((catalogs|documents)/([^/)\\s]+)/"
                    + "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\)");

    private Mentions() {
    }

    /** One mention occurrence found in a body: its ref, the snapshot label, and the exact matched text. */
    public record Occurrence(char marker, MentionRef ref, String label, String token) {
    }

    /** Serialize a mention to its token form. The label is sanitized so it can't break the syntax. */
    public static String token(String label, MentionRef ref) {
        return token('@', label, ref);
    }

    /** Serialize a mention or reference to its token form. */
    public static String token(char marker, String label, MentionRef ref) {
        char safeMarker = marker == '#' ? '#' : '@';
        return safeMarker + "[" + sanitizeLabel(label) + "](" + ref.route() + ")";
    }

    /** A label safe to embed between {@code [} and {@code ]}: collapse newlines and drop {@code ]}. */
    public static String sanitizeLabel(String label) {
        if (label == null || label.isBlank()) {
            return "?";
        }
        String cleaned = label.replace("]", "").replace("\r", " ").replace("\n", " ").trim();
        return cleaned.isEmpty() ? "?" : cleaned;
    }

    /** Every mention occurrence in body order (duplicates included), or empty when there are none. */
    public static List<Occurrence> occurrences(String body) {
        List<Occurrence> out = new ArrayList<>();
        if (body == null || body.isEmpty()) {
            return out;
        }
        Matcher m = TOKEN.matcher(body);
        while (m.find()) {
            UUID id = parseUuid(m.group(5));
            if (id == null) {
                continue;
            }
            out.add(new Occurrence(m.group(1).charAt(0), new MentionRef(m.group(3), m.group(4), id), m.group(2), m.group()));
        }
        return out;
    }

    /** The distinct mention refs in a body, first-seen order preserved. */
    public static List<MentionRef> parse(String body) {
        Map<MentionRef, Boolean> seen = new LinkedHashMap<>();
        for (Occurrence o : occurrences(body)) {
            seen.putIfAbsent(o.ref(), Boolean.TRUE);
        }
        return new ArrayList<>(seen.keySet());
    }

    /** The distinct refs for one marker ({@code @} mentions or {@code #} references), first-seen. */
    public static List<MentionRef> parse(String body, char marker) {
        Map<MentionRef, Boolean> seen = new LinkedHashMap<>();
        for (Occurrence o : occurrences(body)) {
            if (o.marker() == marker) {
                seen.putIfAbsent(o.ref(), Boolean.TRUE);
            }
        }
        return new ArrayList<>(seen.keySet());
    }

    /**
     * Rewrite a body, degrading every mention whose ref satisfies {@code degrade} to its plain label
     * text (the token's display snapshot). Used on POST to strip mentions the author can't read, so a
     * stored comment never smuggles a clickable link to a hidden record.
     */
    public static String degrade(String body, java.util.function.Predicate<MentionRef> degrade) {
        if (body == null || body.isEmpty()) {
            return body;
        }
        Matcher m = TOKEN.matcher(body);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            UUID id = parseUuid(m.group(5));
            boolean strip = id != null && degrade.test(new MentionRef(m.group(3), m.group(4), id));
            m.appendReplacement(out, Matcher.quoteReplacement(strip ? m.group(2) : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * The URL-safe route segment for a logical name: snake_case, mirroring the client's
     * {@code toSnakeCase} (and {@code SurfaceDivBuilder.routeNameOf}) so a mention token's route is the
     * same one a list-row tap or ref link produces.
     */
    public static String routeName(String logicalName) {
        return logicalName == null ? "" : logicalName
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .replaceAll("\\s+", "_")
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
