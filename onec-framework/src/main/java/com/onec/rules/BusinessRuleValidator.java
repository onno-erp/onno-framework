package com.onec.rules;

/**
 * Evaluates a {@link Validated} entity's typed {@link BusinessRule}s before write
 * and before posting, throwing on the first failure. No expression parsing — the
 * rules are plain Java predicates.
 */
public class BusinessRuleValidator {

    public void validate(Object target) {
        if (!(target instanceof Validated validated)) {
            return;
        }
        for (BusinessRule rule : validated.rules()) {
            if (!rule.holds()) {
                String message = rule.message() == null || rule.message().isBlank()
                        ? "Business rule failed: " + rule.name()
                        : rule.message();
                throw new IllegalStateException(message);
            }
        }
    }
}
