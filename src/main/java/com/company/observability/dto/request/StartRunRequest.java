package com.company.observability.dto.request;

import com.company.observability.domain.enums.Frequency;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * Request to start a calculator run with reporting_date
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StartRunRequest {
    @NotBlank(message = "Run ID is required")
    private String runId;

    @NotBlank(message = "Calculator ID is required")
    private String calculatorId;

    @NotBlank(message = "Calculator name is required")
    private String calculatorName;

    @NotNull(message = "Frequency is required (DAILY or MONTHLY)")
    private Frequency frequency;

    @NotNull(message = "Reporting date is required")
    @Schema(example = "2026-02-06")
    private LocalDate reportingDate;

    @NotNull(message = "Start time is required")
    @Schema(example = "2026-02-06T23:22:32Z")
    private Instant startTime;

    @Schema(description = "Self-describing SLA spec (UTC). One of three forms:\n" +
            "• \"T+N@HH:mm\" (N>=1) — day offset + cutoff. DAILY only: deadline = nextBusinessDay(reportingDate, N) " +
            "at HH:mm (weekends skipped). MONTHLY rejects this form.\n" +
            "• \"HH:mm\" (bare clock) — DAILY: offset falls back to run_number (run 1→T+1, run 2→T+2, else T+2). " +
            "MONTHLY: anchored to the startTime date, rolled +1 day if at/before startTime (overnight).\n" +
            "• ISO-8601 duration (e.g. \"PT2H30M\") — deadline = startTime + buffered + lateBand.\n" +
            "Blank/null → derived from expectedDurationMs, else profile average, else ungraded. " +
            "The persisted/response slaTime is always the derived absolute deadline instant (UTC).",
            example = "T+1@09:30")
    private String slaTime;

    // Optional fields
    private Long expectedDurationMs;

    // Optional. If Airflow supplies these, they win; otherwise they are derived from the
    // calculator's cached historical profile, then from start + expectedDurationMs.
    private Instant estimatedStartTime;
    private Instant estimatedEndTime;

    // Optional — promoted from run_parameters for query efficiency.
    // Top-level fields take precedence; fall back to runParameters map for backward compat.
    private String runNumber;
    private String runType;
    private String region;

    // Optional — set the same value on every physical split that belongs to one logical run.
    // Null means this is a standalone run (not a split).
    private String correlationId;

    private Map<String, Object> runParameters;
    private Map<String, Object> additionalAttributes;
}
