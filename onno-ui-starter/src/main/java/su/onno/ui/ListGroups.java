package su.onno.ui;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared helpers for the list <b>grouping</b> query (see {@code CatalogQueryService#groups} /
 * {@code DocumentQueryService#groups}). Grouping is backend {@code GROUP BY}: one header row per
 * distinct value of the group column — or, for a date/time column, per day/month/year <em>bucket</em>
 * (via {@code DATE_TRUNC}, a syntax both H2 and PostgreSQL share).
 *
 * <p>Each group carries an <b>expand</b> descriptor: the filter params the client appends to the
 * normal list feed to load that group's rows. A discrete value expands with {@code eq}; a date
 * bucket expands with a {@code ge}/{@code le} range covering the period. A {@code null} group is
 * shown (count + subtotals) but not expandable — its expand list is empty.</p>
 */
public final class ListGroups {

    private ListGroups() {}

    /** Cap on the number of group headers returned, so a high-cardinality group can't stream forever. */
    public static final int MAX_GROUPS = 200;

    private static final DateTimeFormatter YEAR = DateTimeFormatter.ofPattern("yyyy", Locale.ROOT);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00", Locale.ROOT);
    // A bound value the ge/le filter binds and the DB casts to the column's temporal type. Keeps
    // milliseconds so an end-of-bucket bound (…59.999) doesn't drop a row late in the last second.
    private static final DateTimeFormatter BOUND = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);

    /** One aggregate to compute per group: an aggregate {@code fn} over a numeric {@code column}. */
    public record Agg(String fn, String column) {}

    /** The result of a grouping query: the group headers, and whether the cap truncated them. */
    public record GroupResult(List<Map<String, Object>> groups, boolean capped) {}

    /** Whether a Java type is a date/time — so grouping buckets it by period rather than exact value. */
    public static boolean isTemporalType(Class<?> t) {
        return t == LocalDate.class || t == LocalDateTime.class || t == Instant.class
                || t == java.time.OffsetDateTime.class || t == java.time.ZonedDateTime.class
                || t == java.util.Date.class || t == java.sql.Date.class || t == java.sql.Timestamp.class;
    }

    /** Restrict a client-supplied granularity to a known {@code DATE_TRUNC} unit; default month. */
    public static String safeGranularity(String granularity) {
        String s = granularity == null ? "" : granularity.toLowerCase(Locale.ROOT);
        return switch (s) {
            case "hour", "day", "week", "month", "year" -> s;
            default -> "month";
        };
    }

    /**
     * The SQL the {@code GROUP BY} runs on: the bare column for a discrete group, or a
     * {@code DATE_TRUNC('unit', col)} bucket for a date column. The unit is whitelisted and the column
     * is a validated identifier, so neither carries arbitrary SQL.
     */
    public static String groupExpression(String column, boolean date, String granularity) {
        if (!date) {
            return column;
        }
        return "DATE_TRUNC('" + safeGranularity(granularity) + "', " + column + ")";
    }

    /** The display label for a group value: a formatted period for a date bucket, else the raw value. */
    public static String bucketLabel(Object truncated, String granularity) {
        LocalDateTime dt = toLocalDateTime(truncated);
        if (dt == null) {
            return "";
        }
        DateTimeFormatter fmt = switch (safeGranularity(granularity)) {
            case "hour" -> HOUR;
            case "day", "week" -> DAY;
            case "year" -> YEAR;
            default -> MONTH;
        };
        return dt.format(fmt);
    }

    /**
     * The expand filter for a date bucket: a {@code ge}/{@code le} range from the bucket start to one
     * millisecond before the next bucket, so the rows the client loads are exactly the group's.
     */
    public static List<Map<String, Object>> bucketExpand(String column, Object truncated, String granularity) {
        LocalDateTime start = toLocalDateTime(truncated);
        if (start == null) {
            return List.of();
        }
        LocalDateTime next = switch (safeGranularity(granularity)) {
            case "hour" -> start.plusHours(1);
            case "day" -> start.plusDays(1);
            case "week" -> start.plusWeeks(1);
            case "year" -> start.plusYears(1);
            default -> start.plusMonths(1);
        };
        LocalDateTime endInclusive = next.minusNanos(1_000_000); // 1 ms before the next bucket
        return List.of(
                param("ge", column, start.format(BOUND)),
                param("le", column, endInclusive.format(BOUND)));
    }

    /** The expand filter for a discrete value: {@code eq}, or empty for a null group (not expandable). */
    public static List<Map<String, Object>> discreteExpand(String column, Object value) {
        if (value == null) {
            return List.of();
        }
        return List.of(param("eq", column, String.valueOf(value)));
    }

    /**
     * Turn the raw grouped result rows (each aliased {@code {groupColumn}}, {@code _count}, and
     * {@code a0..aN} for the aggregates) into the client group headers: {@code label} (resolved
     * ref/enum label or formatted date bucket), optional {@code color} (enum pill), {@code count},
     * the aggregate {@code values} (aligned with the requested aggregates), and the {@code expand}
     * filter to load the group's rows. {@code rows} must already be ref-resolved by the caller.
     */
    public static List<Map<String, Object>> buildGroups(List<Map<String, Object>> rows, String groupColumn,
                                                        boolean date, String granularity, List<Agg> aggregates) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object value = row.get(groupColumn);
            Map<String, Object> g = new LinkedHashMap<>();
            if (date) {
                g.put("label", bucketLabel(value, granularity));
            } else {
                Object display = row.get(groupColumn + "_display");
                g.put("label", display != null ? String.valueOf(display)
                        : (value == null ? "" : String.valueOf(value)));
                Object color = row.get(groupColumn + "_color");
                if (color != null) {
                    g.put("color", color);
                }
            }
            Object count = row.get("_count");
            g.put("count", count instanceof Number n ? n.longValue() : 0L);
            List<Object> values = new ArrayList<>(aggregates.size());
            for (int i = 0; i < aggregates.size(); i++) {
                values.add(row.get("a" + i));
            }
            g.put("values", values);
            g.put("expand", date ? bucketExpand(groupColumn, value, granularity)
                    : discreteExpand(groupColumn, value));
            out.add(g);
        }
        return out;
    }

    private static Map<String, Object> param(String op, String column, String value) {
        return Map.of("op", op, "column", column, "value", value);
    }

    /** Normalize whatever the JDBC driver returns for a {@code DATE_TRUNC} value to a LocalDateTime. */
    private static LocalDateTime toLocalDateTime(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDateTime dt) {
            return dt;
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (v instanceof LocalDate d) {
            return d.atStartOfDay();
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate().atStartOfDay();
        }
        if (v instanceof Instant i) {
            return LocalDateTime.ofInstant(i, ZoneOffset.UTC);
        }
        if (v instanceof java.util.Date d) {
            return LocalDateTime.ofInstant(d.toInstant(), ZoneOffset.UTC);
        }
        String s = v.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(s.replace(' ', 'T'));
        } catch (Exception ignored) {
            try {
                return LocalDate.parse(s.substring(0, Math.min(10, s.length()))).atStartOfDay();
            } catch (Exception ignored2) {
                return null;
            }
        }
    }
}
