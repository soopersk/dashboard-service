package com.company.observability.dto.response;

import java.time.Instant;

public record RunResponse(
        String runId,
        String calculatorId,
        String calculatorName,
        String status,
        Instant startTime,
        Instant endTime,
        Long durationMs,
        Instant slaDeadline,
        String slaBand,
        String slaBreachReason
) {}
