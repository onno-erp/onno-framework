package su.onno.query;

import su.onno.fields.Field;
import su.onno.fields.Fields;

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
    static String name(Field<?, ?> methodReference) {
        return Fields.name(methodReference);
    }
}
