package com.company.observability.util;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.DailyAggregate;
import com.company.observability.domain.SlaBreachEvent;
import com.company.observability.domain.enums.AlertStatus;
import com.company.observability.domain.enums.BreachType;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.domain.enums.SlaBand;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Centralized test fixture factory.
 *
 * <p>All builders return fully populated objects with sensible defaults.
 * Override specific fields using the Lombok builder chain on the returned
 * {@link CalculatorRun.CalculatorRunBuilder}, or call the convenience
 * method variants that accept the most commonly varied parameters.
 *
 * <p>Usage:
 * <pre>{@code
 * CalculatorRun run = TestFixtures.aRunningRun();
 * CalculatorRun custom = TestFixtures.aRunningRun("my-run", "calc-99", "tenant-z");
 * }</pre>
 */
public final class TestFixtures {

    // Shared constants used across tests
    public static final String DEFAULT_RUN_ID       = "run-1";
    public static final String DEFAULT_CALC_ID      = "calc-1";
    public static final String DEFAULT_CALC_NAME    = "Calculator One";
    public static final String DEFAULT_TENANT_ID    = "tenant-1";
    public static final LocalDate DEFAULT_DATE      = LocalDate.of(2026, 4, 10);
    public static final Instant  DEFAULT_START      = Instant.parse("2026-04-10T05:00:00Z");
    public static final Instant  DEFAULT_SLA_TIME   = Instant.parse("2026-04-10T05:15:00Z");

    private TestFixtures() { }

    // ---------------------------------------------------------------
    // CalculatorRun factories
    // ---------------------------------------------------------------

    /** A RUNNING run with all required fields populated. */
    public static CalculatorRun aRunningRun() {
        return baseRunBuilder()
                .status(RunStatus.RUNNING)
                .slaBand(null)
                .build();
    }

    /** A RUNNING run with caller-specified identity. */
    public static CalculatorRun aRunningRun(String runId, String calculatorId, String tenantId) {
        return baseRunBuilder()
                .runId(runId)
                .calculatorId(calculatorId)
                .tenantId(tenantId)
                .status(RunStatus.RUNNING)
                .slaBand(null)
                .build();
    }

    /** A successfully completed run (no breach). */
    public static CalculatorRun aCompletedRun() {
        return baseRunBuilder()
                .status(RunStatus.SUCCESS)
                .endTime(DEFAULT_START.plusSeconds(600))
                .durationMs(600_000L)
                .slaBand(null)
                .build();
    }

    /** A completed run with caller-specified identity. */
    public static CalculatorRun aCompletedRun(String runId, String calculatorId, String tenantId) {
        return baseRunBuilder()
                .runId(runId)
                .calculatorId(calculatorId)
                .tenantId(tenantId)
                .status(RunStatus.SUCCESS)
                .endTime(DEFAULT_START.plusSeconds(600))
                .durationMs(600_000L)
                .slaBand(null)
                .build();
    }

    /** A run that has breached its SLA. Status remains RUNNING to represent live detection. */
    public static CalculatorRun aBreachedRun() {
        return baseRunBuilder()
                .status(RunStatus.RUNNING)
                .slaTime(DEFAULT_START.minusSeconds(300))  // SLA was 5 min ago
                .slaBand(SlaBand.LATE)
                .slaBreachReason("Still running 5 minutes past SLA deadline")
                .build();
    }

    /** A MONTHLY run with an end-of-month reporting date. */
    public static CalculatorRun aMonthlyRun() {
        LocalDate eom = LocalDate.of(2026, 3, 31);
        return baseRunBuilder()
                .frequency(Frequency.MONTHLY)
                .reportingDate(eom)
                .status(RunStatus.RUNNING)
                .slaBand(null)
                .build();
    }

    // ---------------------------------------------------------------
    // SlaBreachEvent factories
    // ---------------------------------------------------------------

    public static SlaBreachEvent anSlaBreachEvent() {
        return SlaBreachEvent.builder()
                .runId(DEFAULT_RUN_ID)
                .calculatorId(DEFAULT_CALC_ID)
                .calculatorName(DEFAULT_CALC_NAME)
                .tenantId(DEFAULT_TENANT_ID)
                .breachType(BreachType.TIME_EXCEEDED)
                .expectedValue(300_000L)
                .actualValue(600_000L)
                .alerted(false)
                .alertStatus(AlertStatus.PENDING)
                .retryCount(0)
                .createdAt(Instant.now())
                .build();
    }

    // ---------------------------------------------------------------
    // DailyAggregate factories
    // ---------------------------------------------------------------

    public static DailyAggregate aDailyAggregate() {
        return new DailyAggregate(
                DEFAULT_CALC_ID,
                DEFAULT_DATE,
                5,           // totalRuns
                4,           // successRuns
                1,           // slaBreaches
                600_000L,    // sumDurationMs  (avg = 120_000ms per run)
                1_500L,      // sumStartMinCet (avg = 300 min CET per run)
                1_550L,      // sumEndMinCet   (avg = 310 min CET per run)
                Instant.now()
        );
    }

    // ---------------------------------------------------------------
    // Internal builder
    // ---------------------------------------------------------------

    private static CalculatorRun.CalculatorRunBuilder baseRunBuilder() {
        return CalculatorRun.builder()
                .runId(DEFAULT_RUN_ID)
                .calculatorId(DEFAULT_CALC_ID)
                .calculatorName(DEFAULT_CALC_NAME)
                .tenantId(DEFAULT_TENANT_ID)
                .frequency(Frequency.DAILY)
                .reportingDate(DEFAULT_DATE)
                .startTime(DEFAULT_START)
                .slaTime(DEFAULT_SLA_TIME)
                .expectedDurationMs(300_000L)
                .createdAt(DEFAULT_START)
                .updatedAt(DEFAULT_START);
    }
}
