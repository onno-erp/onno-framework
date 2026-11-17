package su.onno.metadata;

import java.util.List;

/**
 * One attribute (or register dimension/resource) as the model declares it: name, storage, type,
 * reference target and validation constraints. Presentation (order, grouping, widths, widgets,
 * visibility) is not part of the model — clients decide it.
 */
public record AttributeDescriptor(
        String fieldName,
        String displayName,
        String columnName,
        Class<?> javaType,
        int length,
        boolean required,
        boolean isRef,
        String refTarget,
        List<ReferenceTargetDescriptor> refTargets,
        int precision,
        int scale,
        Constraints constraints,
        boolean secret,
        List<String> previousNames,
        boolean searchIndex
) {

    /** A single-target (or non-reference) attribute without rename history or search index. */
    public AttributeDescriptor(
            String fieldName, String displayName, String columnName, Class<?> javaType,
            int length, boolean required, boolean isRef, String refTarget,
            int precision, int scale, Constraints constraints, boolean secret) {
        this(fieldName, displayName, columnName, javaType, length, required, isRef, refTarget,
                List.of(), precision, scale, constraints, secret, List.of(), false);
    }

    public boolean isPolymorphicRef() {
        return !refTargets.isEmpty();
    }

    /**
     * Declarative validation bounds for an attribute, from {@code @Attribute}. {@code min}/{@code max}
     * are {@code NaN} when unset; {@code minLength} is {@code 0} when unset; {@code pattern} blank when
     * unset. The maximum string length is the descriptor's {@link #length()}.
     */
    public record Constraints(double min, double max, int minLength, String pattern, boolean email) {
        public static final Constraints NONE = new Constraints(Double.NaN, Double.NaN, 0, "", false);

        public boolean hasMin() { return !Double.isNaN(min); }
        public boolean hasMax() { return !Double.isNaN(max); }
    }
}
