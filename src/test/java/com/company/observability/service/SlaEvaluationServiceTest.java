package com.company.observability.service;

import com.company.observability.config.SlaProperties;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.domain.enums.SlaBand;
import com.company.observability.domain.SlaEvaluationResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlaEvaluationServiceTest {

    // Defaults: lateBand=15m, veryLateBand=30m → band gap = 15m.
    private final SlaProperties props = new SlaProperties();
    private final SlaEvaluationService service = new SlaEvaluationService(new SimpleMeterRegistry(), props);

    private static final Instant START = Instant.parse("2026-02-22T04:00:00Z");
    // slaTime is the LATE edge → lateEdge duration = 60 min; veryLateEdge = 75 min.
    private static final Instant SLA = START.plusSeconds(60 * 60);

    private CalculatorRun run(RunStatus status, Long durationMs) {
        return CalculatorRun.builder()
                .status(status)
                .startTime(START)
                .slaTime(SLA)
                .durationMs(durationMs)
                .build();
    }

    @Test
    void onTime_whenDurationWithinLateEdge_noBreach() {
        SlaEvaluationResult result = service.evaluateSla(run(RunStatus.SUCCESS, 50L * 60 * 1000));
        assertFalse(result.isBreached());
        assertNull(result.getReason());
        assertEquals(SlaBand.ON_TIME, result.getBand());
    }

    @Test
    void onTime_atExactLateEdgeBoundary_noBreach() {
        // duration == lateEdge (60 min) → still ON_TIME
        SlaEvaluationResult result = service.evaluateSla(run(RunStatus.SUCCESS, 60L * 60 * 1000));
        assertFalse(result.isBreached());
        assertEquals(SlaBand.ON_TIME, result.getBand());
    }

    @Test
    void late_whenBetweenLateAndVeryLateEdge_lateBand() {
        SlaEvaluationResult result = service.evaluateSla(run(RunStatus.SUCCESS, 70L * 60 * 1000));
        assertTrue(result.isBreached());
        assertEquals(SlaBand.LATE, result.getBand());
        assertTrue(result.getReason().contains("LATE band"));
    }

    @Test
    void late_atExactVeryLateEdgeBoundary_lateBand() {
        // duration == veryLateEdge (75 min) → still LATE
        SlaEvaluationResult result = service.evaluateSla(run(RunStatus.SUCCESS, 75L * 60 * 1000));
        assertTrue(result.isBreached());
        assertEquals(SlaBand.LATE, result.getBand());
    }

    @Test
    void veryLate_whenBeyondVeryLateEdge_veryLateBand() {
        SlaEvaluationResult result = service.evaluateSla(run(RunStatus.SUCCESS, 80L * 60 * 1000));
        assertTrue(result.isBreached());
        assertEquals(SlaBand.VERY_LATE, result.getBand());
        assertTrue(result.getReason().contains("VERY_LATE band"));
    }

    @Test
    void failedRun_shortDuration_treatedAsOnTimeTiming() {
        // FAILED/TIMEOUT are orthogonal to timing — SlaEvaluationService is timing-only.
        // A FAILED run with 1ms duration is ON_TIME from a timing perspective.
        // RunIngestionService independently checks status for failure breach.
        SlaEvaluationResult result = service.evaluateSla(run(RunStatus.FAILED, 1L));
        assertFalse(result.isBreached());
        assertEquals(SlaBand.ON_TIME, result.getBand());
    }

    @Test
    void timeoutRun_longDuration_gradedByTimingBand() {
        // TIMEOUT with a very-late duration → VERY_LATE timing band.
        SlaEvaluationResult result = service.evaluateSla(run(RunStatus.TIMEOUT, 80L * 60 * 1000));
        assertTrue(result.isBreached());
        assertEquals(SlaBand.VERY_LATE, result.getBand());
    }

    @Test
    void ungraded_whenNoDeadlineResolved_nullBand() {
        CalculatorRun run = CalculatorRun.builder()
                .status(RunStatus.SUCCESS)
                .startTime(START)
                .slaTime(null)            // no derived deadline
                .durationMs(999L * 60 * 1000)
                .build();
        SlaEvaluationResult result = service.evaluateSla(run);
        assertFalse(result.isBreached());
        assertNull(result.getBand());
    }
}
