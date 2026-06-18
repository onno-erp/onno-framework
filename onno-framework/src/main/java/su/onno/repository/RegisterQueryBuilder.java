package su.onno.repository;

import su.onno.model.AccumulationRecord;
import su.onno.posting.RegisterPersistence;

import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RegisterQueryBuilder<T extends AccumulationRecord> {

    public enum QueryType { BALANCE, TURNOVER }

    private final RegisterPersistence<T> persistence;
    private QueryType queryType = QueryType.BALANCE;
    private LocalDateTime atDate;
    private LocalDateTime fromDate;
    private LocalDateTime toDate;
    private final List<String> groupByFields = new ArrayList<>();
    private final Map<String, Object> filters = new LinkedHashMap<>();

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
