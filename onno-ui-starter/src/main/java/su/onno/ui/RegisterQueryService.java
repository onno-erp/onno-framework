package su.onno.ui;

import su.onno.access.AccessMode;
import su.onno.access.AccessSubject;
import su.onno.metadata.AccumulationRegisterDescriptor;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.model.AccumulationType;

import org.jdbi.v3.core.Jdbi;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read-side queries for accumulation registers (movements, balance, turnover),
 * shared by the REST API and the DivKit emitters.
 *
 * <p>Every read takes the {@link AccessSubject} it is made for. Entity-level RBAC stays with the
 * callers; a register record policy scopes by <em>dimension</em> columns, which exist in both the
 * movement and the totals table, so the same scope restricts movements, balances (read from the
 * totals table), turnovers and totals.
 */
public class RegisterQueryService {

    /** A capped query result that reports whether additional rows were deliberately withheld. */
    public record BoundedRows(List<Map<String, Object>> rows, boolean truncated) {}

    private final MetadataRegistry registry;
    private final Jdbi jdbi;
    private final RefResolver refResolver;
    private final RecordScopeCompiler scopes;

    /**
     * Row caps for the unfiltered register tabs. Movements/balance default to "show everything",
     * and a register is the highest-row-count table in the model, so without a ceiling opening a
     * tab streams the whole table to the client. The movements list is ordered newest-first, so
     * the cap keeps the most recent rows; narrow with a {@code from}/{@code to} window to see more.
     */
    private static final int MOVEMENTS_CAP = 1000;
    private static final int BALANCE_CAP = 5000;

    /** A service without record policies (every subject unscoped) — for tests and tools. */
    public RegisterQueryService(MetadataRegistry registry, Jdbi jdbi) {
        this(registry, jdbi, RecordScopeCompiler.unrestricted(registry), new UiAccessService(registry));
    }

    public RegisterQueryService(MetadataRegistry registry, Jdbi jdbi, RecordScopeCompiler scopes,
                                UiAccessService access) {
        this.registry = registry;
        this.jdbi = jdbi;
        this.scopes = scopes;
        this.refResolver = new RefResolver(registry, jdbi, scopes, access);
    }

    /** The subject's scope over {@code table} (the movement or the totals table of {@code desc}). */
    private ScopeClause scope(AccessSubject subject, AccumulationRegisterDescriptor desc, String table) {
        return scopes.clause(desc.javaClass(), subject, AccessMode.READ, table);
    }

    public AccumulationRegisterDescriptor require(String name) {
        String normalized = name.replace("_", "").replace(" ", "").toLowerCase();
        return registry.allRegisters().stream()
                .filter(d -> d.logicalName().replace(" ", "").replace("_", "").toLowerCase().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Register not found: " + name));
    }

    public List<Map<String, Object>> movements(AccessSubject subject, AccumulationRegisterDescriptor desc,
                                               String from, String to) {
        var result = movementsBounded(subject, desc, from, to);
        if (result.truncated()) throw new IllegalStateException("Movements exceed the preview limit; use movementsPage");
        return result.rows();
    }

    /**
     * The same newest-first movement slice as {@link #movements}, plus an explicit overflow signal
     * for non-UI callers that must not mistake the capped slice for the complete result set.
     */
    public BoundedRows movementsBounded(AccessSubject subject, AccumulationRegisterDescriptor desc,
                                        String from, String to) {
        ScopeClause scope = scope(subject, desc, desc.tableName());
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM " + desc.tableName() + " WHERE _active = true");
        if (from != null) sql.append(" AND _period >= CAST(:from AS TIMESTAMP)");
        if (to != null) sql.append(" AND _period <= CAST(:to AS TIMESTAMP)");
        sql.append(scope.and());
        sql.append(" ORDER BY _period DESC LIMIT :cap");

        List<Map<String, Object>> rows = jdbi.withHandle(h -> {
            var query = h.createQuery(sql.toString());
            if (from != null) query.bind("from", from);
            if (to != null) query.bind("to", to);
            scope.bind(query);
            query.bind("cap", MOVEMENTS_CAP + 1);
            return query.mapToMap().list();
        });
        return bounded(subject, desc, rows, MOVEMENTS_CAP);
    }

    public su.onno.query.KeysetWindow.Page<Map<String, Object>> movementsPage(AccessSubject subject,
            AccumulationRegisterDescriptor desc, String from, String to, ListFilter.Result filters,
            String sortColumn, boolean descending, String cursor, int limit) {
        String col = safeSort(sortColumn, "_period", movementColumns(desc));
        ListFilter.Result scoped = scope(subject, desc, desc.tableName()).andInto(normalizedFilters(filters));
        StringBuilder where = new StringBuilder("_active = true");
        appendMovementWindow(where, from, to);
        appendFilters(where, scoped);
        var page = KeysetWindow.collection(jdbi, desc.tableName(), "*", where.toString(), q -> {
            bindMovementWindow(q, from, to);
            bindFilters(q, scoped);
        }, List.of(new su.onno.query.KeysetWindow.Order(col, descending),
                new su.onno.query.KeysetWindow.Order("_id", descending)), cursor, limit,
                su.onno.query.Cursor.context("movements", desc.tableName(), from, to, scoped.sql(), scoped.bindings(), EntityQuerySupport.cursorSubject(subject)));
        resolveAll(subject, desc, page.rows());
        return page;
    }

    /** Total active movements matching the window + filters — for the island's virtual scroller. */
    public long movementsCount(AccessSubject subject, AccumulationRegisterDescriptor desc, String from, String to,
                               ListFilter.Result filters) {
        ListFilter.Result scoped = scope(subject, desc, desc.tableName()).andInto(normalizedFilters(filters));
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM " + desc.tableName() + " WHERE _active = true");
        appendMovementWindow(sql, from, to);
        appendFilters(sql, scoped);
        return jdbi.withHandle(h -> {
            var query = h.createQuery(sql.toString());
            bindMovementWindow(query, from, to);
            bindFilters(query, scoped);
            return query.mapTo(Long.class).one();
        });
    }

    /** The columns a movements filter may reference — for {@link ListFilter#parse}. */
    public Set<String> movementFilterColumns(AccumulationRegisterDescriptor desc) {
        return movementColumns(desc);
    }

    /** The columns a balance filter may reference — for {@link ListFilter#parse}. */
    public Set<String> balanceFilterColumns(AccumulationRegisterDescriptor desc) {
        return balanceColumns(desc);
    }

    /** The numeric dimension/resource columns — a range bound on one binds as a number. */
    public Set<String> numericFilterColumns(AccumulationRegisterDescriptor desc) {
        Set<String> cols = new java.util.LinkedHashSet<>();
        java.util.stream.Stream.concat(desc.dimensions().stream(), desc.resources().stream())
                .filter(a -> ListFilter.isNumeric(a.javaType()))
                .forEach(a -> cols.add(a.columnName().toLowerCase()));
        return cols;
    }

    private static void appendMovementWindow(StringBuilder sql, String from, String to) {
        if (from != null) sql.append(" AND _period >= CAST(:from AS TIMESTAMP)");
        if (to != null) sql.append(" AND _period <= CAST(:to AS TIMESTAMP)");
    }

    private static void bindMovementWindow(org.jdbi.v3.core.statement.Query query, String from, String to) {
        if (from != null) query.bind("from", from);
        if (to != null) query.bind("to", to);
    }

    private static ListFilter.Result normalizedFilters(ListFilter.Result filters) {
        return filters == null ? new ListFilter.Result("", Map.of()) : filters;
    }

    private static void appendFilters(StringBuilder sql, ListFilter.Result filters) {
        if (filters != null && !filters.isEmpty()) sql.append(" AND (").append(filters.sql()).append(")");
    }

    private static void bindFilters(org.jdbi.v3.core.statement.Query query, ListFilter.Result filters) {
        if (filters != null && !filters.isEmpty()) filters.bindings().forEach(query::bind);
    }

    public su.onno.query.KeysetWindow.Page<Map<String, Object>> balancePage(AccessSubject subject,
            AccumulationRegisterDescriptor desc, ListFilter.Result filters, String sortColumn,
            boolean descending, String cursor, int limit) {
        return balancePage(subject, desc, filters, sortColumn, descending, cursor, limit, null);
    }

    public su.onno.query.KeysetWindow.Page<Map<String, Object>> balancePage(AccessSubject subject,
            AccumulationRegisterDescriptor desc, ListFilter.Result filters, String sortColumn,
            boolean descending, String cursor, int limit, String asOf) {
        BalanceSource source = balanceSource(subject, desc, filters, asOf);
        List<su.onno.query.KeysetWindow.Order> order = new ArrayList<>();
        String sort = safeSort(sortColumn, null, balanceColumns(desc));
        if (sort != null) order.add(new su.onno.query.KeysetWindow.Order(sort, descending));
        for (var dim : desc.dimensions()) {
            if (!dim.columnName().equals(sort)) order.add(new su.onno.query.KeysetWindow.Order(dim.columnName(), descending));
        }
        if (order.isEmpty()) order.add(new su.onno.query.KeysetWindow.Order(desc.resources().getFirst().columnName(), descending));
        var page = KeysetWindow.collection(jdbi, source.from(), "*", source.where(), source.binder(),
                order, cursor, limit, source.context());
        resolveAll(subject, desc, page.rows());
        return page;
    }

    private record BalanceSource(String from, String where,
            java.util.function.Consumer<org.jdbi.v3.core.statement.Query> binder, String context) {}

    private BalanceSource balanceSource(AccessSubject subject, AccumulationRegisterDescriptor desc,
            ListFilter.Result filters, String asOf) {
        if (desc.accumulationType() != AccumulationType.BALANCE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Balance is only available for BALANCE registers");
        }
        ListFilter.Result selection = normalizedFilters(filters);
        AsOf cutoff = AsOf.parse(asOf);
        if (cutoff == null) {
            ListFilter.Result scoped = scope(subject, desc, desc.totalsTableName()).andInto(normalizedFilters(filters));
            return new BalanceSource(desc.totalsTableName(), scoped.isEmpty() ? "1 = 1" : scoped.sql(),
                    q -> bindFilters(q, scoped), su.onno.query.Cursor.context("balance", desc.totalsTableName(),
                    scoped.sql(), scoped.bindings(), EntityQuerySupport.cursorSubject(subject)));
        }
        ScopeClause visible = scope(subject, desc, desc.tableName());
        String dimensions = desc.dimensions().stream().map(AttributeDescriptor::columnName).collect(Collectors.joining(", "));
        String sums = desc.resources().stream().map(r -> signedSum(r.columnName()) + " AS " + r.columnName())
                .collect(Collectors.joining(", "));
        String aggregate = "SELECT " + (dimensions.isEmpty() ? "" : dimensions + ", ") + sums
                + " FROM " + desc.tableName() + " WHERE _active = true AND _period " + cutoff.operator()
                + " :balanceAsOf" + visible.and() + (dimensions.isEmpty() ? "" : " GROUP BY " + dimensions);
        // Scope movements before aggregation; resource filters compare the resulting balances.
        return new BalanceSource("(" + aggregate + ") balance_window", selection.isEmpty() ? "1 = 1" : selection.sql(),
                q -> { q.bind("balanceAsOf", cutoff.bound()); visible.bind(q); bindFilters(q, selection); },
                su.onno.query.Cursor.context("balance-as-of", desc.tableName(), cutoff.operator(), cutoff.bound(),
                        visible.sql(), visible.bindings(), selection.sql(), selection.bindings(), EntityQuerySupport.cursorSubject(subject)));
    }

    /** Convert dimension-name filters using metadata, never raw request SQL. */
    public ListFilter.Result dimensionFilters(AccumulationRegisterDescriptor desc, Map<String, String> values) {
        // Bind each value as the dimension's declared JDBC type, so numeric/boolean/UUID equality
        // compares typed values (5 matches 5.00) rather than a dialect-specific text rendering.
        List<String> conditions = new ArrayList<>();
        Map<String, Object> bindings = new java.util.LinkedHashMap<>();
        for (var dim : desc.dimensions()) {
            if (!values.containsKey(dim.fieldName())) continue;
            String bind = "dimEq_" + dim.columnName();
            conditions.add(dim.columnName() + " = :" + bind);
            bindings.put(bind, typedDimensionValue(dim, values.get(dim.fieldName())));
        }
        return new ListFilter.Result(String.join(" AND ", conditions), bindings);
    }

    private static Object typedDimensionValue(AttributeDescriptor dim, String raw) {
        try {
            return filterValue(dim, raw);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid value for dimension " + dim.fieldName(), e);
        }
    }

    /** Only the register's dimension filters — never paging/transport parameters. */
    private static Map<String, String> dimensionValues(AccumulationRegisterDescriptor desc, Map<String, String> values) {
        Map<String, String> out = new java.util.TreeMap<>();
        if (values == null) return out;
        for (var dim : desc.dimensions()) {
            if (values.containsKey(dim.fieldName())) out.put(dim.fieldName(), values.get(dim.fieldName()));
        }
        return out;
    }

    /** Balance rows (distinct dimension combinations) matching the filters — for the scroller. */
    public long balanceCount(AccessSubject subject, AccumulationRegisterDescriptor desc, ListFilter.Result filters) {
        return balanceCount(subject, desc, filters, null);
    }

    public long balanceCount(AccessSubject subject, AccumulationRegisterDescriptor desc, ListFilter.Result filters, String asOf) {
        BalanceSource source = balanceSource(subject, desc, filters, asOf);
        return jdbi.withHandle(h -> {
            var query = h.createQuery("SELECT COUNT(*) FROM " + source.from() + " WHERE " + source.where());
            source.binder().accept(query);
            return query.mapTo(Long.class).one();
        });
    }

    private static Set<String> movementColumns(AccumulationRegisterDescriptor desc) {
        Set<String> cols = new java.util.LinkedHashSet<>(Set.of("_period", "_movement_type"));
        desc.dimensions().forEach(d -> cols.add(d.columnName().toLowerCase()));
        desc.resources().forEach(r -> cols.add(r.columnName().toLowerCase()));
        return cols;
    }

    /** Columns a balance list may be ordered by: the dimensions + resources of the totals table. */
    private static Set<String> balanceColumns(AccumulationRegisterDescriptor desc) {
        Set<String> cols = new java.util.LinkedHashSet<>();
        desc.dimensions().forEach(d -> cols.add(d.columnName().toLowerCase()));
        desc.resources().forEach(r -> cols.add(r.columnName().toLowerCase()));
        return cols;
    }

    /** Validate a client-supplied sort column against an allow-list, falling back to {@code fallback}. */
    private static String safeSort(String sortColumn,
                                   String fallback, Set<String> allowed) {
        return sortColumn != null && allowed.contains(sortColumn.toLowerCase()) ? sortColumn : fallback;
    }

    public List<Map<String, Object>> balance(AccessSubject subject, AccumulationRegisterDescriptor desc,
                                             Map<String, String> filters) {
        var result = balanceBounded(subject, desc, filters);
        if (result.truncated()) throw new IllegalStateException("Balances exceed the preview limit; use balancePage");
        return result.rows();
    }

    public List<Map<String, Object>> balance(AccessSubject subject, AccumulationRegisterDescriptor desc,
                                             Map<String, String> filters, String asOf) {
        return balanceBounded(subject, desc, filters, asOf).rows();
    }

    /**
     * The same current-balance slice as {@link #balance}, plus an explicit overflow signal for
     * non-UI callers that require complete-result semantics.
     */
    public BoundedRows balanceBounded(AccessSubject subject, AccumulationRegisterDescriptor desc,
                                      Map<String, String> filters) {
        return balanceBounded(subject, desc, filters, null);
    }

    /**
     * Balances at an inclusive ISO-8601 cutoff; null reads materialized current totals. A date-time
     * ({@code 2026-03-31T18:00}) includes movements up to and at that instant; a bare date
     * ({@code 2026-03-31}) includes that whole day. Offsets and zones are rejected, because register
     * periods are local date-times.
     */
    public BoundedRows balanceBounded(AccessSubject subject, AccumulationRegisterDescriptor desc,
                                      Map<String, String> filters, String asOf) {
        if (desc.accumulationType() != AccumulationType.BALANCE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Balance is only available for BALANCE registers");
        }
        AsOf cutoff = AsOf.parse(asOf);

        String table = cutoff == null ? desc.totalsTableName() : desc.tableName();
        ScopeClause scope = scope(subject, desc, table);
        String dimColumns = desc.dimensions().stream()
                .map(AttributeDescriptor::columnName).collect(Collectors.joining(", "));
        StringBuilder sql = new StringBuilder("SELECT ");
        if (cutoff == null) {
            sql.append("*");
        } else {
            if (!dimColumns.isEmpty()) sql.append(dimColumns).append(", ");
            sql.append(desc.resources().stream()
                    .map(r -> signedSum(r.columnName()) + " AS " + r.columnName())
                    .collect(Collectors.joining(", ")));
        }
        sql.append(" FROM ").append(table);
        List<String> conditions = new ArrayList<>(desc.dimensions().stream()
                .filter(d -> filters.containsKey(d.fieldName()))
                .map(d -> d.columnName() + " = :" + d.columnName())
                .toList());
        if (cutoff != null) {
            conditions.add("_active = true");
            conditions.add("_period " + cutoff.operator() + " :asOf");
        }
        if (!scope.isAll()) {
            conditions.add("(" + scope.sql() + ")");
        }
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        if (cutoff != null && !dimColumns.isEmpty()) {
            sql.append(" GROUP BY ").append(dimColumns);
        }
        String dimOrder = desc.dimensions().stream()
                .map(AttributeDescriptor::columnName)
                .collect(Collectors.joining(", "));
        if (!dimOrder.isEmpty()) {
            sql.append(" ORDER BY ").append(dimOrder);
        }
        sql.append(" LIMIT :cap");

        List<Map<String, Object>> rows = jdbi.withHandle(h -> {
            var query = h.createQuery(sql.toString());
            if (cutoff != null) query.bind("asOf", cutoff.bound());
            for (AttributeDescriptor dim : desc.dimensions()) {
                if (filters.containsKey(dim.fieldName())) {
                    query.bind(dim.columnName(), filterValue(dim, filters.get(dim.fieldName())));
                }
            }
            scope.bind(query);
            query.bind("cap", BALANCE_CAP + 1);
            return query.mapToMap().list();
        });
        return bounded(subject, desc, rows, BALANCE_CAP);
    }

    /** A parsed {@code asOf} cutoff: {@code _period <= instant}, or {@code _period < next day} for a date. */
    private record AsOf(String operator, LocalDateTime bound) {
        static AsOf parse(String raw) {
            if (raw == null || raw.isBlank()) return null;
            String value = raw.trim();
            try {
                return value.contains("T")
                        ? new AsOf("<=", LocalDateTime.parse(value))
                        : new AsOf("<", LocalDate.parse(value).plusDays(1).atStartOfDay());
            } catch (DateTimeParseException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "asOf must be an ISO-8601 local date (2026-03-31) or date-time (2026-03-31T18:00)"
                                + " without an offset: " + raw);
            }
        }
    }

    private BoundedRows bounded(AccessSubject subject, AccumulationRegisterDescriptor desc,
                                List<Map<String, Object>> rows, int cap) {
        boolean truncated = rows.size() > cap;
        List<Map<String, Object>> returned = new ArrayList<>(rows);
        if (truncated) returned.removeLast();
        resolveAll(subject, desc, returned);
        return new BoundedRows(returned, truncated);
    }

    public List<Map<String, Object>> turnover(AccessSubject subject, AccumulationRegisterDescriptor desc,
                                              String from, String to,
                                              Map<String, String> filters) {
        var page = turnoverPage(subject, desc, from, to, filters, null, 500);
        if (page.hasMore()) throw new IllegalStateException("Turnover exceeds 500 rows; use turnoverPage and follow nextCursor");
        return page.rows();
    }

    public su.onno.query.KeysetWindow.Page<Map<String, Object>> turnoverPage(AccessSubject subject,
            AccumulationRegisterDescriptor desc, String from, String to, Map<String, String> requestFilters,
            String cursor, int limit) {
        // The controller passes every request parameter; keep only dimensions so cursor/limit
        // never leak into the SQL or the cursor context (page 2 would otherwise mismatch page 1).
        Map<String, String> filters = dimensionValues(desc, requestFilters);
        ScopeClause scope = scope(subject, desc, desc.tableName());
        String dimColumns = desc.dimensions().stream()
                .map(AttributeDescriptor::columnName)
                .collect(Collectors.joining(", "));
        String resourceSums = desc.resources().stream()
                .map(r -> signedSum(r.columnName()) + " AS " + r.columnName())
                .collect(Collectors.joining(", "));

        StringBuilder sql = new StringBuilder("SELECT ");
        if (!dimColumns.isEmpty()) {
            sql.append(dimColumns).append(", ");
        }
        sql.append(resourceSums);
        sql.append(" FROM ").append(desc.tableName());
        sql.append(" WHERE _active = true AND _period >= CAST(:from AS TIMESTAMP) AND _period <= CAST(:to AS TIMESTAMP)");
        for (AttributeDescriptor dim : desc.dimensions()) {
            if (filters.containsKey(dim.fieldName())) {
                sql.append(" AND ").append(dim.columnName()).append(" = :").append(dim.columnName());
            }
        }
        sql.append(scope.and());
        if (!dimColumns.isEmpty()) {
            sql.append(" GROUP BY ").append(dimColumns);
        }

        var order = desc.dimensions().stream()
                .map(d -> new su.onno.query.KeysetWindow.Order(d.columnName(), false)).toList();
        // An aggregate without dimensions is a single-row collection, ordered by its projected sum.
        if (order.isEmpty()) order = List.of(new su.onno.query.KeysetWindow.Order(desc.resources().getFirst().columnName(), false));
        var page = KeysetWindow.collection(jdbi, "(" + sql + ") turnover", "*", "1=1", query -> {
            query.bind("from", from).bind("to", to); scope.bind(query);
            for (AttributeDescriptor dim : desc.dimensions()) {
                if (filters.containsKey(dim.fieldName())) query.bind(dim.columnName(), filterValue(dim, filters.get(dim.fieldName())));
            }
        }, order, cursor, limit, su.onno.query.Cursor.context("turnover", desc.tableName(), from, to,
                filters, EntityQuerySupport.cursorSubject(subject), scope.sql(), scope.bindings()));
        resolveAll(subject, desc, page.rows());
        return page;
    }

    /**
     * A single summed resource across a register — the KPI/metric-card counterpart to
     * {@link #turnover}. Restricted to active movements, narrowed by an optional period
     * window and a safe {@code filter} predicate (see {@link WidgetFilter}). The resource
     * field must be one of the register's resources, so it can never carry arbitrary SQL.
     */
    public BigDecimal total(AccessSubject subject, AccumulationRegisterDescriptor desc, String resourceField,
                            String from, String to, String filter) {
        String resourceColumn = desc.resources().stream()
                .map(AttributeDescriptor::columnName)
                .filter(column -> column.equalsIgnoreCase(resourceField))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown register resource: " + resourceField));
        String agg = "COALESCE(" + signedSum(resourceColumn) + ", 0)";
        WidgetFilter.Result f = scope(subject, desc, desc.tableName())
                .andInto(WidgetFilter.parse(filter, dimensionColumns(desc)));

        StringBuilder sql = new StringBuilder("SELECT ").append(agg)
                .append(" FROM ").append(desc.tableName())
                .append(" WHERE _active = true");
        if (from != null) sql.append(" AND _period >= CAST(:from AS TIMESTAMP)");
        if (to != null) sql.append(" AND _period <= CAST(:to AS TIMESTAMP)");
        if (!f.isEmpty()) {
            sql.append(" AND (").append(f.sql()).append(")");
        }
        return jdbi.withHandle(h -> {
            var query = h.createQuery(sql.toString());
            if (from != null) query.bind("from", from);
            if (to != null) query.bind("to", to);
            f.bindings().forEach(query::bind);
            return query.mapTo(BigDecimal.class).findOne().orElse(BigDecimal.ZERO);
        });
    }

    private static Set<String> dimensionColumns(AccumulationRegisterDescriptor desc) {
        return desc.dimensions().stream()
                .map(d -> d.columnName().toLowerCase())
                .collect(Collectors.toSet());
    }

    private static String signedSum(String resourceColumn) {
        return "SUM(CASE WHEN _movement_type = 'RECEIPT' THEN " + resourceColumn +
                " ELSE -" + resourceColumn + " END)";
    }

    /** Convert HTTP query values to the JDBC type declared by the register dimension. */
    private static Object filterValue(AttributeDescriptor dimension, String raw) {
        Class<?> type = dimension.javaType();
        if (dimension.isRef() || type.isEnum() || type == UUID.class) {
            return UUID.fromString(raw);
        }
        if (type == BigDecimal.class) return new BigDecimal(raw);
        if (type == Integer.class || type == int.class) return Integer.valueOf(raw);
        if (type == Long.class || type == long.class) return Long.valueOf(raw);
        if (type == Double.class || type == double.class) return Double.valueOf(raw);
        if (type == Float.class || type == float.class) return Float.valueOf(raw);
        if (type == Boolean.class || type == boolean.class) return Boolean.valueOf(raw);
        return raw;
    }

    private void resolveAll(AccessSubject subject, AccumulationRegisterDescriptor desc,
                            List<Map<String, Object>> rows) {
        List<AttributeDescriptor> all = new ArrayList<>();
        all.addAll(desc.dimensions());
        all.addAll(desc.resources());
        refResolver.resolveAttributes(rows, all, subject);
    }
}
