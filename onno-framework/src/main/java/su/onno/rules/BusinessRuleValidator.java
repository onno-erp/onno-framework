package su.onno.rules;

import su.onno.validation.ValidationErrors;

/**
 * Evaluates a {@link Validated} entity's typed {@link BusinessRule}s before write and before
 * posting. Collects <em>all</em> failures (a field-scoped rule attaches to its field; a
 * cross-field rule to the form) so the caller can report them together rather than one at a time.
 * No expression parsing — the rules are plain Java predicates.
 */
public class BusinessRuleValidator {

    /** Collect every failing rule into {@code errors} (field- or form-scoped). */
    public void collect(Object target, ValidationErrors errors) {
        if (!(target instanceof Validated validated)) {
            return;
        }
        for (BusinessRule rule : validated.rules()) {
            if (!rule.holds()) {
                String message = rule.message() == null || rule.message().isBlank()
                        ? "Business rule failed: " + rule.name()
                        : rule.message();
                errors.field(rule.field(), message); // null/blank field -> form-level
            }
        }
    }

    /** Collect failures and throw a {@link su.onno.validation.ValidationException} if any. */
    public void validate(Object target) {
        ValidationErrors errors = new ValidationErrors();
        collect(target, errors);
        errors.throwIfAny();
    }
}
