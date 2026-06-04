package com.onec.validation;

import java.util.List;
import java.util.Map;

/**
 * Thrown when an entity fails validation before write. Carries field-scoped messages
 * ({@code fieldName -> messages}) plus any form-level (cross-field) messages, so the API can
 * report a 422 the client maps onto the right inputs. Accumulated via {@link ValidationErrors}.
 */
public class ValidationException extends RuntimeException {

    private final transient Map<String, List<String>> fieldErrors;
    private final transient List<String> formErrors;

    public ValidationException(String message, Map<String, List<String>> fieldErrors, List<String> formErrors) {
        super(message);
        this.fieldErrors = fieldErrors;
        this.formErrors = formErrors;
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
