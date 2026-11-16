package su.onno.query;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.MapMapper;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Bounded SQL seek windows, also supporting composite natural keys and mixed sort directions.
 * All SQL identifiers/fragments must be trusted metadata. The final ordering tuple must be unique.
 * Visibility predicates must be supplied in SQL, before the limit. No total is queried.
 */
public final class KeysetWindow {
    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 500;

    private KeysetWindow() {}

    /** One component of the total ordering; nulls always follow non-null values. */
    public record Order(String column, boolean descending) {}

    /** A collection response. Mapping preserves its raw SQL continuation boundary. */
    public record Page<T>(List<T> rows, String nextCursor, boolean hasMore) {
        public <R> Page<R> map(Function<T, R> mapper) {
            return new Page<>(rows.stream().map(mapper).toList(), nextCursor, hasMore);
        }
    }

    public static int clampLimit(int limit) {
        return limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    }

    public static Page<Map<String, Object>> fetch(Jdbi jdbi, String source, String projection,
            String where, Consumer<Query> binder, List<Order> order, String token, int limit, String context) {
        return jdbi.withHandle(h -> fetch(h, source, projection, where, binder, order, token, limit,
                context, new MapMapper()));
    }

    /** Fetch on the caller's handle, allowing authorization and mapping in the same transaction. */
    public static <T> Page<T> fetch(Handle handle, String source, String projection,
            String where, Consumer<Query> binder, List<Order> order, String token, int limit,
            String context, RowMapper<T> mapper) {
        if (order.isEmpty()) throw new IllegalArgumentException("A unique ordering is required");
        int size = clampLimit(limit);
        String identity = Cursor.context(context, order);
        Cursor.Window cursor = Cursor.decodeWindow(token, identity, order.size());
        if (cursor != null && !validPosition(handle, source, projection, where, binder, order, cursor.values())) cursor = null;
        List<String> ordering = new ArrayList<>();
        List<String> alternatives = new ArrayList<>();
        List<String> prefix = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            Order key = order.get(i);
            String col = key.column();
            ordering.add("CASE WHEN " + col + " IS NULL THEN 1 ELSE 0 END ASC");
            ordering.add(col + (key.descending() ? " DESC" : " ASC"));
            if (cursor == null) continue;
            Object value = cursor.values().get(i);
            if (value != null) {
                String after = "(" + col + " IS NULL OR " + col
                        + (key.descending() ? " < " : " > ") + ":kw" + i + ")";
                alternatives.add("(" + String.join(" AND ", append(prefix, after)) + ")");
                prefix.add(col + " = :kw" + i);
            } else {
                prefix.add(col + " IS NULL");
            }
        }
        String seek = cursor == null ? "" : " AND (" +
                (alternatives.isEmpty() ? "1 = 0" : String.join(" OR ", alternatives)) + ")";
        Query query = handle.createQuery("SELECT " + projection + " FROM " + source
                + " WHERE (" + where + ")" + seek + " ORDER BY " + String.join(", ", ordering)
                + " LIMIT :kwLimit").bind("kwLimit", size + 1);
        binder.accept(query);
        if (cursor != null) {
            for (int i = 0; i < order.size(); i++) {
                if (cursor.values().get(i) != null) query.bind("kw" + i, cursor.values().get(i));
            }
        }
        record Positioned<T>(T row, List<Object> values) {}
        List<Positioned<T>> fetched = query.map((rs, ctx) -> {
            List<Object> values = new ArrayList<>();
            for (Order key : order) {
                String col = key.column();
                values.add(rs.getObject(col.substring(col.lastIndexOf('.') + 1)));
            }
            return new Positioned<>(mapper.map(rs, ctx), values);
        }).list();
        boolean more = fetched.size() > size;
        if (more) fetched.removeLast(); // only the SQL look-ahead row, never an in-memory page slice
        List<T> rows = fetched.stream().map(Positioned::row).toList();
        String next = more ? Cursor.encodeWindow(identity, fetched.getLast().values()) : null;
        return new Page<>(rows, next, more);
    }

    /** Validate against JDBC metadata before binding a client position to a comparison. */
    public static boolean validPosition(Handle handle, String source, String projection, String where,
            Consumer<Query> binder, List<Order> order, List<Object> values) {
        if (values.size() != order.size()) return false;
        Query query = handle.createQuery("SELECT " + projection + " FROM " + source + " WHERE (" + where + ") AND 1=0");
        binder.accept(query);
        return query.scanResultSet((supplier, context) -> {
            try (context) {
                var metadata = supplier.get().getMetaData();
                for (int i = 0; i < order.size(); i++) {
                    String name = order.get(i).column(); name = name.substring(name.lastIndexOf('.') + 1);
                    int column = 0;
                    for (int c = 1; c <= metadata.getColumnCount(); c++) {
                        if (metadata.getColumnLabel(c).equalsIgnoreCase(name)) { column = c; break; }
                    }
                    if (column == 0) throw new IllegalArgumentException("Sort column not projected: " + name);
                    Object value = values.get(i);
                    if (value == null) {
                        if (metadata.isNullable(column) == java.sql.ResultSetMetaData.columnNoNulls) return false;
                        continue;
                    }
                    int type = metadata.getColumnType(column);
                    boolean matches = switch (type) {
                        case java.sql.Types.BIGINT, java.sql.Types.INTEGER, java.sql.Types.SMALLINT,
                             java.sql.Types.TINYINT, java.sql.Types.NUMERIC, java.sql.Types.DECIMAL,
                             java.sql.Types.REAL, java.sql.Types.FLOAT, java.sql.Types.DOUBLE -> value instanceof Number;
                        case java.sql.Types.DATE -> value instanceof java.sql.Date || value instanceof java.time.LocalDate;
                        case java.sql.Types.TIMESTAMP, java.sql.Types.TIMESTAMP_WITH_TIMEZONE ->
                                value instanceof java.sql.Timestamp || value instanceof java.time.LocalDateTime;
                        case java.sql.Types.TIME, java.sql.Types.TIME_WITH_TIMEZONE ->
                                value instanceof java.sql.Time || value instanceof java.time.LocalTime;
                        case java.sql.Types.BOOLEAN, java.sql.Types.BIT -> value instanceof Boolean;
                        default -> "uuid".equalsIgnoreCase(metadata.getColumnTypeName(column))
                                || "java.util.UUID".equals(metadata.getColumnClassName(column))
                                ? value instanceof java.util.UUID : value instanceof String;
                    };
                    if (!matches) return false;
                }
                return true;
            }
        });
    }

    private static List<String> append(List<String> prefix, String value) {
        List<String> result = new ArrayList<>(prefix);
        result.add(value);
        return result;
    }
}
