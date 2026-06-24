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
import java.util.stream.Collectors;

/**
 * Read-side queries for accumulation registers (movements, balance, turnover),
 * shared by the REST API and the DivKit emitters. Pure data access — access
 * control stays with the callers.
 */
public class RegisterQueryService {

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
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM " + desc.tableName() + " WHERE _active = true");
        if (from != null) sql.append(" AND _period >= CAST(:from AS TIMESTAMP)");
        if (to != null) sql.append(" AND _period <= CAST(:to AS TIMESTAMP)");
        sql.append(" ORDER BY _period DESC LIMIT :cap");

        List<Map<String, Object>> rows = jdbi.withHandle(h -> {
            var query = h.createQuery(sql.toString());
            if (from != null) query.bind("from", from);
            if (to != null) query.bind("to", to);
            query.bind("cap", MOVEMENTS_CAP);
            return query.mapToMap().list();
        });
        resolveAll(desc, rows);
        return rows;
    }

    /**
     * One page of movements for the virtualized register island — server-side ordered by a validated
     * column (default {@code _period} DESC) and windowed by {@code offset}/{@code limit}, so a packed
     * register never ships its whole movement log. Refs are resolved like {@link #movements}.
     */
    public List<Map<String, Object>> movementsPage(AccumulationRegisterDescriptor desc, String from, String to,
                                                   String sortColumn, boolean descending, int offset, int limit) {
        String orderBy = safeSort(desc, sortColumn, "_period", movementColumns(desc));
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM " + desc.tableName() + " WHERE _active = true");
        if (from != null) sql.append(" AND _period >= CAST(:from AS TIMESTAMP)");
        if (to != null) sql.append(" AND _period <= CAST(:to AS TIMESTAMP)");
        sql.append(" ORDER BY ").append(orderBy).append(descending ? " DESC" : " ASC");
        sql.append(" LIMIT :limit OFFSET :offset");

        List<Map<String, Object>> rows = jdbi.withHandle(h -> {
            var query = h.createQuery(sql.toString());
            if (from != null) query.bind("from", from);
            if (to != null) query.bind("to", to);
            query.bind("limit", Math.max(1, limit)).bind("offset", Math.max(0, offset));
            return query.mapToMap().list();
        });
        resolveAll(desc, rows);
        return rows;
    }

    /** Total active movements in the (optional) period window — for the island's virtual scroller. */
    public long movementsCount(AccumulationRegisterDescriptor desc, String from, String to) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM " + desc.tableName() + " WHERE _active = true");
        if (from != null) sql.append(" AND _period >= CAST(:from AS TIMESTAMP)");
        if (to != null) sql.append(" AND _period <= CAST(:to AS TIMESTAMP)");
        return jdbi.withHandle(h -> {
            var query = h.createQuery(sql.toString());
            if (from != null) query.bind("from", from);
            if (to != null) query.bind("to", to);
            return query.mapTo(Long.class).one();
        });
    }

    /**
     * One page of current balances (the materialized totals table) for the virtualized island,
     * ordered by a validated column (default: the dimension tuple) and windowed by
     * {@code offset}/{@code limit}. BALANCE registers only.
     */
    public List<Map<String, Object>> balancePage(AccumulationRegisterDescriptor desc,
                                                 String sortColumn, boolean descending, int offset, int limit) {
        if (desc.accumulationType() != AccumulationType.BALANCE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Balance is only available for BALANCE registers");
        }
        String dimOrder = desc.dimensions().stream()
                .map(AttributeDescriptor::columnName)
                .collect(Collectors.joining(", "));
        String orderBy = safeSort(desc, sortColumn,
                dimOrder.isEmpty() ? "1" : dimOrder, balanceColumns(desc));
        String sql = "SELECT * FROM " + desc.totalsTableName()
                + " ORDER BY " + orderBy + (descending ? " DESC" : " ASC")
                + " LIMIT :limit OFFSET :offset";
        List<Map<String, Object>> rows = jdbi.withHandle(h ->
                h.createQuery(sql)
                        .bind("limit", Math.max(1, limit)).bind("offset", Math.max(0, offset))
                        .mapToMap().list());
        resolveAll(desc, rows);
        return rows;
    }

    /** Total balance rows (distinct dimension combinations) — for the island's virtual scroller. */
    public long balanceCount(AccumulationRegisterDescriptor desc) {
        if (desc.accumulationType() != AccumulationType.BALANCE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Balance is only available for BALANCE registers");
        }
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM " + desc.totalsTableName())
                        .mapTo(Long.class).one());
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
                    query.bind(dim.columnName(), filters.get(dim.fieldName()));
                }
            }
            query.bind("cap", BALANCE_CAP);
            return query.mapToMap().list();
        });
        resolveAll(desc, rows);
        return rows;
    }

    public List<Map<String, Object>> turnover(AccumulationRegisterDescriptor desc, String from, String to,
                                              Map<String, String> filters) {
        String dimColumns = desc.dimensions().stream()
                .map(AttributeDescriptor::columnName)
                .collect(Collectors.joining(", "));
        String resourceSums = desc.resources().stream()
                .map(r -> "SUM(" + r.columnName() + ") AS " + r.columnName())
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
                    query.bind(dim.columnName(), filters.get(dim.fieldName()));
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
        Set<String> resourceColumns = desc.resources().stream()
                .map(r -> r.columnName().toLowerCase())
                .collect(Collectors.toSet());
        String agg = WidgetAggregate.expression("sum", resourceField, resourceColumns);
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

    private void resolveAll(AccumulationRegisterDescriptor desc, List<Map<String, Object>> rows) {
        List<AttributeDescriptor> all = new ArrayList<>();
        all.addAll(desc.dimensions());
        all.addAll(desc.resources());
        refResolver.resolveAttributes(rows, all);
    }
}
