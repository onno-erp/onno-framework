package su.onno.query;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
 * boolean, timestamp-as-epoch-millis, uuid, or null) so it round-trips back to a JDBI-bindable
 * object that compares correctly against its column — a date cursor binds as a {@link Timestamp},
 * not a string. The token is a <em>position</em>, not a credential: every field is re-validated and
 * the value is always bound as a parameter, so a tampered token can only point at a different row,
 * never inject SQL.
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

    private static String typeTag(Object v) {
        if (v == null) return "Z";
        if (v instanceof Boolean) return "B";
        if (v instanceof UUID) return "U";
        if (v instanceof BigDecimal || v instanceof Double || v instanceof Float) return "N";
        if (v instanceof Number) return "L"; // Long/Integer/Short/Byte
        if (v instanceof Timestamp || v instanceof Instant
                || v instanceof LocalDateTime || v instanceof LocalDate) return "T";
        return "S";
    }

    private static String encodeValue(String tag, Object v) {
        if (v == null) return "";
        return switch (tag) {
            case "T" -> Long.toString(epochMillis(v));
            default -> v.toString();
        };
    }

    private static Object decodeValue(String tag, String raw) {
        return switch (tag) {
            case "Z" -> null;
            case "B" -> Boolean.valueOf(raw);
            case "U" -> UUID.fromString(raw);
            case "N" -> new BigDecimal(raw);
            case "L" -> Long.valueOf(raw);
            // Bind dates back as a Timestamp so the comparison matches the column's SQL type rather
            // than relying on driver-specific coercion of an Instant/String.
            case "T" -> new Timestamp(Long.parseLong(raw));
            default -> raw;
        };
    }

    private static long epochMillis(Object v) {
        if (v instanceof Timestamp ts) return ts.getTime();
        if (v instanceof Instant i) return i.toEpochMilli();
        if (v instanceof LocalDateTime ldt) return ldt.toInstant(ZoneOffset.UTC).toEpochMilli();
        if (v instanceof LocalDate ld) return ld.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        throw new IllegalArgumentException("Unsupported temporal type: " + v.getClass());
    }

    private static UUID asUuid(Object v) {
        if (v == null) return null;
        return v instanceof UUID u ? u : UUID.fromString(v.toString());
    }
}
