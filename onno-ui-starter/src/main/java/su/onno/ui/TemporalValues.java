package su.onno.ui;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Coerces loosely-typed values — JSON strings from a write request, or JDBC
 * {@code Date}/{@code Timestamp} read back from a row — into the {@code java.time} types the
 * schema generator maps to columns: {@link LocalDate} ({@code DATE}) and {@link LocalDateTime}
 * ({@code TIMESTAMP}).
 *
 * <p>JSON has no temporal type, so a date/datetime attribute always arrives as a string on write.
 * Binding that string straight to a {@code date}/{@code timestamp} column makes JDBI send a
 * {@code varchar}, which H2 silently coerces but PostgreSQL refuses ({@code "column is of type
 * date but expression is of type character varying"}). Parsing here lets the write path bind a
 * typed value both databases accept. Shared by the catalog and document write paths
 * ({@code coerceAttribute}) and the document reconstruct path. (#163)
 */
final class TemporalValues {

    private TemporalValues() {
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
            return toLocalDateTime(value);
        }
        return null;
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
     * Coerce a value into a {@link LocalDateTime}, accepting {@code Timestamp}/{@code Date}
     * instances and both {@code T}- and space-separated strings (H2 returns the latter for
     * TIMESTAMP columns).
     */
    static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime ldt) return ldt;
        if (value instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        if (value instanceof LocalDate ld) return ld.atStartOfDay();
        if (value instanceof java.sql.Date d) return d.toLocalDate().atStartOfDay();
        return LocalDateTime.parse(value.toString().replace(' ', 'T'));
    }
}
