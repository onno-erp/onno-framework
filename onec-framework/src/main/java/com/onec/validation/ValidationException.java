package com.onec.validation;

/**
 * Thrown when an entity fails framework validation on the write path — currently a missing required
 * attribute (see {@code OnecBeforeConvertCallback.validateRequired}).
 *
 * <p>It is a distinct, typed exception (not a raw {@link IllegalStateException}) so the web layer can
 * map it to HTTP 400 instead of letting a client-input error surface as a 500. This matters for
 * public write endpoints (e.g. lead intake via {@code repository.save}), where a bad request should
 * read as a 400 by default (issue #32). The UI starter registers an exception handler that performs
 * this mapping; headless apps can catch {@code ValidationException} directly.
 */
public class ValidationException extends RuntimeException {

    /** The offending field's name, if the failure is attributable to a single field; otherwise {@code null}. */
    private final String field;

    public ValidationException(String message) {
        this(message, null);
    }

    public ValidationException(String message, String field) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
