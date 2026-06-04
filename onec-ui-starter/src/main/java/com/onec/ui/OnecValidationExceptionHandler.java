package com.onec.ui;

import com.onec.validation.ValidationException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps the framework's {@link ValidationException} (e.g. a missing required attribute on a
 * {@code repository.save}) to HTTP 400 with a small JSON body, so a client-input error reads as a
 * Bad Request instead of a 500 (issue #32). Applies application-wide, so it also covers app-written
 * controllers and public intake endpoints that persist via the repository.
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
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
