package com.company.observability.domain.enums;

public enum CompletionStatus {
    SUCCESS, FAILED, TIMEOUT, CANCELLED;

    public RunStatus toRunStatus() {
        return RunStatus.valueOf(this.name());
    }
}
