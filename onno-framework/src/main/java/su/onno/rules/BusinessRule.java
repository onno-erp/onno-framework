package su.onno.rules;

import java.util.function.BooleanSupplier;

/**
 * A named validation rule expressed as typed Java — the replacement for the old
 * {@code @BusinessRule(expression = "...")} string mini-language. The condition is
 * a {@link BooleanSupplier} closing over the entity's fields, so it's checked by
 * the compiler, refactorable, and debuggable; the {@code name}/{@code message}
 * keep it enumerable for manifests and error reporting.
 *
 * <pre>
 * new BusinessRule("gross-positive", "Gross must be positive",
 *         () -&gt; gross != null &amp;&amp; gross.signum() &gt; 0)
 * </pre>
 *
 * <p>{@code field} optionally names the form field this rule guards, so a failure surfaces as an
 * inline error on that input rather than a form-level message. Omit it (or use the 3-arg form) for
 * a cross-field rule, whose message attaches to the form as a whole.</p>
 */
public record BusinessRule(String name, String field, String message, BooleanSupplier condition) {

    /** A cross-field (form-level) rule — no specific field. */
    public BusinessRule(String name, String message, BooleanSupplier condition) {
        this(name, null, message, condition);
    }

    /**
     * A rule scoped to a single field — its message shows inline on that field. The rule name
     * defaults to the field name.
     *
     * <pre>BusinessRule.onField("client", "Client is required", () -&gt; client != null)</pre>
     */
    public static BusinessRule onField(String field, String message, BooleanSupplier condition) {
        return new BusinessRule(field, field, message, condition);
    }

    /** Evaluate the rule against the current entity state. */
    public boolean holds() {
        return condition.getAsBoolean();
    }
}
