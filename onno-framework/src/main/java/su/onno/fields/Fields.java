package su.onno.fields;

import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Objects;

/** Resolves and composes typed {@link Field} references. */
public final class Fields {

    private Fields() {
    }

    /** Java bean-property name behind a getter reference. */
    public static String name(Field<?, ?> field) {
        Objects.requireNonNull(field, "field");
        try {
            Method writeReplace = field.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            SerializedLambda serialized = (SerializedLambda) writeReplace.invoke(field);
            String method = serialized.getImplMethodName();
            if (method.startsWith("get") && method.length() > 3) {
                return decapitalize(method.substring(3));
            }
            if (method.startsWith("is") && method.length() > 2) {
                return decapitalize(method.substring(2));
            }
            throw new IllegalArgumentException(
                    "Field references must use a JavaBean getter (getX/isX), got " + method);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to resolve field name from method reference", e);
        }
    }

    /** Dot path made from compiler-checked property references. */
    @SafeVarargs
    public static String path(Field<?, ?>... fields) {
        Objects.requireNonNull(fields, "fields");
        return java.util.Arrays.stream(fields).map(Fields::name)
                .collect(java.util.stream.Collectors.joining("."));
    }

    private static String decapitalize(String value) {
        if (value.length() > 1
                && Character.isUpperCase(value.charAt(0))
                && Character.isUpperCase(value.charAt(1))) {
            return value;
        }
        return value.substring(0, 1).toLowerCase(Locale.ROOT) + value.substring(1);
    }
}
