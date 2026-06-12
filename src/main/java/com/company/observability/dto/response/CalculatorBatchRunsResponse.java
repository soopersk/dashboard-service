package com.company.observability.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CalculatorBatchRunsResponse(
        LocalDate reportingDate,
        String frequency,
        String runNumber,
        Instant generatedAt,
        Map<String, CalculatorEntry> calculators
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CalculatorEntry(
            String calculatorName,
            String calculatorId,
            List<RunEntry> runs
    ) {}

    @Builder(toBuilder = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunEntry(
            String calculatorName,
            String runId,
            String region,
            String runType,
            String status,
            String slaStatus,
            Boolean slaBreached,
            Instant startTime,
            Instant endTime,
            Instant estimatedStartTime,
            Instant estimatedEndTime,
            Instant sla,
            Long durationMs,
            Long expectedDurationMs,
            String slaBreachReason,
            boolean isRerun
    ) {}
}
