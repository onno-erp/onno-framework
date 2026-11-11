package su.onno.query;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * An opaque, self-describing position in a keyset-paginated list — the "where I left off" token a
 * client echoes back to fetch the next window. It captures the last row of a page as the pair the
 * seek predicate needs: the value of the sort column and the {@code _id} tiebreaker, plus the sort
 * column + direction it was minted for so a stale cursor (the client changed the sort) can be
 * detected and ignored rather than silently returning a wrong window.
 *
 * <p>The wire form is URL-safe Base64 of a tagged, unit-separated record:
 * {@code v1␟{column}␟{a|d}␟{typeTag}␟{value}␟{uuid}}. The value is typed (string, long, decimal,
 * boolean, local date-time at full nanosecond precision, uuid, or null) so it round-trips back to a
 * JDBI-bindable object that compares correctly against its column — a date cursor binds as a
 * {@link Timestamp}, not a string. Full precision matters: a document date defaults to
 * {@code LocalDateTime.now()} (microseconds), and a truncated cursor value would move the seek
 * boundary, repeating or skipping rows that share the truncated instant. Tokens minted with the
 * earlier epoch-millis encoding still decode. The token is a <em>position</em>, not a credential: every field is re-validated and
 * the value is always bound as a parameter, so a tampered token can only point at a different row,
 * never inject SQL.
 *
 * <p>The scalar record and v1 methods below are retained for Java source compatibility. Public
 * collection endpoints use {@link Window} and {@link #encodeWindow(String, java.util.List)} instead:
 * v2 binds every sort value to the complete feed context. A valid token from another context is
 * rejected; a malformed position restarts the feed.
 *
 * @param column     the sort column this cursor was minted for (validated against the entity)
 * @param descending the sort direction it was minted for
 * @param value      the sort column's value on the cursor row, or {@code null} (NULLS-LAST tail)
 * @param id         the {@code _id} tiebreaker of the cursor row
 */
public record Cursor(String column, boolean descending, Object value, UUID id) {

    private static final char SEP = ''; // ASCII unit separator — never appears in real data
    private static final String VERSION = "v1";

    /** Build the cursor that points at {@code row} as the last row of the current page. */
    public static Cursor from(String column, boolean descending, Map<String, Object> row) {
        return new Cursor(column, descending, row.get(column), asUuid(row.get("_id")));
    }

    /**
     * Decode a token, but only if it still matches the active sort — a cursor minted for a different
     * column or direction (the client re-sorted) is treated as absent so paging restarts cleanly
     * from the first window. A blank or malformed token likewise yields {@code null}.
     */
    public static Cursor decodeFor(String token, String column, boolean descending) {
        Cursor c = decode(token);
        if (c == null || !c.column.equals(column) || c.descending != descending) {
            return null;
        }
        return c;
    }

    /**
     * Decode a subject-bound token using the active sort. An unparseable position restarts the
     * feed; a token carrying a different access-scope fingerprint is rejected. The fingerprint
     * is supplied by trusted access-policy code, never by the client. Transport adapters should
     * map {@link ScopeMismatchException} to a bad-request response.
     */
    public static Cursor decodeFor(String token, String column, boolean descending, String fingerprint) {
        if (token == null || token.isBlank()) return null;
        int seal = token.lastIndexOf('~');
        String raw = seal < 0 ? token : token.substring(0, seal);
        String minted = seal < 0 ? "" : token.substring(seal + 1);
        String expected = fingerprint == null ? "" : fingerprint;
        Cursor position = decode(raw);
        if (position == null) return null;
        if (!minted.equals(expected)) throw new ScopeMismatchException();
        return decodeFor(raw, column, descending);
    }

    /** A valid token cannot be reused across access scopes. */
    public static final class ScopeMismatchException extends IllegalArgumentException {
        private ScopeMismatchException() {
            super("Cursor was issued for a different user");
        }
    }

    /**
     * Encode this position with an optional access-scope fingerprint. Preserves the existing
     * wire format: an unrestricted token is unchanged; a restricted token ends in ~fingerprint.
     * This binding is not a signature or authorization check: always scope the SQL query too.
     */
    public String encode(String fingerprint) {
        String token = encode();
        return fingerprint == null || fingerprint.isEmpty() ? token : token + '~' + fingerprint;
    }

    /** Decode a token to a cursor, or {@code null} if it is blank or not a well-formed v1 token. */
    public static Cursor decode(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = raw.split(String.valueOf(SEP), -1);
            if (parts.length != 6 || !VERSION.equals(parts[0])) {
                return null;
            }
            if (!("d".equals(parts[2]) || "a".equals(parts[2]))) return null;
            if (!java.util.Set.of("S", "Z", "B", "U", "N", "L", "D", "T", "A").contains(parts[3])) return null;
            boolean descending = "d".equals(parts[2]);
            Object value = decodeValue(parts[3], parts[4]);
            return new Cursor(parts[1], descending, value, UUID.fromString(parts[5]));
        } catch (RuntimeException ignored) {
            return null; // an unparseable cursor reads as "start over", never a 500
        }
    }

    /** The URL-safe token to hand back to the client as {@code nextCursor}. */
    public String encode() {
        String tag = typeTag(value);
        String raw = VERSION + SEP + column + SEP + (descending ? "d" : "a")
                + SEP + tag + SEP + encodeValue(tag, value) + SEP + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** A typed position for an arbitrary, uniquely ordered SQL tuple. */
    public record Window(java.util.List<Object> values) {
        public Window {
            values = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(values));
        }
    }

    /** Encode a tuple for one feed, sort, filters and access scope. */
    public static String encodeWindow(String context, java.util.List<Object> values) {
        StringBuilder raw = new StringBuilder("v2").append(SEP).append(component(context));
        for (Object value : values) {
            String tag = typeTag(value);
            raw.append(SEP).append(tag).append(SEP).append(component(encodeValue(tag, value)));
        }
        return component(raw.toString());
    }

    /**
     * Decode a tuple. Malformed tokens restart; a well-formed token for a different feed, sort,
     * filter or access scope is rejected. Bound values are never SQL fragments.
     */
    public static Window decodeWindow(String token, String context, int size) {
        if (token == null || token.isBlank() || token.length() > 65536) return null;
        if (decode(token.split("~", 2)[0]) != null) throw new ScopeMismatchException();
        String issued;
        java.util.List<Object> values = new java.util.ArrayList<>();
        try {
            String[] parts = uncomponent(token).split(String.valueOf(SEP), -1);
            if (parts.length < 4 || !"v2".equals(parts[0]) || parts.length % 2 != 0) return null;
            issued = uncomponent(parts[1]);
            for (int n = 2; n < parts.length; n += 2) {
                String tag = parts[n];
                String value = uncomponent(parts[n + 1]);
                if (!java.util.Set.of("S", "Z", "B", "U", "N", "L", "D", "T", "A").contains(tag)) return null;
                if ("B".equals(tag) && !"true".equals(value) && !"false".equals(value)) return null;
                values.add(decodeValue(tag, value));
            }
        } catch (RuntimeException malformed) {
            return null;
        }
        if (!issued.equals(context)) throw new ScopeMismatchException();
        return values.size() == size ? new Window(values) : null;
    }

    /** Fingerprint the complete requesting identity, including unrestricted users. */
    public static String subjectContext(su.onno.access.AccessSubject subject) {
        if (subject instanceof su.onno.access.AccessSubject.User(var username, var roles, var recordId, var attributes))
            return context(username, recordId, roles, attributes);
        return "system";
    }

    /** Stable, opaque context key. Maps/sets are canonicalized, so nodes agree on its identity. */
    public static String context(Object... parts) {
        StringBuilder value = new StringBuilder();
        for (Object part : parts) canonical(value, part);
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void canonical(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null;");
            case Map<?, ?> map -> {
                out.append('{');
                map.entrySet().stream().sorted(java.util.Comparator.comparing(e -> String.valueOf(e.getKey())))
                        .forEach(e -> { canonical(out, e.getKey()); canonical(out, e.getValue()); });
                out.append('}');
            }
            case java.util.Set<?> set -> {
                out.append('[');
                set.stream().map(String::valueOf).sorted().forEach(v -> canonical(out, v));
                out.append(']');
            }
            case Iterable<?> list -> {
                out.append('[');
                list.forEach(v -> canonical(out, v));
                out.append(']');
            }
            default -> {
                String text = value.toString();
                out.append(value.getClass().getName()).append(':').append(text.length()).append(':').append(text).append(';');
            }
        }
    }

    private static String component(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String uncomponent(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String typeTag(Object v) {
        if (v == null) return "Z";
        if (v instanceof java.sql.Date || v instanceof LocalDate) return "A";
        if (v instanceof Boolean) return "B";
        if (v instanceof UUID) return "U";
        if (v instanceof BigDecimal || v instanceof Double || v instanceof Float) return "N";
        if (v instanceof Number) return "L"; // Long/Integer/Short/Byte
        if (v instanceof Timestamp || v instanceof Instant
                || v instanceof LocalDateTime) return "D";
        return "S";
    }

    private static String encodeValue(String tag, Object v) {
        if (v == null) return "";
        return "D".equals(tag) ? localDateTime(v).toString() : v.toString();
    }

    private static Object decodeValue(String tag, String raw) {
        return switch (tag) {
            case "Z" -> null;
            case "A" -> java.sql.Date.valueOf(LocalDate.parse(raw));
            case "B" -> Boolean.valueOf(raw);
            case "U" -> UUID.fromString(raw);
            case "N" -> new BigDecimal(raw);
            case "L" -> Long.valueOf(raw);
            // Bind dates back as a Timestamp so the comparison matches the column's SQL type rather
            // than relying on driver-specific coercion of an Instant/String.
            case "D" -> Timestamp.valueOf(LocalDateTime.parse(raw));
            // Legacy (pre-precision) tokens: epoch millis.
            case "T" -> new Timestamp(Long.parseLong(raw));
            default -> raw;
        };
    }

    /**
     * The value as the wall-clock date-time a {@link Timestamp} bind compares against, nanoseconds
     * kept. {@link Timestamp} and {@link Instant} go through the JVM zone exactly as the JDBC
     * driver binds a {@link Timestamp}, so the decoded cursor equals the row it was minted from.
     */
    private static LocalDateTime localDateTime(Object v) {
        if (v instanceof Timestamp ts) return ts.toLocalDateTime();
        if (v instanceof Instant i) return Timestamp.from(i).toLocalDateTime();
        if (v instanceof LocalDateTime ldt) return ldt;
        if (v instanceof LocalDate ld) return ld.atStartOfDay();
        throw new IllegalArgumentException("Unsupported temporal type: " + v.getClass());
    }

    private static UUID asUuid(Object v) {
        if (v == null) return null;
        return v instanceof UUID u ? u : UUID.fromString(v.toString());
    }
}
