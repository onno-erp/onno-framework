package su.onno.repository;

import su.onno.model.AccumulationRecord;
import su.onno.posting.RegisterPersistence;

import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RegisterQueryBuilder<T extends AccumulationRecord> {

    public enum QueryType { BALANCE, TURNOVER }

    /** A {@code (col1, col2, …) IN ((v1, v2, …), …)} predicate over a set of dimension tuples. */
    public record TupleInFilter(List<String> fieldNames, List<List<Object>> tuples) {
    }

    private final RegisterPersistence<T> persistence;
    private QueryType queryType = QueryType.BALANCE;
    private LocalDateTime atDate;
    private LocalDateTime fromDate;
    private LocalDateTime toDate;
    private final List<String> groupByFields = new ArrayList<>();
    private final Map<String, Object> filters = new LinkedHashMap<>();
    private final Map<String, Collection<?>> inFilters = new LinkedHashMap<>();
    private final List<TupleInFilter> tupleFilters = new ArrayList<>();

    public RegisterQueryBuilder(RegisterPersistence<T> persistence) {
        this.persistence = persistence;
    }

    public RegisterQueryBuilder<T> balance() {
        this.queryType = QueryType.BALANCE;
        return this;
    }

    public RegisterQueryBuilder<T> turnover() {
        this.queryType = QueryType.TURNOVER;
        return this;
    }

    public RegisterQueryBuilder<T> at(LocalDateTime pointInTime) {
        this.atDate = pointInTime;
        return this;
    }

    public RegisterQueryBuilder<T> from(LocalDateTime from) {
        this.fromDate = from;
        return this;
    }

    public RegisterQueryBuilder<T> to(LocalDateTime to) {
        this.toDate = to;
        return this;
    }

    public <R> RegisterQueryBuilder<T> groupBy(FieldReference<T, R> field) {
        groupByFields.add(resolveFieldName(field));
        return this;
    }

    public <R> RegisterQueryBuilder<T> where(FieldReference<T, R> field, R value) {
        String fieldName = resolveFieldName(field);
        filters.put(fieldName, value);
        return this;
    }

    /**
     * Restrict one dimension to a set of values &mdash; rendered as {@code col IN (…)}. Lets a
     * caller read balances for exactly the dimension values on a document in a single query
     * instead of fetching the whole slice and filtering in Java. An empty collection matches no
     * rows. Values may be raw column values or {@code Ref}s (unwrapped to their id).
     */
    public <R> RegisterQueryBuilder<T> whereIn(FieldReference<T, R> field, Collection<R> values) {
        inFilters.put(resolveFieldName(field), values);
        return this;
    }

    /**
     * Restrict a pair of dimensions to a set of tuples &mdash; rendered as
     * {@code (colA, colB) IN ((a1, b1), (a2, b2), …)}. This is the register-read analog of 1C's
     * {@code Остатки(…, (dimA, dimB) В (…))}: fetch balances for exactly a document's
     * {@code (dimA, dimB)} pairs in one query. An empty collection matches no rows.
     */
    public <A, B> RegisterQueryBuilder<T> whereIn(FieldReference<T, A> fieldA,
                                                  FieldReference<T, B> fieldB,
                                                  Collection<? extends List<?>> tuples) {
        List<String> fieldNames = List.of(resolveFieldName(fieldA), resolveFieldName(fieldB));
        List<List<Object>> rows = new ArrayList<>();
        for (List<?> tuple : tuples) {
            if (tuple.size() != fieldNames.size()) {
                throw new IllegalArgumentException(
                        "Tuple " + tuple + " has " + tuple.size() + " values but "
                                + fieldNames.size() + " dimensions were given");
            }
            rows.add(new ArrayList<>(tuple));
        }
        tupleFilters.add(new TupleInFilter(fieldNames, rows));
        return this;
    }

    public List<T> execute() {
        return persistence.executeQuery(this);
    }

    // --- Getters for RegisterPersistence ---

    public QueryType getQueryType() { return queryType; }
    public LocalDateTime getAtDate() { return atDate; }
    public LocalDateTime getFromDate() { return fromDate; }
    public LocalDateTime getToDate() { return toDate; }
    public List<String> getGroupByFields() { return groupByFields; }
    public Map<String, Object> getFilters() { return filters; }
    public Map<String, Collection<?>> getInFilters() { return inFilters; }
    public List<TupleInFilter> getTupleFilters() { return tupleFilters; }

    private static String resolveFieldName(Serializable lambda) {
        try {
            Method writeReplace = lambda.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            SerializedLambda serialized = (SerializedLambda) writeReplace.invoke(lambda);
            String methodName = serialized.getImplMethodName();
            if (methodName.startsWith("get") && methodName.length() > 3) {
                return Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
            } else if (methodName.startsWith("is") && methodName.length() > 2) {
                return Character.toLowerCase(methodName.charAt(2)) + methodName.substring(3);
            }
            return methodName;
        } catch (Exception e) {
            throw new RuntimeException("Failed to resolve field name from method reference", e);
        }
    }
}
