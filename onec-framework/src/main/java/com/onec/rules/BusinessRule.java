package com.onec.rules;

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
 */
public record BusinessRule(String name, String message, BooleanSupplier condition) {

    /** Evaluate the rule against the current entity state. */
    public boolean holds() {
        return condition.getAsBoolean();
    }
}
