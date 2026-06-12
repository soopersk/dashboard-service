package com.company.observability.service;

import com.company.observability.config.SlaProperties;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.SlaEvaluationResult;
import com.company.observability.domain.enums.SlaBand;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

import static com.company.observability.util.ObservabilityConstants.SLA_EVALUATION_DURATION;

/**
 * Grades a completed run's timing against its frozen, duration-derived SLA deadline.
 *
 * <p>Returns a {@link SlaBand} (timing-only concern). FAILED/TIMEOUT runs are NOT
 * short-circuited here — failure is a separate dimension owned by {@code RunStatus}.
 * The caller ({@code RunIngestionService}) checks status independently and publishes
 * a {@code SlaBreachedEvent} for both failure and timing breach cases.
 *
 * <pre>
 *   actual <= lateEdgeMs       → ON_TIME   (no breach)
 *   actual <= veryLateEdgeMs   → LATE      (breach)
 *   actual >  veryLateEdgeMs   → VERY_LATE (breach)
 *   null slaTime / startTime / durationMs → null band (ungraded)
 * </pre>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlaEvaluationService {

    private final MeterRegistry meterRegistry;
    private final SlaProperties props;

    public SlaEvaluationResult evaluateSla(CalculatorRun run) {
        Timer.Sample sample = Timer.start(meterRegistry);
        SlaEvaluationResult result = classify(run);
        sample.stop(Timer.builder(SLA_EVALUATION_DURATION)
                .tag("result", result.isBreached() ? "breached" : "clear")
                .register(meterRegistry));
        return result;
    }

    private SlaEvaluationResult classify(CalculatorRun run) {
        // Ungraded: no derived deadline (no history / no estimate) → null band.
        if (run.getSlaTime() == null || run.getStartTime() == null || run.getDurationMs() == null) {
            return new SlaEvaluationResult(null, null);
        }

        long lateEdgeMs = Duration.between(run.getStartTime(), run.getSlaTime()).toMillis();
        long veryLateEdgeMs = lateEdgeMs + props.bandGapMs();
        long actualMs = run.getDurationMs();

        if (actualMs <= lateEdgeMs) {
            return new SlaEvaluationResult(SlaBand.ON_TIME, null);
        }

        long minutesLate = (actualMs - lateEdgeMs) / 60_000L;
        if (actualMs <= veryLateEdgeMs) {
            return new SlaEvaluationResult(SlaBand.LATE,
                    String.format("Finished %d minutes late (LATE band)", minutesLate));
        }
        return new SlaEvaluationResult(SlaBand.VERY_LATE,
                String.format("Finished %d minutes late (VERY_LATE band)", minutesLate));
    }
}
