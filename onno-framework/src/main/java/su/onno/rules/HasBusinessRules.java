package su.onno.rules;

import java.util.List;

/**
 * A catalog or document with business rules: invariants across several fields, or that depend on
 * business logic, checked before write and before posting. Implement it and return typed
 * {@link BusinessRule}s.
 *
 * <p>A rule about one field alone ("required", a length, a range, a format) is a Jakarta Bean
 * Validation annotation on that field instead ({@code @NotNull}, {@code @Size}, {@code @Min},
 * {@code @Pattern}, ...).</p>
 *
 * <pre>
 * public List&lt;BusinessRule&gt; rules() {
 *     return List.of(
 *         new BusinessRule("dates-ordered", "The end must follow the start",
 *                 () -&gt; endsAt == null || startsAt == null || endsAt.isAfter(startsAt)),
 *         BusinessRule.onField("discount", "A discount needs a manager's approval",
 *                 () -&gt; discount == null || discount.signum() == 0 || approvedBy != null));
 * }
 * </pre>
 */
public interface HasBusinessRules {

    List<BusinessRule> rules();
}
