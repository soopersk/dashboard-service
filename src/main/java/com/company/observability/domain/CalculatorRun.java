package com.company.observability.domain;

import com.company.observability.domain.enums.Frequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.domain.enums.SlaBand;
import lombok.*;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * Calculator Run domain model with reporting_date for partition key
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = {"runId", "reportingDate"})
@ToString
public class CalculatorRun implements Serializable {
    private static final long serialVersionUID = 1L;

    // Primary key
    private String runId;

    // Calculator metadata
    private String calculatorId;
    private String calculatorName;
    private String tenantId;

    @Builder.Default
    private Frequency frequency = Frequency.DAILY;

    // Partition key - critical for query performance
    private LocalDate reportingDate;

    // Timing information
    private Instant startTime;
    private Instant endTime;
    private Long durationMs;

    // Default to RUNNING
    @Builder.Default
    private RunStatus status = RunStatus.RUNNING;

    // SLA tracking
    private Instant slaTime;
    private Long expectedDurationMs;
    private Instant estimatedStartTime;
    private Instant estimatedEndTime;

    private SlaBand slaBand;
    private boolean slaBreached;
    private String slaBreachReason;

    // Promoted from run_parameters JSONB
    private String runNumber;
    private String runType;
    private String region;

    // Transient — set by CalculatorStateService, not persisted
    private boolean isRerun;

    // Split-run correlation: Airflow sets the same value on every physical split of one logical run.
    // NULL = standalone run (not part of a split group).
    private String correlationId;

    // Metadata
    private Map<String, Object> runParameters;
    private Map<String, Object> additionalAttributes;
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * Helper to determine if this is a DAILY run
     */
    public boolean isDaily() {
        return frequency == Frequency.DAILY;
    }

    /**
     * Helper to determine if this is a MONTHLY run
     */
    public boolean isMonthly() {
        return frequency == Frequency.MONTHLY;
    }

    /**
     * Helper to check if this is an end-of-month reporting date
     */
    public boolean isEndOfMonth() {
        if (reportingDate == null) return false;
        LocalDate nextDay = reportingDate.plusDays(1);
        return nextDay.getMonth() != reportingDate.getMonth();
    }
}
