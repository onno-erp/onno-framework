package su.onno.metadata;

import java.util.List;
import java.util.Set;

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
     * Single-field validation bounds, merged from the Jakarta Bean Validation annotations on the field
     * ({@code @Size}, {@code @Min}/{@code @Max}, {@code @Pattern}, {@code @Email}, ...) and the
     * deprecated {@code @Attribute} flags, so clients and the schema read one view whichever was used.
     * {@code min}/{@code max} are inclusive and {@code NaN} when unset; {@code minLength} and
     * {@code maxLength} are {@code 0} when unset; {@code pattern} is blank when unset. The column's
     * storage length is the descriptor's {@link #length()}; {@code maxLength} is the validation limit
     * ({@code @Size(max)}), never above it.
     *
     * <p>{@code beanValidated} names the rules that come from a Jakarta annotation. When a Bean
     * Validation provider is active it enforces them (its semantics and messages), and
     * {@link su.onno.validation.AttributeValidator} skips them so a value is not reported twice.</p>
     */
    public record Constraints(double min, double max, int minLength, int maxLength, String pattern,
                              boolean email, Set<Rule> beanValidated) {
        public static final Constraints NONE = new Constraints(Double.NaN, Double.NaN, 0, 0, "", false, Set.of());

        public Constraints {
            pattern = pattern == null ? "" : pattern;
            beanValidated = beanValidated == null || beanValidated.isEmpty() ? Set.of() : Set.copyOf(beanValidated);
        }

        /** Bounds declared through {@code @Attribute} only (no {@code maxLength}, nothing bean-validated). */
        public Constraints(double min, double max, int minLength, String pattern, boolean email) {
            this(min, max, minLength, 0, pattern, email, Set.of());
        }

        public boolean hasMin() { return !Double.isNaN(min); }
        public boolean hasMax() { return !Double.isNaN(max); }
        public boolean hasMaxLength() { return maxLength > 0; }

        /** Whether {@code rule} was declared with a Jakarta annotation. */
        public boolean beanValidated(Rule rule) { return beanValidated.contains(rule); }

        /** A single-field rule, for {@link #beanValidated()}. */
        public enum Rule { REQUIRED, MIN, MAX, MIN_LENGTH, MAX_LENGTH, PATTERN, EMAIL }
    }
}
