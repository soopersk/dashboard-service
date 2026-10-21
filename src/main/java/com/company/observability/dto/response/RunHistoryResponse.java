package com.company.observability.dto.response;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.RunStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Run history for one calculator, newest first. Only the on-demand attributes Airflow injects
 * ({@code odRunId}, {@code consRunVersion}, {@code calculatorRunVersion}) are exposed from
 * {@code additional_attributes}; every other key (incl. internal ingestion metadata) stays private.
 */
public record RunHistoryResponse(String calculatorName, List<RunEntry> runs) {

    /** True when a run may still change: any RUNNING entry, or no runs yet (one may start any second). */
    public boolean anyLive() {
        return runs.isEmpty() || runs.stream().anyMatch(r -> RunStatus.RUNNING.name().equals(r.status()));
    }

    public record RunEntry(
            String runId,
            String calculatorId,
            String frequency,
            LocalDate reportingDate,
            String runNumber,
            String region,
            String runType,
            String status,
            Instant startTime,
            Instant endTime,
            Long durationMs,
            Long expectedDurationMs,
            Instant slaTime,
            String slaBand,
            boolean slaBreached,
            String odRunId,
            String consRunVersion,
            String calculatorRunVersion
    ) {
        public static RunEntry from(CalculatorRun run) {
            Map<String, Object> attrs = run.getAdditionalAttributes() != null ? run.getAdditionalAttributes() : Map.of();
            return new RunEntry(
                    run.getRunId(),
                    run.getCalculatorId(),
                    run.getFrequency().name(),
                    run.getReportingDate(),
                    run.getRunNumber(),
                    run.getRegion(),
                    run.getRunType(),
                    run.getStatus().name(),
                    run.getStartTime(),
                    run.getEndTime(),
                    run.getDurationMs(),
                    run.getExpectedDurationMs(),
                    run.getSlaTime(),
                    run.getSlaBand() != null ? run.getSlaBand().name() : null,
                    run.isSlaBreached(),
                    attr(attrs, "odRunId"),
                    attr(attrs, "consRunVersion"),
                    attr(attrs, "calculatorRunVersion"));
        }

        /** Numeric values become strings ("3"); an absent key or JSON null becomes null. */
        private static String attr(Map<String, Object> attrs, String key) {
            return Objects.toString(attrs.get(key), null);
        }
    }
}
