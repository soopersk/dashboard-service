package com.company.observability.domain.enums;

public enum BreachType {
    DURATION_EXCEEDED("Run duration exceeded SLA target"),
    TIME_EXCEEDED("Run end time exceeded SLA target"),
    FAILED("Run failed"),
    TIMEOUT("Run timed out"),
    UNKNOWN("Unknown breach type");

    private final String description;

    BreachType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public static BreachType fromString(String type) {
        if (type == null) {
            return UNKNOWN;
        }
        try {
            return BreachType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}