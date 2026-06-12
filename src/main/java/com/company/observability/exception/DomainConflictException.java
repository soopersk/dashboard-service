package com.company.observability.exception;

/**
 * Request conflicts with the persisted state of the resource (HTTP 409) — e.g. a second
 * {@code /complete} carrying a different terminal status than the one already recorded.
 */
public class DomainConflictException extends RuntimeException {
    public DomainConflictException(String message) {
        super(message);
    }
}
