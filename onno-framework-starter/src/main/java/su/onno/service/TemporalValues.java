package su.onno.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Coerces temporal values at two different trust boundaries: strict JSON write values and
 * permissive JDBC read values. Write-side {@link LocalDateTime} strings must be offset-free ISO
 * wall time; read normalization still accepts driver-specific timestamp objects and strings.
 *
 * <p>JSON has no temporal type, so a date/datetime attribute always arrives as a string on write.
 * Binding that string straight to a {@code date}/{@code timestamp} column makes JDBI send a
 * {@code varchar}, which H2 silently coerces but PostgreSQL refuses ({@code "column is of type
 * date but expression is of type character varying"}). Parsing here lets the write path bind a
 * typed value both databases accept. Shared by the catalog and document write paths
 * ({@code coerceAttribute}) and the document reconstruct path. (#163)
 */
final class TemporalValues {

    static final String LOCAL_DATE_TIME_WRITE_MESSAGE =
            "must be an offset-free ISO local date-time (yyyy-MM-ddTHH:mm[:ss[.fraction]])";

    static final String LOCAL_TIME_WRITE_MESSAGE =
            "must be an ISO local time (HH:mm[:ss[.fraction]])";

    static final String INSTANT_WRITE_MESSAGE =
            "must be an ISO instant with an explicit offset (yyyy-MM-ddTHH:mm[:ss[.fraction]]Z or ±HH:mm)";

    private TemporalValues() {
    }

    /**
     * The field-level message for a temporal {@code targetType} whose write value failed to
     * parse, or {@code null} when the type has no specific message.
     */
    static String writeMessage(Class<?> targetType) {
        if (targetType == LocalDateTime.class) return LOCAL_DATE_TIME_WRITE_MESSAGE;
        if (targetType == LocalTime.class) return LOCAL_TIME_WRITE_MESSAGE;
        if (targetType == Instant.class) return INSTANT_WRITE_MESSAGE;
        return null;
    }

    /**
     * Coerce {@code value} to {@code targetType} when it is a temporal column type, else return
     * {@code null} to signal "not a temporal type — leave the value as it is". Callers pass a
     * non-null {@code value}; the two temporal coercions never return null, so {@code null}
     * unambiguously means "not handled here".
     */
    static Object coerce(Class<?> targetType, Object value) {
        if (targetType == LocalDate.class) {
            return toLocalDate(value);
        }
        if (targetType == LocalDateTime.class) {
            return toWriteLocalDateTime(value);
        }
        if (targetType == LocalTime.class) {
            return toLocalTime(value);
        }
        if (targetType == Instant.class) {
            return toWriteInstant(value);
        }
        return null;
    }

    /**
     * Coerce a value into a {@link LocalTime} — a zone-less time of day. Accepts a typed value, a
     * JDBC {@code java.sql.Time}, or an ISO {@code HH:mm[:ss[.fraction]]} string. Used on both the
     * write and the read side: a time of day carries no offset to mishandle.
     */
    static LocalTime toLocalTime(Object value) {
        if (value instanceof LocalTime lt) return lt;
        if (value instanceof java.sql.Time t) return t.toLocalTime();
        if (value instanceof LocalDateTime ldt) return ldt.toLocalTime();
        try {
            return LocalTime.parse(value.toString().trim(), DateTimeFormatter.ISO_LOCAL_TIME);
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException(LOCAL_TIME_WRITE_MESSAGE, invalid);
        }
    }

    /**
     * Parse an external write value for an {@link Instant}. An instant is an absolute moment, so a
     * JSON string must say which one: an ISO date-time with {@code Z} or a numeric offset. An
     * offset-free value is rejected rather than guessed against the server's zone — the mirror
     * image of {@link #toWriteLocalDateTime}, which rejects offset-bearing values.
     */
    static Instant toWriteInstant(Object value) {
        if (value instanceof Instant i) return i;
        if (value instanceof OffsetDateTime odt) return odt.toInstant();
        if (value instanceof ZonedDateTime zdt) return zdt.toInstant();
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        try {
            return OffsetDateTime.parse(value.toString().trim(),
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException(INSTANT_WRITE_MESSAGE, invalid);
        }
    }

    /**
     * Normalize a JDBC/read-side {@code TIMESTAMP WITH TIME ZONE} value into an {@link Instant}.
     * PostgreSQL surfaces the column as {@code java.sql.Timestamp}, H2 as {@code OffsetDateTime};
     * both denote the same absolute moment. A string keeps its offset (space-separated accepted).
     */
    static Instant toInstant(Object value) {
        if (value instanceof Instant i) return i;
        if (value instanceof OffsetDateTime odt) return odt.toInstant();
        if (value instanceof ZonedDateTime zdt) return zdt.toInstant();
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        String s = value.toString().trim().replace(' ', 'T');
        // Drivers render a bare hour offset ("+00") that ISO_OFFSET_DATE_TIME refuses.
        if (s.matches(".*[+-]\\d{2}$")) s = s + ":00";
        return OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
    }

    /**
     * Parse an external write value for a {@link LocalDateTime}. Offset- or zone-bearing values are
     * rejected because silently discarding that information can persist a different moment than
     * the caller intended. Already-typed JDBC values remain accepted for programmatic command
     * callers; JSON strings must use the canonical offset-free ISO representation.
     */
    static LocalDateTime toWriteLocalDateTime(Object value) {
        if (value instanceof LocalDateTime ldt) return ldt;
        if (value instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        if (value instanceof LocalDate ld) return ld.atStartOfDay();
        if (value instanceof java.sql.Date d) return d.toLocalDate().atStartOfDay();
        if (value instanceof OffsetDateTime || value instanceof ZonedDateTime) {
            throw new IllegalArgumentException(LOCAL_DATE_TIME_WRITE_MESSAGE);
        }
        try {
            return LocalDateTime.parse(value.toString(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException(LOCAL_DATE_TIME_WRITE_MESSAGE, invalid);
        }
    }

    /**
     * Coerce a value into a {@link LocalDate}. The source may be a {@code LocalDate}, a
     * {@code java.sql.Date}/{@code Timestamp} from a row, or a string — and a TIMESTAMP renders as
     * {@code "2026-06-04 08:44:44.4"} (space, not {@code T}), so a strict {@code LocalDate.parse}
     * would fail at the space. Take just the date part.
     */
    static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate ld) return ld;
        if (value instanceof LocalDateTime ldt) return ldt.toLocalDate();
        if (value instanceof java.sql.Date d) return d.toLocalDate();
        if (value instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        String s = value.toString();
        return LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s);
    }

    /**
     * Normalize a JDBC/read-side value into a {@link LocalDateTime}, accepting
     * {@code Timestamp}/{@code Date} instances and both {@code T}- and space-separated strings (H2
     * returns the latter for TIMESTAMP columns). Offset-bearing driver values retain their local
     * fields; this method is not used to validate external writes.
     */
    static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime ldt) return ldt;
        // LocalDateTime is a wall-clock business value. If a JDBC/Jackson boundary supplied an
        // offset or zone, keep the displayed local fields; do not shift them to the server zone.
        if (value instanceof OffsetDateTime odt) return odt.toLocalDateTime();
        if (value instanceof ZonedDateTime zdt) return zdt.toLocalDateTime();
        if (value instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        if (value instanceof LocalDate ld) return ld.atStartOfDay();
        if (value instanceof java.sql.Date d) return d.toLocalDate().atStartOfDay();
        // ISO_DATE_TIME accepts both offset-free and offset-bearing values (including trailing Z);
        // LocalDateTime.from deliberately retains the wall-clock component and ignores the offset.
        return LocalDateTime.from(DateTimeFormatter.ISO_DATE_TIME.parse(
                value.toString().replace(' ', 'T')));
    }
}
