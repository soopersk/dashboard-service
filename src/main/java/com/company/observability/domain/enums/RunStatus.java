package com.company.observability.domain.enums;

public enum RunStatus {
    RUNNING("Run is currently in progress"),
    SUCCESS("Run completed successfully"),
    FAILED("Run failed with errors"),
    TIMEOUT("Run exceeded timeout limit"),
    CANCELLED("Run was cancelled");

    private final String description;

    RunStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public boolean isTerminal() {
        return this != RUNNING;
    }

    public boolean isSuccessful() {
        return this == SUCCESS;
    }

    public static RunStatus fromString(String status) {
        if (status == null) {
            return RUNNING;
        }
        try {
            return RunStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            return RUNNING;
        }
    }
}
