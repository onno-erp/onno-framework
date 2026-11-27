package su.onno.service;

import su.onno.access.AccessMode;
import su.onno.access.AccessSubject;
import su.onno.metadata.AccumulationRegisterDescriptor;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.model.AccumulationType;

import org.jdbi.v3.core.Jdbi;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-side queries for accumulation registers (movements, balance, turnover),
 * shared by the REST API, the UI's views and MCP.
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
        this(registry, jdbi, RecordScopeCompiler.unrestricted(registry), new AccessService(registry));
    }

    public RegisterQueryService(MetadataRegistry registry, Jdbi jdbi, RecordScopeCompiler scopes,
                                AccessService access) {
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
                .orElseThrow(() -> new ServiceException(ServiceException.Status.NOT_FOUND,
                        "Register not found: " + name));
    }

    public List<Map<String, Object>> movements(AccessSubject subject, AccumulationRegisterDescriptor desc,
                                               String from, String to) {
        return movements(subject, desc, from, to, null);
    }

    /**
     * Every movement in {@code [from, to]} matching a {@link Where} predicate over the movement
     * rows, newest first — the complete result, read page by page.
     */
    public List<Map<String, Object>> movements(AccessSubject subject, AccumulationRegisterDescriptor desc,
                                               String from, String to, String whereJson) {
        return su.onno.query.KeysetWindow.drain(c -> movementsPage(subject, desc, from, to, null, whereJson,
                "_period", true, c, su.onno.query.KeysetWindow.MAX_LIMIT));
    }

    /**
     * The same newest-first movement slice as {@link #movements}, plus an explicit overflow signal
     * for non-UI callers that must not mistake the capped slice for the complete result set.
     */
    public BoundedRows movementsBounded(AccessSubject subject, AccumulationRegisterDescriptor desc,
                                        String from, String to) {
        return movementsBounded(subject, desc, from, to, null);
    }

    /** As {@link #movementsBounded(AccessSubject, AccumulationRegisterDescriptor, String, String)}, narrowed by {@code where}. */
    public BoundedRows movementsBounded(AccessSubject subject, AccumulationRegisterDescriptor desc,
                                        String from, String to, String whereJson) {
        SqlPredicate predicate = scope(subject, desc, desc.tableName())
                .andInto(Where.compile(whereJson, movementWhereSchema(desc)));
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM " + desc.tableName() + " WHERE _active = true");
        if (from != null) sql.append(" AND _period >= CAST(:from AS TIMESTAMP)");
        if (to != null) sql.append(" AND _period <= CAST(:to AS TIMESTAMP)");
        if (!predicate.isEmpty()) sql.append(" AND (").append(predicate.sql()).append(")");
        sql.append(" ORDER BY _period DESC LIMIT :cap");

        List<Map<String, Object>> rows = jdbi.withHandle(h -> {
            var query = h.createQuery(sql.toString());
            if (from != null) query.bind("from", from);
            if (to != null) query.bind("to", to);
            predicate.bindings().forEach(query::bind);
            query.bind("cap", MOVEMENTS_CAP + 1);
            return query.mapToMap().list();
        });
        return bounded(subject, desc, rows, MOVEMENTS_CAP);
    }

    // ---------------------------------------------------------------- where

    /**
     * What a {@link Where} over movement rows may name: the dimensions and resources by field name,
     * plus {@code period}, {@code recorder} (the posting document's id) and {@code movementType}
     * ({@code "RECEIPT"}/{@code "EXPENSE"}).
     */
    public Where.Schema movementWhereSchema(AccumulationRegisterDescriptor desc) {
        Map<String, Where.Type> system = new java.util.LinkedHashMap<>();
        system.put("_period", Where.Type.DATETIME);
        system.put("_document_ref", Where.Type.UUID);
        system.put("_movement_type", Where.Type.TEXT);
        return Where.Schema.of(fields(desc), system, field -> storageColumn(desc, field));
    }

    /** What a {@link Where} over balance rows may name: the dimensions and the resources' balances. */
    public Where.Schema balanceWhereSchema(AccumulationRegisterDescriptor desc) {
        return Where.Schema.of(fields(desc), Map.of(), field -> storageColumn(desc, field));
    }

    /**
     * The storage column for a logical register field name ({@code period}, {@code recorder},
     * {@code movementType}, a dimension/resource field), or the name itself (a storage column
     * passes through and is checked against the schema's allowlist by the caller).
     */
    public static String storageColumn(AccumulationRegisterDescriptor desc, String field) {
        if (field == null || field.isBlank()) return field;
        switch (field) {
            case "period" -> { return "_period"; }
            case "recorder" -> { return "_document_ref"; }
            case "movementType" -> { return "_movement_type"; }
            default -> { }
        }
        for (AttributeDescriptor a : fields(desc)) {
            if (a.fieldName().equals(field)) return a.columnName();
        }
        return field;
    }

    private static List<AttributeDescriptor> fields(AccumulationRegisterDescriptor desc) {
        List<AttributeDescriptor> all = new ArrayList<>(desc.dimensions());
        all.addAll(desc.resources());
        return all;
    }

    /** Movement rows in the logical vocabulary of the generated client: field names and {@code period}/{@code recorder}/{@code movementType}. */
    public static List<Map<String, Object>> logical(AccumulationRegisterDescriptor desc, List<Map<String, Object>> rows) {
        return EntityJsonRepresentation.accumulationRegisters(desc, rows);
    }

    /**
     * One keyset page of movements, ordered by a validated column (default {@code _period}) with
     * {@code _id} as tie-breaker, narrowed by the grid's declarative filters. Refs are resolved like
     * {@link #movements}.
     */
    public su.onno.query.KeysetWindow.Page<Map<String, Object>> movementsPage(AccessSubject subject,
            AccumulationRegisterDescriptor desc, String from, String to, ListFilter.Result filters,
            String sortColumn, boolean descending, String cursor, int limit) {
        return movementsPage(subject, desc, from, to, filters, null, sortColumn, descending, cursor, limit);
    }

    /** As the overload above, also narrowed by a {@link Where} predicate over the movement rows. */
    public su.onno.query.KeysetWindow.Page<Map<String, Object>> movementsPage(AccessSubject subject,
            AccumulationRegisterDescriptor desc, String from, String to, ListFilter.Result filters,
            String whereJson, String sortColumn, boolean descending, String cursor, int limit) {
        String col = safeSort(storageColumn(desc, sortColumn), "_period", movementColumns(desc));
        ListFilter.Result scoped = withWhere(scope(subject, desc, desc.tableName()).andInto(normalizedFilters(filters)),
                Where.compile(whereJson, movementWhereSchema(desc)));
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

    /** A list filter with a compiled {@link Where} ANDed in (binding names never collide: {@code wh*}). */
    private static ListFilter.Result withWhere(ListFilter.Result filters, SqlPredicate where) {
        if (where == null || where.isEmpty()) return filters;
        if (filters == null || filters.isEmpty()) return new ListFilter.Result(where.sql(), where.bindings());
        Map<String, Object> merged = new java.util.LinkedHashMap<>(filters.bindings());
        merged.putAll(where.bindings());
        return new ListFilter.Result("(" + filters.sql() + ") AND (" + where.sql() + ")", merged);
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
        return balancePage(subject, desc, filters, null, sortColumn, descending, cursor, limit, asOf);
    }

    /**
     * One keyset page of balance rows (current totals, or as of {@code asOf}), narrowed by the
     * grid's filters and a {@link Where} over the balance rows (see {@link #balanceWhereSchema}),
     * which reads a resource as its balance value.
     */
    public su.onno.query.KeysetWindow.Page<Map<String, Object>> balancePage(AccessSubject subject,
            AccumulationRegisterDescriptor desc, ListFilter.Result filters, String whereJson, String sortColumn,
            boolean descending, String cursor, int limit, String asOf) {
        BalanceSource source = balanceSource(subject, desc,
                withWhere(normalizedFilters(filters), Where.compile(whereJson, balanceWhereSchema(desc))), asOf);
        List<su.onno.query.KeysetWindow.Order> order = new ArrayList<>();
        String sort = safeSort(storageColumn(desc, sortColumn), null, balanceColumns(desc));
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
            throw new ServiceException(ServiceException.Status.BAD_REQUEST, "Balance is only available for BALANCE registers");
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
            throw new ServiceException(ServiceException.Status.BAD_REQUEST,
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

    /** Current balances (the materialized totals) matching {@code where} over the balance rows. */
    public List<Map<String, Object>> balance(AccessSubject subject, AccumulationRegisterDescriptor desc,
                                             String whereJson) {
        return balance(subject, desc, whereJson, null);
    }

    /**
     * Every balance row matching {@code where} (see {@link #balanceWhereSchema}), current or at an
     * {@code asOf} cutoff — the complete result, read page by page.
     */
    public List<Map<String, Object>> balance(AccessSubject subject, AccumulationRegisterDescriptor desc,
                                             String whereJson, String asOf) {
        return su.onno.query.KeysetWindow.drain(c -> balancePage(subject, desc, null, whereJson, null, false, c,
                su.onno.query.KeysetWindow.MAX_LIMIT, asOf));
    }

    /**
     * Balances at an inclusive ISO-8601 cutoff; null reads materialized current totals. A date-time
     * ({@code 2026-03-31T18:00}) includes movements up to and at that instant; a bare date
     * ({@code 2026-03-31}) includes that whole day. Offsets and zones are rejected, because register
     * periods are local date-times. {@code where} (see {@link #balanceWhereSchema}) selects balance
     * rows — by dimension and by the resources' balance values — after aggregation, so it means the
     * same with and without a cutoff. Reports, instead of hiding, a result over the row cap.
     */
    public BoundedRows balanceBounded(AccessSubject subject, AccumulationRegisterDescriptor desc,
                                      String whereJson, String asOf) {
        if (desc.accumulationType() != AccumulationType.BALANCE) {
            throw new ServiceException(ServiceException.Status.BAD_REQUEST,
                    "Balance is only available for BALANCE registers");
        }
        AsOf cutoff = AsOf.parse(asOf);
        SqlPredicate where = Where.compile(whereJson, balanceWhereSchema(desc));

        String table = cutoff == null ? desc.totalsTableName() : desc.tableName();
        ScopeClause scope = scope(subject, desc, table);
        String dimColumns = desc.dimensions().stream()
                .map(AttributeDescriptor::columnName).collect(Collectors.joining(", "));
        StringBuilder source = new StringBuilder();
        if (cutoff == null) {
            source.append("(SELECT * FROM ").append(desc.totalsTableName()).append(scope.where()).append(") balances");
        } else {
            // Balances as of the cutoff, computed from the movements; `where` then reads them like totals.
            source.append("(SELECT ");
            if (!dimColumns.isEmpty()) source.append(dimColumns).append(", ");
            source.append(desc.resources().stream()
                    .map(r -> signedSum(r.columnName()) + " AS " + r.columnName())
                    .collect(Collectors.joining(", ")));
            source.append(" FROM ").append(desc.tableName())
                    .append(" WHERE _active = true AND _period ").append(cutoff.operator()).append(" :asOf")
                    .append(scope.and());
            if (!dimColumns.isEmpty()) source.append(" GROUP BY ").append(dimColumns);
            source.append(") balances");
        }
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(source);
        if (!where.isEmpty()) sql.append(" WHERE ").append(where.sql());
        if (!dimColumns.isEmpty()) sql.append(" ORDER BY ").append(dimColumns);
        sql.append(" LIMIT :cap");

        List<Map<String, Object>> rows = jdbi.withHandle(h -> {
            var query = h.createQuery(sql.toString());
            if (cutoff != null) query.bind("asOf", cutoff.bound());
            scope.bind(query);
            where.bindings().forEach(query::bind);
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
                throw new ServiceException(ServiceException.Status.BAD_REQUEST,
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

    /**
     * Turnover in {@code [from, to]}: the resources' signed sums per dimension tuple, over the
     * movement rows {@code where} (see {@link #movementWhereSchema}) selects — so a turnover can be
     * narrowed by dimension, by {@code recorder} or by {@code movementType}.
     */
    public List<Map<String, Object>> turnover(AccessSubject subject, AccumulationRegisterDescriptor desc,
                                              String from, String to, String whereJson) {
        return su.onno.query.KeysetWindow.drain(c -> turnoverPage(subject, desc, from, to, Map.of(), whereJson, c,
                su.onno.query.KeysetWindow.MAX_LIMIT));
    }

    /** Turnover narrowed by dimension equality values (dimension field name → value). */
    public List<Map<String, Object>> turnover(AccessSubject subject, AccumulationRegisterDescriptor desc,
                                              String from, String to, Map<String, String> filters) {
        return su.onno.query.KeysetWindow.drain(c -> turnoverPage(subject, desc, from, to, filters, null, c,
                su.onno.query.KeysetWindow.MAX_LIMIT));
    }

    public su.onno.query.KeysetWindow.Page<Map<String, Object>> turnoverPage(AccessSubject subject,
            AccumulationRegisterDescriptor desc, String from, String to, Map<String, String> requestFilters,
            String cursor, int limit) {
        return turnoverPage(subject, desc, from, to, requestFilters, null, cursor, limit);
    }

    /**
     * One keyset page of turnover rows: dimension equality values from {@code requestFilters} (other
     * request parameters are ignored) and a {@link Where} over the movement rows, ANDed.
     */
    public su.onno.query.KeysetWindow.Page<Map<String, Object>> turnoverPage(AccessSubject subject,
            AccumulationRegisterDescriptor desc, String from, String to, Map<String, String> requestFilters,
            String whereJson, String cursor, int limit) {
        // The controller passes every request parameter; keep only dimensions so cursor/limit
        // never leak into the SQL or the cursor context (page 2 would otherwise mismatch page 1).
        Map<String, String> filters = dimensionValues(desc, requestFilters);
        ListFilter.Result dims = dimensionFilters(desc, filters);
        SqlPredicate predicate = scope(subject, desc, desc.tableName())
                .andInto(Where.compile(whereJson, movementWhereSchema(desc)));
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
        if (!predicate.isEmpty()) sql.append(" AND (").append(predicate.sql()).append(")");
        appendFilters(sql, dims);
        if (!dimColumns.isEmpty()) {
            sql.append(" GROUP BY ").append(dimColumns);
        }

        var order = desc.dimensions().stream()
                .map(d -> new su.onno.query.KeysetWindow.Order(d.columnName(), false)).toList();
        // An aggregate without dimensions is a single-row collection, ordered by its projected sum.
        if (order.isEmpty()) order = List.of(new su.onno.query.KeysetWindow.Order(desc.resources().getFirst().columnName(), false));
        var page = KeysetWindow.collection(jdbi, "(" + sql + ") turnover", "*", "1=1", query -> {
            query.bind("from", from).bind("to", to);
            predicate.bindings().forEach(query::bind);
            bindFilters(query, dims);
        }, order, cursor, limit, su.onno.query.Cursor.context("turnover", desc.tableName(), from, to,
                filters, EntityQuerySupport.cursorSubject(subject), predicate.sql(), predicate.bindings()));
        resolveAll(subject, desc, page.rows());
        return page;
    }

    /**
     * Grouped aggregates over the register's movements — the register counterpart of the
     * catalog/document {@code /aggregate} read, same request and envelope (see
     * {@link AggregateBuckets}). Field names are logical ({@code period}, a dimension, a resource);
     * {@code sum}/{@code avg}/{@code min}/{@code max} of a resource read it signed (receipts add,
     * expenses subtract), so a sum is the turnover; {@code count} counts movements. {@code where}
     * selects movement rows (see {@link #movementWhereSchema}); {@code dateField} defaults to
     * {@code period}.
     */
    public Map<String, Object> aggregate(AccessSubject subject, AccumulationRegisterDescriptor desc,
                                         AggregateBuckets.Request request) {
        Where.Schema schema = movementWhereSchema(desc);
        SqlPredicate predicate = scope(subject, desc, desc.tableName())
                .andInto(Where.compile(request.where(), schema));
        String dateField = request.dateField() == null || request.dateField().isBlank()
                ? "_period" : storageColumn(desc, request.dateField());
        AggregateBuckets.Request storage = new AggregateBuckets.Request(
                request.metric(), storageColumn(desc, request.field()),
                request.metric2(), storageColumn(desc, request.field2()),
                storageColumn(desc, request.groupBy()), request.groupByDate(),
                storageColumn(desc, request.seriesBy()), request.where(),
                dateField, request.from(), request.to());
        Set<String> resources = desc.resources().stream()
                .map(r -> r.columnName().toLowerCase())
                .collect(Collectors.toSet());
        AggregateBuckets.Source source = new AggregateBuckets.Source(desc.tableName(), "_active = true",
                column -> resources.contains(column) ? signed(column) : column);
        return AggregateBuckets.run(jdbi, refResolver, fields(desc), source,
                schema.columns().keySet(), storage, predicate, subject);
    }

    private static String signed(String resourceColumn) {
        return "(CASE WHEN _movement_type = 'RECEIPT' THEN " + resourceColumn + " ELSE -" + resourceColumn + " END)";
    }

    private static String signedSum(String resourceColumn) {
        return "SUM" + signed(resourceColumn);
    }

    /**
     * Convert HTTP query values to the JDBC type declared by the register dimension. An enum takes
     * its stored UUID or its constant name, exactly as a {@code where} does.
     */
    private static Object filterValue(AttributeDescriptor dimension, String raw) {
        Class<?> type = dimension.javaType();
        if (type.isEnum()) {
            @SuppressWarnings("unchecked")
            Class<? extends Enum<?>> enumType = (Class<? extends Enum<?>>) type;
            return EnumValues.filterId(enumType, raw, "Invalid value for dimension " + dimension.fieldName());
        }
        if (dimension.isRef() || type == java.util.UUID.class) {
            return java.util.UUID.fromString(raw);
        }
        if (type == java.math.BigDecimal.class) return new java.math.BigDecimal(raw);
        if (type == Integer.class || type == int.class) return Integer.valueOf(raw);
        if (type == Long.class || type == long.class) return Long.valueOf(raw);
        if (type == Double.class || type == double.class) return Double.valueOf(raw);
        if (type == Float.class || type == float.class) return Float.valueOf(raw);
        if (type == Boolean.class || type == boolean.class) return Boolean.valueOf(raw);
        return raw;
    }

    private void resolveAll(AccessSubject subject, AccumulationRegisterDescriptor desc,
                            List<Map<String, Object>> rows) {
        refResolver.resolveAttributes(rows, fields(desc), subject);
    }
}
