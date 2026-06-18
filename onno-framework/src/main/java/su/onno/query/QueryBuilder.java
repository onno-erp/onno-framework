package su.onno.query;

import java.util.ArrayList;
import java.util.List;

/**
 * Fluent front-end that accumulates a {@link QuerySpec} and runs it through a
 * {@link QueryEngine}. DSL helpers from {@link Q} produce un-rooted paths; this builder
 * binds them to {@code <T>} as they are added, so callers never repeat the entity class.
 *
 * <pre>{@code
 * import static su.onno.query.Q.*;
 *
 * List<Row> rows = engine.from(SalesOrder.class)
 *     .select(col(SalesOrder::getNumber),
 *             ref(SalesOrder::getCustomer, Customer::getName))
 *     .where(eq(SalesOrder::getStatus, "APPROVED"))
 *     .orderBy(desc(SalesOrder::getDate))
 *     .fetch();
 * }</pre>
 */
public final class QueryBuilder<T> {

    private final QueryEngine engine;
    private final Class<T> from;
    private final List<Select> select = new ArrayList<>();
    private final List<Predicate> where = new ArrayList<>();
    private final List<Path> groupBy = new ArrayList<>();
    private final List<Order> orderBy = new ArrayList<>();
    private final List<Path> totals = new ArrayList<>();
    private Integer limit;
    private Integer offset;

    QueryBuilder(QueryEngine engine, Class<T> from) {
        this.engine = engine;
        this.from = from;
    }

    public QueryBuilder<T> select(Select... items) {
        for (Select item : items) {
            select.add(item.withRoot(from));
        }
        return this;
    }

    public QueryBuilder<T> where(Predicate... predicates) {
        for (Predicate predicate : predicates) {
            where.add(predicate.withRoot(from));
        }
        return this;
    }

    public QueryBuilder<T> groupBy(Path... paths) {
        for (Path path : paths) {
            groupBy.add(path.withRoot(from));
        }
        return this;
    }

    public QueryBuilder<T> orderBy(Order... orders) {
        for (Order order : orders) {
            orderBy.add(order.withRoot(from));
        }
        return this;
    }

    public QueryBuilder<T> totalsBy(Path... paths) {
        for (Path path : paths) {
            totals.add(path.withRoot(from));
        }
        return this;
    }

    public QueryBuilder<T> limit(int limit) {
        this.limit = limit;
        return this;
    }

    public QueryBuilder<T> offset(int offset) {
        this.offset = offset;
        return this;
    }

    /** The declarative spec this builder has assembled &mdash; usable without executing. */
    public QuerySpec toSpec() {
        return new QuerySpec(from, select, where, groupBy, orderBy, totals, limit, offset);
    }

    public List<Row> fetch() {
        return engine.fetch(toSpec());
    }

    public <D> List<D> fetchInto(Class<D> dtoType) {
        return engine.fetchInto(toSpec(), dtoType);
    }
}
