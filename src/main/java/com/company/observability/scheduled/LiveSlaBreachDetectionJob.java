package com.company.observability.scheduled;

import com.company.observability.cache.SlaMonitoringCache;
import com.company.observability.config.SlaProperties;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.domain.enums.SlaBand;
import com.company.observability.event.SlaBreachedEvent;
import com.company.observability.logging.LifecycleEvent;
import com.company.observability.logging.LifecycleLogger;
import com.company.observability.repository.CalculatorRunRepository;
import com.company.observability.util.MdcContextUtil;
import com.company.observability.domain.SlaEvaluationResult;

import static net.logstash.logback.argument.StructuredArguments.kv;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.company.observability.util.ObservabilityConstants.*;

/**
 * LIVE SLA BREACH DETECTION (every 15 seconds)
 * Checks Redis sorted set for runs past SLA deadline
 * Much faster than database polling, near real-time detection
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(
        value = {"observability.sla.live-detection.enabled", "observability.sla.live-tracking.enabled"},
        havingValue = "true",
        matchIfMissing = true
)
public class LiveSlaBreachDetectionJob {

    private final SlaMonitoringCache slaMonitoringCache;
    private final CalculatorRunRepository runRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final LifecycleLogger lifecycleLogger;
    private final SlaProperties slaProperties;
    private final TransactionTemplate transactionTemplate;
    private final AtomicInteger approachingRunsGauge = new AtomicInteger(0);
    private final AtomicInteger lastBreachesGauge = new AtomicInteger(0);
    private final AtomicLong activeRunsGauge = new AtomicLong(0L);

    @Value("${observability.sla.live-detection.interval-ms:15000}")
    private long detectionIntervalMs;

    @PostConstruct
    void registerGauges() {
        meterRegistry.gauge(SLA_APPROACHING_COUNT, approachingRunsGauge);
        meterRegistry.gauge(SLA_DETECTION_LAST_BREACHES, lastBreachesGauge);
        meterRegistry.gauge(SLA_MONITORING_ACTIVE, activeRunsGauge);
    }

    /**
     * Check for SLA breaches every 15 seconds (near real-time)
     * Uses Redis sorted set for fast lookups
     *
     * <p>Deliberately NOT job-level transactional: each run's breach write + event publication
     * runs in its own short transaction ({@link #markBreachAndPublish}), so a failure late in
     * the batch cannot roll back already-deregistered runs' breach records.
     */
    @Scheduled(
            fixedDelayString = "${observability.sla.live-detection.interval-ms:15000}",
            initialDelayString = "${observability.sla.live-detection.initial-delay-ms:10000}"
    )
    public void detectLiveSlaBreaches() {
        Map<String, String> snapshot = MdcContextUtil.setJobContext("live-sla-detection");
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            List<Map<String, Object>> breachedRuns = slaMonitoringCache.getBreachedRuns();

            if (breachedRuns.isEmpty()) {
                log.debug("event=sla.live_detection outcome=success count=0");
                recordMetrics(sample, 0);
                return;
            }

            log.warn("event=sla.live_detection outcome=success count={}", breachedRuns.size());

            int processedCount = 0;

            for (Map<String, Object> runInfo : breachedRuns) {
                String runId = (String) runInfo.get("runId");
                String tenantId = (String) runInfo.get("tenantId");
                String reportingDateStr = (String) runInfo.get("reportingDate");
                LocalDate reportingDate = null;
                if (reportingDateStr != null) {
                    reportingDate = LocalDate.parse(reportingDateStr);
                }

                Map<String, String> runSnapshot = MdcContextUtil.setCalculatorContext(
                        (String) runInfo.get("calculatorId"), runId);

                try {
                    Optional<CalculatorRun> runOpt = reportingDate != null
                            ? runRepository.findById(runId, reportingDate)
                            : runRepository.findById(runId);

                    if (runOpt.isEmpty()) {
                        log.warn("event=sla.live_detection.run_lookup outcome=failure reason=not_found runId={}", runId);
                        slaMonitoringCache.deregisterFromSlaMonitoring(runId, tenantId, reportingDate);
                        continue;
                    }

                    CalculatorRun run = runOpt.get();

                    if (run.getStatus() != RunStatus.RUNNING) {
                        log.debug("event=sla.live_detection.run_check outcome=rejected reason=already_completed runId={}", runId);
                        slaMonitoringCache.deregisterFromSlaMonitoring(runId, tenantId, reportingDate);
                        continue;
                    }

                    if (run.getSlaBand() != null) {
                        log.debug("event=sla.live_detection.run_check outcome=rejected reason=already_breached runId={}", runId);
                        slaMonitoringCache.deregisterFromSlaMonitoring(runId, tenantId, reportingDate);
                        continue;
                    }

                    if (markBreachAndPublish(run, "redis")) {
                        // Deregister only after the breach transaction committed — a failed
                        // write must keep the run monitored for the next cycle.
                        slaMonitoringCache.deregisterFromSlaMonitoring(runId, tenantId, reportingDate);
                        processedCount++;
                    }

                } catch (Exception e) {
                    log.error("event=sla.live_detection.run_process outcome=failure runId={}", runId, e);
                } finally {
                    MdcContextUtil.restoreContext(runSnapshot);
                }
            }

            log.info("event=sla.live_detection.completed outcome=success processed={} total={}",
                    processedCount, breachedRuns.size());

            recordMetrics(sample, processedCount);

        } catch (Exception e) {
            log.error("event=sla.live_detection outcome=failure", e);
            meterRegistry.counter(SLA_DETECTION_FAILURE).increment();
        } finally {
            MdcContextUtil.restoreContext(snapshot);
        }
    }

    /**
     * DB fallback sweep: marks overdue RUNNING runs that Redis-based detection missed
     * (registration failed at start, Redis outage, lost entries). Redis stays the fast path;
     * this is the safety net that guarantees no breach is silently lost.
     */
    @Scheduled(
            fixedDelayString = "${observability.sla.live-detection.db-sweep-interval-ms:120000}",
            initialDelayString = "${observability.sla.live-detection.db-sweep-initial-delay-ms:60000}"
    )
    public void sweepOverdueRunsFromDb() {
        Map<String, String> snapshot = MdcContextUtil.setJobContext("sla-db-sweep");
        try {
            List<CalculatorRun> overdueRuns = runRepository.findOverdueRunningRuns();

            if (overdueRuns.isEmpty()) {
                log.debug("event=sla.db_sweep outcome=success count=0");
                return;
            }

            log.warn("event=sla.db_sweep outcome=success count={}", overdueRuns.size());

            int processedCount = 0;
            for (CalculatorRun run : overdueRuns) {
                Map<String, String> runSnapshot = MdcContextUtil.setCalculatorContext(
                        run.getCalculatorId(), run.getRunId());
                try {
                    if (markBreachAndPublish(run, "db_sweep")) {
                        // Drop any stale Redis entry so the fast path stops re-examining it.
                        slaMonitoringCache.deregisterFromSlaMonitoring(
                                run.getRunId(), run.getTenantId(), run.getReportingDate());
                        processedCount++;
                    }
                } catch (Exception e) {
                    log.error("event=sla.db_sweep.run_process outcome=failure runId={}", run.getRunId(), e);
                } finally {
                    MdcContextUtil.restoreContext(runSnapshot);
                }
            }

            log.info("event=sla.db_sweep.completed outcome=success processed={} total={}",
                    processedCount, overdueRuns.size());

        } catch (Exception e) {
            log.error("event=sla.db_sweep outcome=failure", e);
            meterRegistry.counter(SLA_DETECTION_FAILURE).increment();
        } finally {
            MdcContextUtil.restoreContext(snapshot);
        }
    }

    /**
     * Marks the run's SLA breach and publishes {@link SlaBreachedEvent} in one short
     * per-run transaction, so {@code @TransactionalEventListener(AFTER_COMMIT)} listeners
     * fire exactly when the breach record is durable. Shared by the Redis path and the DB sweep.
     *
     * @return true when this call won the idempotency guard ({@code sla_band IS NULL AND
     *         status = 'RUNNING'}) and recorded the breach; false when another writer
     *         (completion, concurrent detection) got there first.
     */
    private boolean markBreachAndPublish(CalculatorRun run, String source) {
        String breachReason = buildBreachReason(run);
        SlaBand band = determineBand(run);

        Boolean marked = transactionTemplate.execute(tx -> {
            int updated = runRepository.markSlaBreach(
                    run.getRunId(), run.getReportingDate(), band, breachReason);
            if (updated == 0) {
                return false;
            }
            run.setSlaBand(band);
            run.setSlaBreached(true);
            run.setSlaBreachReason(breachReason);
            eventPublisher.publishEvent(new SlaBreachedEvent(run, new SlaEvaluationResult(band, breachReason)));
            return true;
        });

        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }

        lifecycleLogger.emit(LifecycleEvent.SLA_LIVE_BREACH,
                kv("runId", run.getRunId()), kv("reason", breachReason),
                kv("band", band), kv("source", source));
        meterRegistry.counter(SLA_BREACH_LIVE_DETECTED,
                "band", band.name(),
                "source", source
        ).increment();
        return true;
    }

    /**
     * Also check for runs approaching SLA (early warning)
     */
    @Scheduled(
            fixedDelayString = "${observability.sla.early-warning.interval-ms:60000}",
            initialDelayString = "30000"
    )
    public void detectApproachingSla() {
        Map<String, String> snapshot = MdcContextUtil.setJobContext("sla-early-warning");

        try {
            List<Map<String, Object>> approachingRuns =
                    slaMonitoringCache.getApproachingSlaRuns(10);

            if (!approachingRuns.isEmpty()) {
                log.info("event=sla.early_warning outcome=success count={}", approachingRuns.size());

                for (Map<String, Object> runInfo : approachingRuns) {
                    log.warn("event=sla.early_warning.run runId={} calculator={} minutesUntilSla={}",
                            runInfo.get("runId"),
                            runInfo.get("calculatorName"),
                            calculateMinutesUntilSla(runInfo));
                }
            }
            approachingRunsGauge.set(approachingRuns.size());

        } catch (Exception e) {
            log.error("event=sla.early_warning outcome=failure", e);
        } finally {
            MdcContextUtil.restoreContext(snapshot);
        }
    }

    private String buildBreachReason(CalculatorRun run) {
        long delayMinutes = Duration.between(run.getSlaTime(), Instant.now()).toMinutes();
        return String.format(
                "Still running %d minutes past SLA deadline (detected live via Redis monitoring)",
                delayMinutes
        );
    }

    /**
     * Grade a still-running breach by how far past the frozen LATE-edge deadline it is.
     * slaTime is the LATE-band edge; within one band-gap past it the run is in the LATE
     * band (MEDIUM), beyond that it is VERY_LATE (HIGH).
     */
    private SlaBand determineBand(CalculatorRun run) {
        long delayMs = Duration.between(run.getSlaTime(), Instant.now()).toMillis();
        return delayMs <= slaProperties.bandGapMs() ? SlaBand.LATE : SlaBand.VERY_LATE;
    }

    private long calculateMinutesUntilSla(Map<String, Object> runInfo) {
        long slaTime = ((Number) runInfo.get("slaTime")).longValue();
        long now = Instant.now().toEpochMilli();
        return (slaTime - now) / 60000;
    }

    private void recordMetrics(Timer.Sample sample, int breachedCount) {
        meterRegistry.counter(SLA_DETECTION_EXECUTION).increment();
        sample.stop(meterRegistry.timer(SLA_DETECTION_DURATION));

        lastBreachesGauge.set(breachedCount);

        long monitoredCount = slaMonitoringCache.getMonitoredRunCount();
        activeRunsGauge.set(monitoredCount);
    }
}

