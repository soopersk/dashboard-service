package com.company.observability.exception;

public class DomainAccessDeniedException extends RuntimeException {
    public DomainAccessDeniedException(String message) {
        super(message);
    }
}
