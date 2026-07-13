package com.company.observability.exception;

/**
 * A genuinely-unexpected repository failure that is not a {@link org.springframework.dao.DataAccessException}
 * (which is classified on its own by {@code GlobalExceptionHandler}). Maps to HTTP 500 — typed and
 * distinguishable in logs/metrics from framework-level 500s, without leaking internals to callers.
 */
public class PersistenceFailureException extends RuntimeException {
    public PersistenceFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
