package com.onec.validation;

import java.util.List;
import java.util.Map;

/**
 * Thrown when an entity fails framework validation on the write path — a missing required
 * attribute, an out-of-range / malformed value, or a failed {@link com.onec.rules.BusinessRule}.
 *
 * <p>A distinct typed exception (not a raw {@link IllegalStateException}) so the web layer maps it
 * to a 4xx instead of a 500. It carries both a single {@link #getField()} (for the simple
 * one-field case — e.g. a missing required attribute on a public intake {@code repository.save},
 * issue #32) and the richer {@link #fieldErrors()} / {@link #formErrors()} collected by
 * {@link ValidationErrors}, so a form can map every failure onto the right input at once. The UI
 * starter registers a handler that performs the HTTP mapping; headless apps can catch this
 * directly.</p>
 */
public class ValidationException extends RuntimeException {

    /** The offending field's name when the failure is attributable to a single field; else {@code null}. */
    private final String field;
    private final transient Map<String, List<String>> fieldErrors;
    private final transient List<String> formErrors;

    public ValidationException(String message) {
        this(message, (String) null);
    }

    /** A single-field (or, when {@code field} is null, form-level) failure. */
    public ValidationException(String message, String field) {
        super(message);
        this.field = field;
        this.fieldErrors = field == null ? Map.of() : Map.of(field, List.of(message));
        this.formErrors = field == null ? List.of(message) : List.of();
    }

    /** Multiple collected failures, keyed by field plus any form-level messages. */
    public ValidationException(String message, Map<String, List<String>> fieldErrors, List<String> formErrors) {
        super(message);
        // Surface a single field through getField() when there's exactly one and nothing form-level.
        this.field = fieldErrors.size() == 1 && formErrors.isEmpty()
                ? fieldErrors.keySet().iterator().next() : null;
        this.fieldErrors = fieldErrors;
        this.formErrors = formErrors;
    }

    public String getField() {
        return field;
    }

    /** Per-field messages, keyed by attribute field name, in declaration order. */
    public Map<String, List<String>> fieldErrors() {
        return fieldErrors;
    }

    /** Cross-field / form-level messages not tied to a specific input. */
    public List<String> formErrors() {
        return formErrors;
    }
}
