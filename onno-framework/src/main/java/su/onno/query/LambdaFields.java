package su.onno.query;

import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;

/**
 * Resolves the bean-property name behind a serializable method-reference
 * ({@code Customer::getName} &rarr; {@code "name"}). This is the same
 * {@code SerializedLambda} trick {@code RegisterQueryBuilder} already uses; it is
 * lifted here so the general query layer and the register virtual tables share one
 * implementation.
 */
final class LambdaFields {

    private LambdaFields() {
    }

    /** Field name for a getter reference, e.g. {@code Customer::getName} &rarr; {@code "name"}. */
    static String name(Serializable methodReference) {
        try {
            Method writeReplace = methodReference.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            SerializedLambda serialized = (SerializedLambda) writeReplace.invoke(methodReference);
            String methodName = serialized.getImplMethodName();
            if (methodName.startsWith("get") && methodName.length() > 3) {
                return Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
            } else if (methodName.startsWith("is") && methodName.length() > 2) {
                return Character.toLowerCase(methodName.charAt(2)) + methodName.substring(3);
            }
            return methodName;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to resolve field name from method reference", e);
        }
    }
}
