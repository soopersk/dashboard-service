package com.company.observability.exception;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import static com.company.observability.util.ObservabilityConstants.API_ERROR;

@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    private final MeterRegistry meterRegistry;

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoSuchElementException ex) {
        HttpStatus status = HttpStatus.NOT_FOUND;
        meterRegistry.counter(API_ERROR, "exception", ex.getClass().getSimpleName(), "status", String.valueOf(status.value())).increment();
        log.warn("event=api.error status={} exception={} message={}", status.value(), ex.getClass().getSimpleName(), ex.getMessage());
        return buildErrorResponse(status, ex.getMessage());
    }

    @ExceptionHandler(DomainNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleDomainNotFound(DomainNotFoundException ex) {
        HttpStatus status = HttpStatus.NOT_FOUND;
        meterRegistry.counter(API_ERROR, "exception", ex.getClass().getSimpleName(), "status", String.valueOf(status.value())).increment();
        log.warn("event=api.error status={} exception={} message={}", status.value(), ex.getClass().getSimpleName(), ex.getMessage());
        return buildErrorResponse(status, ex.getMessage());
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(SecurityException ex) {
        HttpStatus status = HttpStatus.FORBIDDEN;
        meterRegistry.counter(API_ERROR, "exception", ex.getClass().getSimpleName(), "status", String.valueOf(status.value())).increment();
        log.warn("event=api.error status={} exception={} message={}", status.value(), ex.getClass().getSimpleName(), ex.getMessage());
        return buildErrorResponse(status, ex.getMessage());
    }

    @ExceptionHandler(DomainAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleDomainAccessDenied(DomainAccessDeniedException ex) {
        HttpStatus status = HttpStatus.FORBIDDEN;
        meterRegistry.counter(API_ERROR, "exception", ex.getClass().getSimpleName(), "status", String.valueOf(status.value())).increment();
        log.warn("event=api.error status={} exception={} message={}", status.value(), ex.getClass().getSimpleName(), ex.getMessage());
        return buildErrorResponse(status, ex.getMessage());
    }

    @ExceptionHandler({DomainValidationException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> handleDomainValidation(RuntimeException ex) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        meterRegistry.counter(API_ERROR, "exception", ex.getClass().getSimpleName(), "status", String.valueOf(status.value())).increment();
        log.warn("event=api.error status={} exception={} message={}", status.value(), ex.getClass().getSimpleName(), ex.getMessage());
        return buildErrorResponse(status, ex.getMessage());
    }

    @ExceptionHandler(DomainConflictException.class)
    public ResponseEntity<Map<String, Object>> handleDomainConflict(DomainConflictException ex) {
        HttpStatus status = HttpStatus.CONFLICT;
        meterRegistry.counter(API_ERROR, "exception", ex.getClass().getSimpleName(), "status", String.valueOf(status.value())).increment();
        log.warn("event=api.error status={} exception={} message={}", status.value(), ex.getClass().getSimpleName(), ex.getMessage());
        return buildErrorResponse(status, ex.getMessage());
    }

    /**
     * Malformed/unbindable request bodies (bad JSON, strict enum rejection such as an unknown
     * frequency) — 400, not the generic 500.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        meterRegistry.counter(API_ERROR, "exception", ex.getClass().getSimpleName(), "status", String.valueOf(status.value())).increment();
        String message = ex.getMostSpecificCause().getMessage();
        log.warn("event=api.error status={} exception={} message={}", status.value(), ex.getClass().getSimpleName(), message);
        return buildErrorResponse(status, message);
    }

    @ExceptionHandler({MissingRequestHeaderException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<Map<String, Object>> handleMissingRequestInput(Exception ex) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        meterRegistry.counter(API_ERROR, "exception", ex.getClass().getSimpleName(), "status", String.valueOf(status.value())).increment();
        log.warn("event=api.error status={} exception={} message={}", status.value(), ex.getClass().getSimpleName(), ex.getMessage());
        return buildErrorResponse(status, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationErrors(MethodArgumentNotValidException ex) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        meterRegistry.counter(API_ERROR, "exception", ex.getClass().getSimpleName(), "status", String.valueOf(status.value())).increment();
        log.warn("event=api.error status={} exception={} fieldErrorCount={}", status.value(), ex.getClass().getSimpleName(), ex.getBindingResult().getFieldErrorCount());

        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
            errors.put(error.getField(), error.getDefaultMessage())
        );

        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", Instant.now());
        response.put("status", HttpStatus.BAD_REQUEST.value());
        response.put("error", "Validation Failed");
        response.put("errors", errors);

        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        meterRegistry.counter(API_ERROR, "exception", ex.getClass().getSimpleName(), "status", String.valueOf(status.value())).increment();
        log.warn("event=api.error status={} exception={} message={}", status.value(), ex.getClass().getSimpleName(), ex.getMessage());
        return buildErrorResponse(status, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        meterRegistry.counter(API_ERROR, "exception", ex.getClass().getSimpleName(), "status", String.valueOf(status.value())).increment();
        log.error("event=api.error status={} exception={} method={} uri={}", status.value(), ex.getClass().getSimpleName(), request.getMethod(), request.getRequestURI(), ex);
        return buildErrorResponse(status, "An unexpected error occurred");
    }

    private ResponseEntity<Map<String, Object>> buildErrorResponse(HttpStatus status, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", Instant.now());
        response.put("status", status.value());
        response.put("error", status.getReasonPhrase());
        response.put("message", message);

        return ResponseEntity.status(status).body(response);
    }
}
