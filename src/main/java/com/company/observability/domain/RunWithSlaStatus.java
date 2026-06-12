package com.company.observability.domain;

import com.company.observability.domain.enums.Frequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.domain.enums.SlaBand;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Lightweight projection for performance card: sourced from calculator_runs only.
 * slaBand is read directly from cr.sla_band — no JOIN to sla_breach_events.
 */
public record RunWithSlaStatus(
        String runId,
        String calculatorId,
        String calculatorName,
        LocalDate reportingDate,
        Instant startTime,
        Instant endTime,
        Long durationMs,
        Instant slaTime,
        Instant estimatedStartTime,
        Frequency frequency,
        RunStatus status,
        SlaBand slaBand,
        String slaBreachReason,
        String correlationId,
        String runNumber,
        Long expectedDurationMs
) {}
