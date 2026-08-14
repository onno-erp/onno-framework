package su.onno.ui;

import su.onno.metadata.AccumulationRegisterDescriptor;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.model.AccumulationType;

import org.jdbi.v3.core.Jdbi;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read-side queries for accumulation registers (movements, balance, turnover),
 * shared by the REST API and the DivKit emitters. Pure data access — access
 * control stays with the callers.
 */
public class RegisterQueryService {

    /** A capped query result that reports whether additional rows were deliberately withheld. */
    public record BoundedRows(List<Map<String, Object>> rows, boolean truncated) {}

    private final MetadataRegistry registry;
    private final Jdbi jdbi;
    private final RefResolver refResolver;

    /**
     * Row caps for the unfiltered register tabs. Movements/balance default to "show everything",
     * and a register is the highest-row-count table in the model, so without a ceiling opening a
     * tab streams the whole table to the client. The movements list is ordered newest-first, so
     * the cap keeps the most recent rows; narrow with a {@code from}/{@code to} window to see more.
     */
    private static final int MOVEMENTS_CAP = 1000;
    private static final int BALANCE_CAP = 5000;

    public RegisterQueryService(MetadataRegistry registry, Jdbi jdbi) {
        this.registry = registry;
        this.jdbi = jdbi;
        this.refResolver = new RefResolver(registry, jdbi);
    }

    public AccumulationRegisterDescriptor require(String name) {
        String normalized = name.replace("_", "").replace(" ", "").toLowerCase();
        return registry.allRegisters().stream()
                .filter(d -> d.logicalName().replace(" ", "").replace("_", "").toLowerCase().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Register not found: " + name));
    }

    public List<Map<String, Object>> movements(AccumulationRegisterDescriptor desc, String from, String to) {
        return movementsBounded(desc, from, to).rows();
    }

    /**
     * The same newest-first movement slice as {@link #movements}, plus an explicit overflow signal
     * for non-UI callers that must not mistake the capped slice for the complete result set.
     */
    public BoundedRows movementsBounded(AccumulationRegisterDescriptor desc, String from, String to) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM " + desc.tableName() + " WHERE _active = true");
        if (from != null) sql.append(" AND _period >= CAST(:from AS TIMESTAMP)");
        if (to != null) sql.append(" AND _period <= CAST(:to AS TIMESTAMP)");
        sql.append(" ORDER BY _period DESC LIMIT :cap");

        List<Map<String, Object>> rows = jdbi.withHandle(h -> {
            var query = h.createQuery(sql.toString());
            if (from != null) query.bind("from", from);
            if (to != null) query.bind("to", to);
            query.bind("cap", MOVEMENTS_CAP + 1);
            return query.mapToMap().list();
        });
        return bounded(desc, rows, MOVEMENTS_CAP);
    }

    /**
     * One window of movements for the virtualized register island — server-side ordered by a validated
     * column (default {@code _period} DESC), narrowed by the grid's declarative filters (the same
     * {@code eq}/{@code in}/{@code like}/{@code prefix}/{@code ge}/{@code le} grammar as
     * {@link ListFilter} compiles for catalog/document lists) and bounded by a start position and
     * window size, so a packed register never ships its whole movement log.
     * Refs are resolved like {@link #movements}.
     */
    public List<Map<String, Object>> movementsWindow(AccumulationRegisterDescriptor desc, String from, String to,
                                                     ListFilter.Result filters,
                                                     String sortColumn, boolean descending,
                                                     int rowPosition, int windowSize) {
        String orderBy = safeSort(desc, sortColumn, "_period", movementColumns(desc));
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM " + desc.tableName() + " WHERE _active = true");
        appendMovementWindow(sql, from, to);
        appendFilters(sql, filters);
        sql.append(" ORDER BY ").append(orderBy).append(descending ? " DESC" : " ASC");
        sql.append(" LIMIT :windowSize OFFSET :rowPosition");

        List<Map<String, Object>> rows = jdbi.withHandle(h -> {
            var query = h.createQuery(sql.toString());
            bindMovementWindow(query, from, to);
            bindFilters(query, filters);
            query.bind("windowSize", Math.max(1, windowSize))
                    .bind("rowPosition", Math.max(0, rowPosition));
            return query.mapToMap().list();
        });
        resolveAll(desc, rows);
        return rows;
    }

    /** Total active movements matching the window + filters — for the island's virtual scroller. */
    public long movementsCount(AccumulationRegisterDescriptor desc, String from, String to,
                               ListFilter.Result filters) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM " + desc.tableName() + " WHERE _active = true");
        appendMovementWindow(sql, from, to);
        appendFilters(sql, filters);
        return jdbi.withHandle(h -> {
            var query = h.createQuery(sql.toString());
            bindMovementWindow(query, from, to);
            bindFilters(query, filters);
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

    private static void appendMovementWindow(StringBuilder sql, String from, String to) {
        if (from != null) sql.append(" AND _period >= CAST(:from AS TIMESTAMP)");
        if (to != null) sql.append(" AND _period <= CAST(:to AS TIMESTAMP)");
    }

    private static void bindMovementWindow(org.jdbi.v3.core.statement.Query query, String from, String to) {
        if (from != null) query.bind("from", from);
        if (to != null) query.bind("to", to);
    }

    private static void appendFilters(StringBuilder sql, ListFilter.Result filters) {
        if (filters != null && !filters.isEmpty()) sql.append(" AND ").append(filters.sql());
    }

    private static void bindFilters(org.jdbi.v3.core.statement.Query query, ListFilter.Result filters) {
        if (filters != null && !filters.isEmpty()) filters.bindings().forEach(query::bind);
    }

    /**
     * One window of current balances (the materialized totals table) for the virtualized island,
     * ordered by a validated column (default: the dimension tuple) and bounded by a start position
     * and window size. BALANCE registers only.
     */
    public List<Map<String, Object>> balanceWindow(AccumulationRegisterDescriptor desc, ListFilter.Result filters,
                                                   String sortColumn, boolean descending,
                                                   int rowPosition, int windowSize) {
        if (desc.accumulationType() != AccumulationType.BALANCE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Balance is only available for BALANCE registers");
        }
        String dimOrder = desc.dimensions().stream()
                .map(AttributeDescriptor::columnName)
                .collect(Collectors.joining(", "));
        String orderBy = safeSort(desc, sortColumn,
                dimOrder.isEmpty() ? "1" : dimOrder, balanceColumns(desc));
        StringBuilder sql = new StringBuilder("SELECT * FROM " + desc.totalsTableName());
        if (filters != null && !filters.isEmpty()) sql.append(" WHERE ").append(filters.sql());
        sql.append(" ORDER BY ").append(orderBy).append(descending ? " DESC" : " ASC")
                .append(" LIMIT :windowSize OFFSET :rowPosition");
        List<Map<String, Object>> rows = jdbi.withHandle(h -> {
            var query = h.createQuery(sql.toString());
            bindFilters(query, filters);
            query.bind("windowSize", Math.max(1, windowSize))
                    .bind("rowPosition", Math.max(0, rowPosition));
            return query.mapToMap().list();
        });
        resolveAll(desc, rows);
        return rows;
    }

    /** Balance rows (distinct dimension combinations) matching the filters — for the scroller. */
    public long balanceCount(AccumulationRegisterDescriptor desc, ListFilter.Result filters) {
        if (desc.accumulationType() != AccumulationType.BALANCE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Balance is only available for BALANCE registers");
        }
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM " + desc.totalsTableName());
        if (filters != null && !filters.isEmpty()) sql.append(" WHERE ").append(filters.sql());
        return jdbi.withHandle(h -> {
            var query = h.createQuery(sql.toString());
            bindFilters(query, filters);
            return query.mapTo(Long.class).one();
        });
    }

    /** Columns a movements list may be ordered by: the period/type system columns + dims + resources. */
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
    private static String safeSort(AccumulationRegisterDescriptor desc, String sortColumn,
                                   String fallback, Set<String> allowed) {
        return sortColumn != null && allowed.contains(sortColumn.toLowerCase()) ? sortColumn : fallback;
    }

    public List<Map<String, Object>> balance(AccumulationRegisterDescriptor desc, Map<String, String> filters) {
        return balanceBounded(desc, filters).rows();
    }

    /**
     * The same current-balance slice as {@link #balance}, plus an explicit overflow signal for
     * non-UI callers that require complete-result semantics.
     */
    public BoundedRows balanceBounded(AccumulationRegisterDescriptor desc, Map<String, String> filters) {
        if (desc.accumulationType() != AccumulationType.BALANCE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Balance is only available for BALANCE registers");
        }

        StringBuilder sql = new StringBuilder("SELECT * FROM " + desc.totalsTableName());
        List<String> conditions = desc.dimensions().stream()
                .filter(d -> filters.containsKey(d.fieldName()))
                .map(d -> d.columnName() + " = :" + d.columnName())
                .toList();
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
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
            for (AttributeDescriptor dim : desc.dimensions()) {
                if (filters.containsKey(dim.fieldName())) {
                    query.bind(dim.columnName(), filterValue(dim, filters.get(dim.fieldName())));
                }
            }
            query.bind("cap", BALANCE_CAP + 1);
            return query.mapToMap().list();
        });
        return bounded(desc, rows, BALANCE_CAP);
    }

    private BoundedRows bounded(AccumulationRegisterDescriptor desc,
                                List<Map<String, Object>> rows, int cap) {
        boolean truncated = rows.size() > cap;
        List<Map<String, Object>> returned = truncated
                ? new ArrayList<>(rows.subList(0, cap))
                : rows;
        resolveAll(desc, returned);
        return new BoundedRows(returned, truncated);
    }

    public List<Map<String, Object>> turnover(AccumulationRegisterDescriptor desc, String from, String to,
                                              Map<String, String> filters) {
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
        if (!dimColumns.isEmpty()) {
            sql.append(" GROUP BY ").append(dimColumns);
        }

        List<Map<String, Object>> rows = jdbi.withHandle(h -> {
            var query = h.createQuery(sql.toString()).bind("from", from).bind("to", to);
            for (AttributeDescriptor dim : desc.dimensions()) {
                if (filters.containsKey(dim.fieldName())) {
                    query.bind(dim.columnName(), filterValue(dim, filters.get(dim.fieldName())));
                }
            }
            return query.mapToMap().list();
        });
        resolveAll(desc, rows);
        return rows;
    }

    /**
     * A single summed resource across a register — the KPI/metric-card counterpart to
     * {@link #turnover}. Restricted to active movements, narrowed by an optional period
     * window and a safe {@code filter} predicate (see {@link WidgetFilter}). The resource
     * field must be one of the register's resources, so it can never carry arbitrary SQL.
     */
    public BigDecimal total(AccumulationRegisterDescriptor desc, String resourceField,
                            String from, String to, String filter) {
        String resourceColumn = desc.resources().stream()
                .map(AttributeDescriptor::columnName)
                .filter(column -> column.equalsIgnoreCase(resourceField))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown register resource: " + resourceField));
        String agg = "COALESCE(" + signedSum(resourceColumn) + ", 0)";
        WidgetFilter.Result f = WidgetFilter.parse(filter, dimensionColumns(desc));

        StringBuilder sql = new StringBuilder("SELECT ").append(agg)
                .append(" FROM ").append(desc.tableName())
                .append(" WHERE _active = true");
        if (from != null) sql.append(" AND _period >= CAST(:from AS TIMESTAMP)");
        if (to != null) sql.append(" AND _period <= CAST(:to AS TIMESTAMP)");
        if (!f.isEmpty()) {
            sql.append(" AND ").append(f.sql());
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

    private void resolveAll(AccumulationRegisterDescriptor desc, List<Map<String, Object>> rows) {
        List<AttributeDescriptor> all = new ArrayList<>();
        all.addAll(desc.dimensions());
        all.addAll(desc.resources());
        refResolver.resolveAttributes(rows, all);
    }
}
