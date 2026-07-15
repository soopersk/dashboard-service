package com.company.observability.service;

import com.company.observability.cache.SlaMonitoringCache;
import com.company.observability.config.SlaProperties;
import com.company.observability.domain.CalculatorProfile;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.SlaEvaluationResult;
import com.company.observability.domain.enums.Dimension;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.domain.enums.CompletionStatus;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.dto.request.*;
import com.company.observability.exception.DomainAccessDeniedException;
import com.company.observability.exception.DomainConflictException;
import com.company.observability.exception.DomainNotFoundException;
import com.company.observability.exception.DomainValidationException;
import com.company.observability.event.*;
import com.company.observability.logging.LifecycleEvent;
import com.company.observability.logging.LifecycleLogger;
import com.company.observability.repository.*;
import com.company.observability.util.*;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.company.observability.util.ObservabilityConstants.*;
import static net.logstash.logback.argument.StructuredArguments.kv;

@Service
@Slf4j
@RequiredArgsConstructor
public class RunIngestionService {

    private final CalculatorRunRepository runRepository;
    private final SlaEvaluationService slaEvaluationService;
    private final SlaBaselineResolver slaBaselineResolver;
    private final CalculatorProfileService calculatorProfileService;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final SlaMonitoringCache slaMonitoringCache;
    private final LifecycleLogger lifecycleLogger;
    private final SlaProperties slaProperties;
    private final CalculatorNameResolver calculatorNameResolver;

    @Value("${observability.sla.live-tracking.enabled:true}")
    private boolean liveTrackingEnabled;

    /** Result of {@link #startRun}: the persisted (or pre-existing) run and whether this call created it. */
    public record StartRunOutcome(CalculatorRun run, boolean created) {}

    @Transactional
    public StartRunOutcome startRun(StartRunRequest request, String tenantId) {
        var prev = MdcContextUtil.setCalculatorContext(request.getCalculatorId(), request.getRunId());
        try {
            return doStartRun(request, tenantId);
        } finally {
            MdcContextUtil.restoreContext(prev);
        }
    }

    private StartRunOutcome doStartRun(StartRunRequest request, String tenantId) {
        // Check for existing run using partition key
        Optional<CalculatorRun> existing = runRepository.findById(
                request.getRunId(), request.getReportingDate());

        if (existing.isPresent()) {
            lifecycleLogger.emit(LifecycleEvent.RUN_START_REJECTED, kv("reason", "duplicate"));
            meterRegistry.counter(INGESTION_RUN_DUPLICATE, "phase", "start").increment();
            return new StartRunOutcome(existing.get(), false);
        }

        // Validate reporting_date matches frequency expectations
        validateReportingDate(request);

        Frequency frequency = Objects.requireNonNullElse(
                request.getFrequency(), Frequency.DAILY);

        // Resolve promoted fields before the profile fetch so the profiles can be scoped.
        String runNumber = normalizeRunNumber(
                resolveField(request.getRunNumber(), request.getRunParameters(), "run_number"));
        String runType = resolveField(request.getRunType(), request.getRunParameters(), "run_type");
        String region = resolveField(request.getRegion(), request.getRunParameters(), "region");

        // Archetype dimension guard: only the field matching the calculator's configured archetype
        // is a legitimate dimension source. Anything else populated is a wrong-field mistake, not a
        // second dimension — collapse it to the 'ALL' slice but preserve the original value in
        // additional_attributes (no data loss, no schema change). Applied uniformly to all three
        // archetypes: NONE stashes both region and run_type (byte-for-byte the former NONE-only
        // guard); REGION stashes a stray run_type; RUN_TYPE stashes a stray region.
        Map<String, Object> additionalAttributes = request.getAdditionalAttributes();
        Dimension archetype = calculatorNameResolver.dimensionOf(request.getCalculatorName());
        if (archetype != Dimension.REGION && region != null) {
            additionalAttributes = putStray(additionalAttributes, "region", region);
            region = null;
        }
        if (archetype != Dimension.RUN_TYPE && runType != null) {
            additionalAttributes = putStray(additionalAttributes, "run_type", runType);
            runType = null;
        }
        // A dimensional calculator that arrives with neither field lands in the stray 'ALL' bucket,
        // indistinguishable from legitimate data. Accept the write (never reject over a labeling
        // problem) but make it observable rather than silent.
        if ((archetype == Dimension.REGION || archetype == Dimension.RUN_TYPE) && region == null && runType == null) {
            meterRegistry.counter(INGESTION_DIMENSION_MISSING,
                    "calculator", request.getCalculatorName(), "archetype", archetype.name()).increment();
            log.warn("event=run.start.dimension outcome=missing calculator={} archetype={} runId={}",
                    request.getCalculatorName(), archetype, request.getRunId());
        }

        // Run-number-agnostic guard: a calculator not declared run-number-aware must not carry a
        // run_number — a stray cycle label would create a phantom per-cycle profile. Collapse it to
        // the un-numbered ('ALL') bucket but preserve the original in additional_attributes.
        if (runNumber != null && !calculatorNameResolver.isRunNumberAware(request.getCalculatorName())) {
            additionalAttributes = putStray(additionalAttributes, "run_number", runNumber);
            runNumber = null;
        }

        // Mirrors the aggregate's COALESCE(region, run_type, 'ALL') dimension key.
        String dimension = region != null ? region : runType;

        // Two scoped profiles (Redis-backed, no DB on warm cache; each falls back down the
        // dim → run_number → blended chain for thin history):
        // - SLA baseline uses the run_number-scoped profile so RUN1/RUN2 cycles keep distinct
        //   budgets but the budget stays calculator-level across dimensions.
        // - Estimates use the dimension-scoped profile for accurate per-region/run-type timing.
        CalculatorProfile baselineProfile = calculatorProfileService.getProfile(
                request.getCalculatorName(), frequency, runNumber);
        CalculatorProfile estimateProfile = calculatorProfileService.getProfile(
                request.getCalculatorName(), frequency, runNumber, dimension);

        // Derive SLA baseline + deadline once and reuse for persistence + estimated-end fallback.
        SlaBaselineResolver.SlaResolution slaResolution = slaBaselineResolver.resolve(request, frequency, baselineProfile);
        Instant slaDeadline = slaResolution.deadline();

        Instant estimatedStartTime = resolveEstimatedStart(request, estimateProfile);
        Instant estimatedEndTime = resolveEstimatedEnd(request, estimatedStartTime, estimateProfile, slaResolution);

        CalculatorRun run = CalculatorRun.builder()
                .runId(request.getRunId())
                .calculatorId(request.getCalculatorId())
                .calculatorName(request.getCalculatorName())
                .tenantId(tenantId)
                .frequency(frequency)
                .reportingDate(request.getReportingDate())
                .startTime(request.getStartTime())
                .status(RunStatus.RUNNING)
                .slaTime(slaDeadline)
                .expectedDurationMs(resolveExpectedDuration(request, estimateProfile, slaResolution))
                .estimatedStartTime(estimatedStartTime)
                .estimatedEndTime(estimatedEndTime)
                .runNumber(runNumber)
                .runType(runType)
                .region(region)
                .correlationId(request.getCorrelationId())
                .runParameters(request.getRunParameters())
                .additionalAttributes(additionalAttributes)
                .slaBand(null)
                .slaBreachReason(null)
                .build();

        run = runRepository.upsert(run);

        lifecycleLogger.emit(LifecycleEvent.RUN_START_SUCCESS,
                kv("freq", request.getFrequency()), kv("reportingDate", request.getReportingDate()));

        // Register for live SLA monitoring (DAILY and MONTHLY) whenever a deadline was derived.
        if (liveTrackingEnabled && slaDeadline != null) {
            slaMonitoringCache.registerForSlaMonitoring(run);
        }

        eventPublisher.publishEvent(new RunStartedEvent(run));

        meterRegistry.counter(INGESTION_RUN_STARTED,
                "frequency", run.getFrequency().name()
        ).increment();

        log.info("event=run.start.persist outcome=success slaSpec={} slaDeadline={} liveTracking={}",
                request.getSlaTime(), slaDeadline, liveTrackingEnabled);

        return new StartRunOutcome(run, true);
    }

    @Transactional
    public CalculatorRun completeRun(String runId, CompleteRunRequest request, String tenantId) {
        // FOR UPDATE: serializes against LiveSlaBreachDetectionJob.markSlaBreach so the
        // band/breach state read here is authoritative, not a racing snapshot.
        Optional<CalculatorRun> runOpt = runRepository.findByIdForUpdate(runId, request.getReportingDate());

        if (runOpt.isEmpty()) {
            throw new DomainNotFoundException("Run not found: " + runId + " for reportingDate=" + request.getReportingDate());
        }

        CalculatorRun run = runOpt.get();

        // Advisory tenant check (tenantId is optional and not used for query filtering):
        // enforce only when both sides carry a tenant — a run ingested without a header must
        // not 403 every caller, and a missing header on a tenant-owned run is logged, not blocked.
        if (tenantId != null && run.getTenantId() != null && !tenantId.equals(run.getTenantId())) {
            throw new DomainAccessDeniedException("Run " + runId + " does not belong to tenant " + tenantId);
        }
        if (tenantId == null && run.getTenantId() != null) {
            log.warn("event=run.complete.tenant outcome=accepted_with_warning reason=missing_header runId={} ownerTenant={}",
                    runId, run.getTenantId());
        }

        var prev = MdcContextUtil.setCalculatorContext(run.getCalculatorId(), runId);
        try {
            return doCompleteRun(run, request);
        } finally {
            MdcContextUtil.restoreContext(prev);
        }
    }

    private CalculatorRun doCompleteRun(CalculatorRun run, CompleteRunRequest request) {
        if (run.getStatus() != RunStatus.RUNNING) {
            CompletionStatus requested = request.getStatus() != null
                    ? request.getStatus()
                    : CompletionStatus.SUCCESS;
            if (run.getStatus() != requested.toRunStatus()) {
                // Not an idempotent replay — the caller is trying to change a recorded outcome.
                lifecycleLogger.emit(LifecycleEvent.RUN_COMPLETE_REJECTED, kv("reason", "conflict"));
                throw new DomainConflictException("Run " + run.getRunId() + " already completed with status "
                        + run.getStatus() + "; cannot change it to " + requested.toRunStatus());
            }
            lifecycleLogger.emit(LifecycleEvent.RUN_COMPLETE_REJECTED, kv("reason", "duplicate"));
            meterRegistry.counter(INGESTION_RUN_DUPLICATE, "phase", "complete").increment();
            return run;
        }

        if (request.getEndTime().isBefore(run.getStartTime())) {
            throw new DomainValidationException("End time cannot be before start time");
        }

        // Authoritative under the FOR UPDATE row lock — live detection cannot write concurrently.
        boolean liveBreached = run.getSlaBand() != null && run.getSlaBand().isBreached();
        String previousBreachReason = run.getSlaBreachReason();

        long durationMs = Duration.between(run.getStartTime(), request.getEndTime()).toMillis();

        run.setEndTime(request.getEndTime());
        run.setDurationMs(durationMs);
        CompletionStatus completionStatus = request.getStatus() != null
                ? request.getStatus()
                : CompletionStatus.SUCCESS;
        run.setStatus(completionStatus.toRunStatus());

        // Grade timing (independent of failure status). The on-write grade is deterministic
        // (frozen sla_time + actual endTime) and is the final authority on the timing band:
        // it corrects live false positives (run finished before the deadline but /complete
        // arrived after it) and upgrades stale live bands (live LATE → actual VERY_LATE).
        SlaEvaluationResult slaResult = slaEvaluationService.evaluateSla(run);

        boolean falsePositiveCleared = false;
        if (slaResult.getBand() != null) {
            falsePositiveCleared = liveBreached && !slaResult.getBand().isBreached();
            run.setSlaBand(slaResult.getBand());
            run.setSlaBreachReason(slaResult.getReason());
        } else {
            // Ungraded on-write (no frozen deadline) — keep whatever live detection recorded.
            run.setSlaBreachReason(previousBreachReason);
        }

        // Compute breach flag before upsert so it is persisted to sla_breached
        boolean timingBreached = run.getSlaBand() != null && run.getSlaBand().isBreached();
        boolean failureBreached = run.getStatus() == RunStatus.FAILED || run.getStatus() == RunStatus.TIMEOUT;
        boolean isBreached = timingBreached || failureBreached;
        run.setSlaBreached(isBreached);

        if (falsePositiveCleared) {
            // The sla_breach_events row and any fired alert remain as audit history.
            log.info("event=sla.breach.cleared reason=false_positive runId={} endTime={} slaTime={}",
                    run.getRunId(), run.getEndTime(), run.getSlaTime());
            meterRegistry.counter(SLA_BREACH_CLEARED).increment();
        }

        run = runRepository.upsert(run);

        // Deregister only after the transaction commits — a rolled-back completion must not
        // strip live SLA monitoring from a run that is still RUNNING in the database.
        deregisterAfterCommit(run.getRunId(), run.getTenantId(), run.getReportingDate());

        // calculator_sli_daily is populated by the nightly DailyAggregationJob, not per completion.

        meterRegistry.counter(INGESTION_RUN_COMPLETED,
                "frequency", run.getFrequency().name(),
                "status", run.getStatus().name(),
                "sla_breached", String.valueOf(isBreached)
        ).increment();

        boolean newlyBreached = !liveBreached && isBreached;
        if (newlyBreached) {
            eventPublisher.publishEvent(new SlaBreachedEvent(run, slaResult));
        } else {
            eventPublisher.publishEvent(new RunCompletedEvent(run));
        }

        lifecycleLogger.emit(LifecycleEvent.RUN_COMPLETE_SUCCESS, kv("reportingDate", run.getReportingDate()));

        return run;
    }

    /**
     * Deregisters the run from live SLA monitoring after the surrounding transaction commits.
     * Falls back to immediate deregistration when no transaction is active (tests, future callers).
     */
    private void deregisterAfterCommit(String runId, String tenantId, LocalDate reportingDate) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    slaMonitoringCache.deregisterFromSlaMonitoring(runId, tenantId, reportingDate);
                }
            });
        } else {
            slaMonitoringCache.deregisterFromSlaMonitoring(runId, tenantId, reportingDate);
        }
    }

    /**
     * Validate reporting_date matches frequency expectations. MONTHLY runs must carry an
     * end-of-month reporting date — dashboard MONTHLY queries filter to EOM dates, so a
     * non-EOM run would be ingested but never visible. Rejecting loudly beats silent loss.
     */
    private void validateReportingDate(StartRunRequest request) {
        if (request.getFrequency() == Frequency.MONTHLY) {
            LocalDate reportingDate = request.getReportingDate();
            LocalDate nextDay = reportingDate.plusDays(1);

            if (nextDay.getMonth() == reportingDate.getMonth()) {
                log.warn("event=run.validate.reporting_date outcome=rejected reason=non_eom_monthly reportingDate={}",
                        reportingDate);
                throw new DomainValidationException(
                        "MONTHLY reporting_date must be the last day of the month: " + reportingDate);
            }
        }
    }

    /**
     * Normalizes a numeric run_number ("01" → "1", trims) so DB filters, cache keys and the
     * T+N offset math all agree. Non-numeric values are kept as-is (don't break existing
     * senders) but flagged — {@code parseRunNumber} silently treats them as T+2.
     */
    private String normalizeRunNumber(String runNumber) {
        String normalized = RunNumbers.normalize(runNumber);
        if (normalized != null) {
            try {
                Integer.parseInt(normalized);
            } catch (NumberFormatException e) {
                log.warn("event=run.start.run_number outcome=accepted_with_warning reason=non_numeric runNumber={}", runNumber);
                meterRegistry.counter("obs.ingestion.run_number.non_numeric").increment();
            }
        }
        return normalized;
    }

    /**
     * Copy-on-write put shared by the dimension- and run-number-agnostic guards: preserves a stray
     * label in additional_attributes. No-ops on a null value; copies the (possibly-null) source map
     * on first real write so the request's map is never mutated in place.
     */
    private Map<String, Object> putStray(Map<String, Object> attributes, String key, String value) {
        if (value == null) {
            return attributes;
        }
        Map<String, Object> copy = attributes != null ? new LinkedHashMap<>(attributes) : new LinkedHashMap<>();
        copy.put(key, value);
        return copy;
    }

    /**
     * Resolves a promoted field: top-level request field takes precedence,
     * falls back to runParameters map for backward compatibility.
     */
    private String resolveField(String topLevel, Map<String, Object> params, String key) {
        if (topLevel != null) return topLevel;
        if (params == null) return null;
        Object val = params.get(key);
        return val != null ? val.toString() : null;
    }

    /**
     * Estimated start precedence: request value (Airflow) → historical avg start (cached
     * profile) → actual start time.
     */
    private Instant resolveEstimatedStart(StartRunRequest request, CalculatorProfile profile) {
        if (request.getEstimatedStartTime() != null) {
            return request.getEstimatedStartTime();
        }
        if (profile != null && profile.totalRuns() > 0 && request.getStartTime() != null) {
            LocalDate startDateUtc = request.getStartTime().atZone(ZoneOffset.UTC).toLocalDate();
            return TimeUtils.instantFromUtcMinuteOfDay(startDateUtc, profile.avgStartMinUtc());
        }
        return request.getStartTime();
    }

    /**
     * Estimated end precedence: request value (Airflow) → duration baseline → profile avg → null.
     *
     * <p>When no duration baseline exists we deliberately persist {@code null} rather than the frozen
     * clock-derived SLA deadline: conflating the deadline into {@code estimated_end_time} (an immutable
     * column) made runs look "planned" to consume their whole SLA budget. Null is honest — consumers
     * fall back to the {@code sla} field for the deadline.
     */
    private Instant resolveEstimatedEnd(
            StartRunRequest request,
            Instant estimatedStart,
            CalculatorProfile profile,
            SlaBaselineResolver.SlaResolution slaResolution
    ) {
        if (request.getEstimatedEndTime() != null) {
            return request.getEstimatedEndTime();
        }

        if (slaResolution != null && slaResolution.baselineDurationMs() != null) {
            boolean usingProfileAverage = request.getExpectedDurationMs() == null
                    && (request.getSlaTime() == null || request.getSlaTime().isBlank());
            if (usingProfileAverage && estimatedStart != null) {
                return estimatedStart.plusMillis(slaResolution.baselineDurationMs());
            }
            return TimeUtils.calculateEstimatedEndTime(request.getStartTime(), slaResolution.baselineDurationMs());
        }

        if (profile != null && profile.avgDurationMs() > 0 && estimatedStart != null) {
            return estimatedStart.plusMillis(profile.avgDurationMs());
        }
        return null;
    }

    /**
     * Resolves the value to persist in {@code expected_duration_ms} — the historical expectation,
     * independent of the SLA limit: request value → profile avg (when sufficient samples) →
     * resolved baseline (duration-spec / fallback) → null.
     */
    private Long resolveExpectedDuration(StartRunRequest request, CalculatorProfile profile,
                                         SlaBaselineResolver.SlaResolution slaResolution) {
        if (request.getExpectedDurationMs() != null && request.getExpectedDurationMs() > 0) {
            return request.getExpectedDurationMs();
        }
        if (profile != null && profile.hasSufficientSamples(slaProperties.getMinSampleSize()) && profile.avgDurationMs() > 0) {
            return profile.avgDurationMs();
        }
        return slaResolution != null ? slaResolution.baselineDurationMs() : null;
    }
}
