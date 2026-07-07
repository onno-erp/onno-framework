package su.onno.ui;

import su.onno.metadata.AttributeDescriptor;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds the grouped-aggregate SQL behind the chart/stat/sparkline/gauge widgets (#199): a
 * {@code GROUP BY groupBy[, seriesBy]} computing one (or, for a dual-axis combo, two) aggregate
 * values per bucket, so a chart ships O(buckets) rows instead of the whole table. Date bucketing
 * maps to {@code DATE_TRUNC} (supported by both H2 and PostgreSQL).
 *
 * <p>Injection safety follows {@link WidgetFilter}/{@link WidgetAggregate}: every referenced
 * column must be one of the entity's known columns (or the small system allowlist) and match a
 * strict identifier pattern; values are always bound parameters. Unlike a filter typo (which
 * degrades to "no filter"), an unknown group/series/date column is an error — silently grouping
 * by nothing would draw a <em>wrong</em> chart, not an unfiltered one.</p>
 */
public final class WidgetBuckets {

    private WidgetBuckets() {}

    private static final Logger log = LoggerFactory.getLogger(WidgetBuckets.class);

    /**
     * System columns a widget may group/window on even though they aren't business attributes —
     * per entity kind, because allowlisting a column the kind's table doesn't have (a catalog
     * has no {@code _date}) would pass validation and then blow up in SQL.
     */
    public static final Set<String> CATALOG_SYSTEM_COLUMNS = Set.of("_code", "_description", "_is_folder");
    public static final Set<String> DOCUMENT_SYSTEM_COLUMNS = Set.of("_number", "_date", "_posted");

    private static final Pattern IDENTIFIER = Pattern.compile("^_?[a-z][a-z0-9_]*$");
    private static final Set<String> DATE_UNITS = Set.of("minute", "hour", "day", "week", "month");

    /** More x buckets than this is unreadable anyway; the query is capped and the rest dropped. */
    public static final int MAX_BUCKETS = 1000;

    /**
     * The widget's aggregate request, straight from the query string. {@code metric}/{@code field}
     * are the primary measure ({@link WidgetAggregate} semantics); {@code metric2}/{@code field2}
     * add the optional secondary measure of a combo chart. {@code groupBy} is the x-axis column
     * (blank → one grand-total bucket, the gauge/metric case); {@code groupByDate} buckets a
     * timestamp column by unit. {@code dateField}+{@code from}/{@code to} window the rows to the
     * dashboard's shared time range before aggregating.
     */
    public record Request(String metric, String field, String metric2, String field2,
                          String groupBy, String groupByDate, String seriesBy,
                          String filter, String dateField, String from, String to) {

        public boolean grouped() {
            return groupBy != null && !groupBy.isBlank();
        }

        public boolean hasSeries() {
            return seriesBy != null && !seriesBy.isBlank();
        }

        public boolean hasSecondary() {
            return metric2 != null && !metric2.isBlank();
        }

        public boolean windowed() {
            return dateField != null && !dateField.isBlank()
                    && ((from != null && !from.isBlank()) || (to != null && !to.isBlank()));
        }
    }

    /** The rendered statement plus its bound parameters (the widget filter's bindings included). */
    public record Query(String sql, Map<String, Object> bindings) {}

    /**
     * Render the bucket query. Result columns: {@code _bucket} (absent when un-grouped),
     * {@code _series} (only with {@code seriesBy}), {@code _value}, and {@code _value2} (only for
     * a combo). Ordered by bucket so date buckets come back chronological, capped at
     * {@link #MAX_BUCKETS}{@code + 1} rows so the caller can detect truncation.
     *
     * @throws IllegalArgumentException on an unknown metric/column or date unit
     */
    public static Query build(Request r, String table, Set<String> columns, WidgetFilter.Result filter) {
        Map<String, Object> bindings = new LinkedHashMap<>(filter.bindings());

        StringBuilder select = new StringBuilder("SELECT ");
        String groupExpr = null;
        if (r.grouped()) {
            groupExpr = groupExpression(r.groupBy(), r.groupByDate(), columns);
            select.append(groupExpr).append(" AS _bucket, ");
        }
        if (r.hasSeries()) {
            select.append(requireColumn(r.seriesBy(), columns, "seriesBy")).append(" AS _series, ");
        }
        select.append(WidgetAggregate.expression(r.metric(), r.field(), columns))
                .append(" AS _value");
        if (r.hasSecondary()) {
            select.append(", ").append(WidgetAggregate.expression(r.metric2(), r.field2(), columns))
                    .append(" AS _value2");
        }

        StringBuilder sql = new StringBuilder(select)
                .append(" FROM ").append(table)
                .append(" WHERE _deletion_mark = false");
        appendWindow(sql, bindings, r, columns);
        if (!filter.isEmpty()) {
            sql.append(" AND (").append(filter.sql()).append(")");
        }
        if (r.grouped()) {
            sql.append(" GROUP BY ").append(groupExpr);
            if (r.hasSeries()) sql.append(", ").append(requireColumn(r.seriesBy(), columns, "seriesBy"));
            sql.append(" ORDER BY 1");
            sql.append(" LIMIT ").append(MAX_BUCKETS + 1);
        }
        return new Query(sql.toString(), bindings);
    }

    /**
     * The companion span query — {@code MIN}/{@code MAX} of the window column over the same
     * filtered, windowed row set — so the client can auto-size the date granularity without ever
     * fetching rows. Result columns: {@code _min}, {@code _max}.
     */
    public static Query span(Request r, String table, Set<String> columns, WidgetFilter.Result filter) {
        String col = requireColumn(r.dateField(), columns, "dateField");
        Map<String, Object> bindings = new LinkedHashMap<>(filter.bindings());
        StringBuilder sql = new StringBuilder("SELECT MIN(").append(col).append(") AS _min, MAX(")
                .append(col).append(") AS _max FROM ").append(table)
                .append(" WHERE _deletion_mark = false");
        appendWindow(sql, bindings, r, columns);
        if (!filter.isEmpty()) {
            sql.append(" AND (").append(filter.sql()).append(")");
        }
        return new Query(sql.toString(), bindings);
    }

    /**
     * Execute the request and shape the response the widget client consumes:
     * {@code {buckets: [{key, label?, series?, seriesLabel?, value, value2?}], truncated,
     * span?: {min, max}}}. Bucket/series values that are enum or {@code Ref} UUIDs get a
     * {@code label}/{@code seriesLabel} resolved the same way list rows do, so a "by status" pie
     * shows names, not UUIDs. {@code span} (present when {@code dateField} is set and any rows
     * match) is the MIN/MAX of the window column over the same filtered set — what the client
     * sizes date granularity from without ever fetching rows.
     */
    static Map<String, Object> run(Jdbi jdbi, RefResolver refResolver, List<AttributeDescriptor> attributes,
                                   String table, Set<String> columns, Request r) {
        WidgetFilter.Result wf = WidgetFilter.parse(r.filter(), columns);
        Query q = build(r, table, columns, wf);
        List<Map<String, Object>> rows = jdbi.withHandle(h -> {
            var query = h.createQuery(q.sql());
            q.bindings().forEach(query::bind);
            return query.mapToMap().list();
        });
        boolean truncated = rows.size() > MAX_BUCKETS;
        if (truncated) {
            log.warn("Widget aggregate over {} produced more than {} buckets; result truncated. "
                    + "Group by a coarser column or date unit.", table, MAX_BUCKETS);
            rows = rows.subList(0, MAX_BUCKETS);
        }

        String groupCol = r.grouped() ? r.groupBy().toLowerCase() : null;
        String seriesCol = r.hasSeries() ? r.seriesBy().toLowerCase() : null;
        List<Map<String, Object>> labelRows = List.of();
        if (groupCol != null || seriesCol != null) {
            labelRows = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> lr = new LinkedHashMap<>();
                if (groupCol != null) lr.put(groupCol, row.get("_bucket"));
                if (seriesCol != null) lr.put(seriesCol, row.get("_series"));
                labelRows.add(lr);
            }
            refResolver.resolveAttributes(labelRows, attributes);
        }

        List<Map<String, Object>> buckets = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            Map<String, Object> b = new LinkedHashMap<>();
            if (groupCol != null) {
                b.put("key", jsonValue(row.get("_bucket")));
                Object display = labelRows.get(i).get(groupCol + "_display");
                if (display != null) b.put("label", String.valueOf(display));
            }
            if (seriesCol != null) {
                b.put("series", jsonValue(row.get("_series")));
                Object display = labelRows.get(i).get(seriesCol + "_display");
                if (display != null) b.put("seriesLabel", String.valueOf(display));
            }
            b.put("value", row.get("_value"));
            if (r.hasSecondary()) b.put("value2", row.get("_value2"));
            buckets.add(b);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("buckets", buckets);
        out.put("truncated", truncated);
        if (r.dateField() != null && !r.dateField().isBlank()) {
            Query sq = span(r, table, columns, wf);
            Map<String, Object> span = jdbi.withHandle(h -> {
                var query = h.createQuery(sq.sql());
                sq.bindings().forEach(query::bind);
                return query.mapToMap().one();
            });
            if (span.get("_min") != null) {
                out.put("span", Map.of("min", jsonValue(span.get("_min")),
                        "max", jsonValue(span.get("_max"))));
            }
        }
        return out;
    }

    /** Timestamps → ISO strings, UUIDs → strings; JSON-native scalars pass through. */
    private static Object jsonValue(Object v) {
        if (v == null || v instanceof Boolean || v instanceof Number || v instanceof String) return v;
        if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toString();
        if (v instanceof java.time.temporal.Temporal) return v.toString();
        return String.valueOf(v);
    }

    private static void appendWindow(StringBuilder sql, Map<String, Object> bindings,
                                     Request r, Set<String> columns) {
        if (!r.windowed()) return;
        String col = requireColumn(r.dateField(), columns, "dateField");
        if (r.from() != null && !r.from().isBlank()) {
            sql.append(" AND ").append(col).append(" >= CAST(:_window_from AS TIMESTAMP)");
            bindings.put("_window_from", r.from());
        }
        if (r.to() != null && !r.to().isBlank()) {
            sql.append(" AND ").append(col).append(" < CAST(:_window_to AS TIMESTAMP)");
            bindings.put("_window_to", r.to());
        }
    }

    private static String groupExpression(String groupBy, String groupByDate, Set<String> columns) {
        String col = requireColumn(groupBy, columns, "groupBy");
        if (groupByDate == null || groupByDate.isBlank()) {
            return col;
        }
        String unit = groupByDate.toLowerCase();
        if (!DATE_UNITS.contains(unit)) {
            throw new IllegalArgumentException("Unknown groupByDate unit: " + groupByDate);
        }
        return "DATE_TRUNC('" + unit + "', " + col + ")";
    }

    private static String requireColumn(String name, Set<String> columns, String what) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(what + " is required");
        }
        String col = name.toLowerCase();
        if (!IDENTIFIER.matcher(col).matches() || !columns.contains(col)) {
            throw new IllegalArgumentException("Unknown " + what + " column: " + name);
        }
        return col;
    }
}
