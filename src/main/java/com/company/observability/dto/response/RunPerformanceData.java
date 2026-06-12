package com.company.observability.dto.response;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Raw domain data for run-level performance analysis.
 * All timestamps are UTC Instants — no CET conversion, no formatting.
 */
public record RunPerformanceData(
        String calculatorId,
        String calculatorName,
        String frequency,
        int periodDays,
        long meanDurationMs,
        int totalRuns,      // terminal/evaluated runs only
        int runningRuns,    // currently running runs
        int slaMetCount,
        int lateCount,
        int veryLateCount,
        List<RunDataPoint> runs,
        Instant estimatedStartTime,
        Instant slaTime
) implements Serializable {

    private static final long serialVersionUID = 1L;

    public record RunDataPoint(
            String runId,
            LocalDate reportingDate,
            Instant startTime,
            Instant endTime,
            Long durationMs,
            String status,
            String slaBand,
            String slaStatus,
            List<String> subRunIds,   // non-null only when this point represents a collapsed split group
            Instant estimatedStartTime,
            Instant slaTime,
            String runNumber,
            Long expectedDurationMs
    ) implements Serializable {
        private static final long serialVersionUID = 1L;
    }
}
