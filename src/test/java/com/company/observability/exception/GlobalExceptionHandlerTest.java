package com.company.observability.exception;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(new SimpleMeterRegistry());

    @Test
    void handleDomainNotFound_returns404() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleDomainNotFound(new DomainNotFoundException("run not found"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(404, response.getBody().get("status"));
        assertEquals("run not found", response.getBody().get("message"));
    }

    @Test
    void handleDomainAccessDenied_returns403() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleDomainAccessDenied(new DomainAccessDeniedException("forbidden"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(403, response.getBody().get("status"));
        assertEquals("forbidden", response.getBody().get("message"));
    }

    @Test
    void handleDomainValidation_returns400() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleDomainValidation(new DomainValidationException("bad input"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(400, response.getBody().get("status"));
        assertEquals("bad input", response.getBody().get("message"));
    }

    @Test
    void handleConstraintViolation_returns400() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleConstraintViolation(new ConstraintViolationException("constraint failed", null));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(400, response.getBody().get("status"));
        assertEquals("constraint failed", response.getBody().get("message"));
    }

    @Test
    void handleGenericException_returns500WithStableMessage() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleGenericException(new RuntimeException("internal details"), new MockHttpServletRequest());

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(500, response.getBody().get("status"));
        assertEquals("An unexpected error occurred", response.getBody().get("message"));
    }
}
