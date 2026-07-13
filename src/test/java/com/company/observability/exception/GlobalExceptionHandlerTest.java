package com.company.observability.exception;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void handleDataIntegrityViolation_returns400WithMostSpecificCause() {
        DataIntegrityViolationException ex =
                new DataIntegrityViolationException("wrapper", new RuntimeException("value too long for column calculator_name"));

        ResponseEntity<Map<String, Object>> response = handler.handleDataIntegrityViolation(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(400, response.getBody().get("status"));
        assertEquals("value too long for column calculator_name", response.getBody().get("message"));
    }

    @Test
    void handleTransientDataAccess_returns503() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleTransientDataAccess(new CannotAcquireLockException("lock timeout"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(503, response.getBody().get("status"));
        assertEquals("Temporary database contention — retry the request.", response.getBody().get("message"));
    }

    @Test
    void handleDataAccess_returns503() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleDataAccess(new DataAccessResourceFailureException("connection refused"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(503, response.getBody().get("status"));
        assertEquals("Database temporarily unavailable — retry the request.", response.getBody().get("message"));
    }

    @Test
    void handlePersistenceFailure_returns500WithStableMessage() {
        ResponseEntity<Map<String, Object>> response = handler.handlePersistenceFailure(
                new PersistenceFailureException("Failed to save calculator run", new RuntimeException("boom")),
                new MockHttpServletRequest());

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(500, response.getBody().get("status"));
        assertEquals("An unexpected error occurred while saving the run", response.getBody().get("message"));
    }

    @Test
    void buildErrorResponse_includesRequestIdWhenMdcSet() {
        MDC.put("requestId", "req-123");

        ResponseEntity<Map<String, Object>> response =
                handler.handleDomainValidation(new DomainValidationException("bad input"));

        assertEquals("req-123", response.getBody().get("requestId"));
    }

    @Test
    void buildErrorResponse_omitsRequestIdWhenMdcEmpty() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleDomainValidation(new DomainValidationException("bad input"));

        assertTrue(!response.getBody().containsKey("requestId"));
    }

    @Test
    void handleValidationErrors_includesRequestIdAndFieldErrors() {
        MDC.put("requestId", "req-456");

        BindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "startRunRequest");
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);

        ResponseEntity<Map<String, Object>> response = handler.handleValidationErrors(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Validation Failed", response.getBody().get("error"));
        assertEquals("req-456", response.getBody().get("requestId"));
    }
}
