package su.onno.web;

import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * A UI route to one catalog or document record: {@code catalogs|documents/{name}/{id}}, with a leading
 * slash, a query, a fragment and trailing segments allowed. The one parser for record links carried as
 * strings (notification links, presence routes), so the REST feed, the live stream and presence read
 * them alike.
 *
 * @param kind {@code catalogs} or {@code documents}
 * @param name the entity segment, percent-decoded (verbatim when an escape in it is malformed)
 * @param id   the record id
 */
public record RecordRoute(String kind, String name, UUID id) {

    /**
     * The record {@code route} addresses, or empty when it addresses none: a page, a register, a list
     * (also one focused on a record with {@code ?focus=}) or a {@code new} draft.
     */
    public static Optional<RecordRoute> parse(String route) {
        if (route == null) return Optional.empty();
        String[] parts = Arrays.stream(route.split("[/?#]")).filter(p -> !p.isBlank()).toArray(String[]::new);
        if (parts.length < 3 || !isRecordKind(parts[0])) return Optional.empty();
        UUID id = uuidOrNull(parts[2]);
        return id == null ? Optional.empty() : Optional.of(new RecordRoute(parts[0], decodeSegment(parts[1]), id));
    }

    /** Whether {@code kind} is a route kind whose third segment is a record id. */
    public static boolean isRecordKind(String kind) {
        return "catalogs".equals(kind) || "documents".equals(kind);
    }

    /** {@code value} as a UUID, or {@code null} when it is not one. */
    public static UUID uuidOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof UUID u) return u;
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    /** Percent-decode one route segment, keeping {@code +} literal; a malformed escape leaves it verbatim. */
    public static String decodeSegment(String segment) {
        if (segment == null || segment.indexOf('%') < 0) return segment;
        try {
            return UriUtils.decode(segment, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return segment;
        }
    }
}
