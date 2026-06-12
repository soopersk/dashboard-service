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
        String slaBand,
        String slaBreachReason
) {}
