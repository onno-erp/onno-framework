package su.onno.repository;

import su.onno.model.AccumulationRecord;

import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public class RegisterFilter<T extends AccumulationRecord> {

    private final Map<String, Object> fieldFilters = new LinkedHashMap<>();

    public <R> RegisterFilter<T> where(FieldReference<T, R> getter, R value) {
        String fieldName = resolveFieldName(getter);
        fieldFilters.put(fieldName, value);
        return this;
    }

    /**
     * Restrict a dimension to a set of values &mdash; rendered as {@code col IN (…)} so a caller can
     * read balances for exactly a document's dimension values in one query. An empty collection
     * matches no rows.
     */
    public <R> RegisterFilter<T> whereIn(FieldReference<T, R> getter, Collection<R> values) {
        String fieldName = resolveFieldName(getter);
        fieldFilters.put(fieldName, values);
        return this;
    }

    public Map<String, Object> getFieldFilters() {
        return fieldFilters;
    }

    private static String resolveFieldName(Serializable lambda) {
        try {
            Method writeReplace = lambda.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            SerializedLambda serialized = (SerializedLambda) writeReplace.invoke(lambda);
            String methodName = serialized.getImplMethodName();
            return getterToFieldName(methodName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to resolve field name from method reference", e);
        }
    }

    private static String getterToFieldName(String methodName) {
        String fieldName;
        if (methodName.startsWith("get") && methodName.length() > 3) {
            fieldName = methodName.substring(3);
        } else if (methodName.startsWith("is") && methodName.length() > 2) {
            fieldName = methodName.substring(2);
        } else {
            return methodName;
        }
        return Character.toLowerCase(fieldName.charAt(0)) + fieldName.substring(1);
    }
}
