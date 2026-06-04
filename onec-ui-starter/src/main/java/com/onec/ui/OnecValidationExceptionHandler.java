package com.onec.ui;

import com.onec.validation.ValidationException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps the framework's {@link ValidationException} (a missing required attribute, an out-of-range /
 * malformed value, or a failed business rule) to HTTP 400 with a small JSON body, so a client-input
 * error reads as a Bad Request instead of a 500 (issue #32). Applies application-wide, so it also
 * covers app-written controllers and public intake endpoints that persist via the repository.
 *
 * <p>When the failure carries per-field detail it's emitted as {@code fieldErrors} (and any
 * cross-field messages as {@code formErrors}), which the edit form maps onto the right inputs.</p>
 */
@RestControllerAdvice
public class OnecValidationExceptionHandler {

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Map<String, Object>> handle(ValidationException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "validation");
        body.put("message", ex.getMessage());
        if (ex.getField() != null) {
            body.put("field", ex.getField());
        }
        if (!ex.fieldErrors().isEmpty()) {
            body.put("fieldErrors", ex.fieldErrors());
        }
        if (!ex.formErrors().isEmpty()) {
            body.put("formErrors", ex.formErrors());
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
