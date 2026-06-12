package com.company.observability.alert;

public class AlertDeliveryException extends RuntimeException {

    public AlertDeliveryException(String message) {
        super(message);
    }

    public AlertDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
