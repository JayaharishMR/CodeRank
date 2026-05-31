package com.coderank.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Centralised exception handling to return clean, predictable error responses.
 *
 * Without this handler, Spring Security's filter chain intercepts validation
 * failures before the controller runs and maps them to 401/403 (because the
 * security filter sees an unresolvable request, not a bad payload). This
 * handler ensures malformed input correctly returns 400 with a human-readable
 * field-level error message instead.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        // Concatenate all field errors into a single string so the client gets
        // one consolidated message rather than an opaque framework default
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
