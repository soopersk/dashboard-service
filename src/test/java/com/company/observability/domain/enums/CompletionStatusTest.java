package com.company.observability.domain.enums;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompletionStatusTest {

    @Test
    void validValues() {
        assertEquals(CompletionStatus.SUCCESS, CompletionStatus.valueOf("SUCCESS"));
        assertEquals(CompletionStatus.FAILED, CompletionStatus.valueOf("FAILED"));
        assertEquals(CompletionStatus.TIMEOUT, CompletionStatus.valueOf("TIMEOUT"));
        assertEquals(CompletionStatus.CANCELLED, CompletionStatus.valueOf("CANCELLED"));
    }

    @Test
    void exactlyFourValues() {
        assertEquals(4, CompletionStatus.values().length, "RUNNING must not be a CompletionStatus");
    }

    @Test
    void toRunStatus() {
        assertEquals(RunStatus.SUCCESS, CompletionStatus.SUCCESS.toRunStatus());
        assertEquals(RunStatus.FAILED, CompletionStatus.FAILED.toRunStatus());
        assertEquals(RunStatus.TIMEOUT, CompletionStatus.TIMEOUT.toRunStatus());
        assertEquals(RunStatus.CANCELLED, CompletionStatus.CANCELLED.toRunStatus());
    }
}
