package com.company.observability.domain.enums;

public enum AlertStatus {
    PENDING("Alert is pending to be sent"),
    SENT("Alert has been sent successfully"),
    FAILED("Alert sending failed"),
    RETRYING("Retrying to send alert");

    private final String description;

    AlertStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public boolean isFinal() {
        return this == SENT || this == FAILED;
    }

    public static AlertStatus fromString(String status) {
        if (status == null) {
            return PENDING;
        }
        try {
            return AlertStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            return PENDING;
        }
    }
}