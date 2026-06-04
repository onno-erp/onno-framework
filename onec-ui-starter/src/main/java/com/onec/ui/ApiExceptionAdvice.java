package com.onec.ui;

import com.onec.validation.ValidationException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps a {@link ValidationException} (thrown before write when an entity fails its declarative
 * constraints or business rules) to HTTP 422 with a body the client can map onto the form:
 * {@code { message, fieldErrors: { field: [..] }, formErrors: [..] }}. Registered as a bean in
 * {@link UiAutoConfiguration} (the UI controllers aren't component-scanned).
 */
@RestControllerAdvice
public class ApiExceptionAdvice {

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Map<String, Object>> onValidation(ValidationException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", ex.getMessage());
        body.put("fieldErrors", ex.fieldErrors());
        body.put("formErrors", ex.formErrors());
        return ResponseEntity.unprocessableEntity().body(body);
    }
}
