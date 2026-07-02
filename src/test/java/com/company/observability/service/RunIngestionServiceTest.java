package com.company.observability.service;

import com.company.observability.cache.SlaMonitoringCache;
import com.company.observability.config.SlaProperties;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.Dimension;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.domain.enums.CompletionStatus;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.domain.enums.SlaBand;
import com.company.observability.dto.request.CompleteRunRequest;
import com.company.observability.dto.request.StartRunRequest;
import com.company.observability.event.RunCompletedEvent;
import com.company.observability.event.SlaBreachedEvent;
import com.company.observability.exception.DomainAccessDeniedException;
import com.company.observability.exception.DomainValidationException;
import com.company.observability.domain.CalculatorProfile;
import com.company.observability.repository.CalculatorRunRepository;
import com.company.observability.domain.SlaEvaluationResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunIngestionServiceTest {

    @Mock
    private CalculatorRunRepository runRepository;
    @Mock
    private SlaEvaluationService slaEvaluationService;
    @Mock
    private SlaBaselineResolver slaBaselineResolver;
    @Mock
    private CalculatorProfileService calculatorProfileService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private SlaMonitoringCache slaMonitoringCache;
    @Mock
    private CalculatorNameResolver calculatorNameResolver;

    private RunIngestionService service;

    private static final CalculatorProfile EMPTY_PROFILE =
            new CalculatorProfile("Calculator 1", "DAILY", null, null, 0, 0, 0, 0);

    private SlaProperties slaProperties;

    @BeforeEach
    void setUp() {
        slaProperties = new SlaProperties();
        service = new RunIngestionService(
                runRepository,
                slaEvaluationService,
                slaBaselineResolver,
                calculatorProfileService,
                eventPublisher,
                new SimpleMeterRegistry(),
                slaMonitoringCache,
                new com.company.observability.logging.LifecycleLogger(),
                slaProperties,
                calculatorNameResolver
        );
        // Default scoped-profile chain: no history. Individual tests override the 4-arg
        // (estimate) overload when they need profile-sourced estimates.
        org.mockito.Mockito.lenient()
                .when(calculatorProfileService.getProfile(anyString(), any(Frequency.class), any()))
                .thenReturn(EMPTY_PROFILE);
        org.mockito.Mockito.lenient()
                .when(calculatorProfileService.getProfile(anyString(), any(Frequency.class), any(), any()))
                .thenReturn(EMPTY_PROFILE);
        // Default: treat calculators as run-number-aware so run_number-bearing tests keep their
        // cycle label. The run-number-agnostic guard tests override this to false per name.
        org.mockito.Mockito.lenient()
                .when(calculatorNameResolver.isRunNumberAware(anyString()))
                .thenReturn(true);
    }

    @Test
    void completeRun_rejectsEndTimeBeforeStartTime() {
        Instant start = Instant.parse("2026-02-20T10:00:00Z");
        CalculatorRun run = runningRun(start);
        when(runRepository.findByIdForUpdate(eq("run-1"), any(LocalDate.class))).thenReturn(Optional.of(run));

        CompleteRunRequest request = CompleteRunRequest.builder()
                .reportingDate(LocalDate.now())
                .endTime(start.minusSeconds(1))
                .status(CompletionStatus.SUCCESS)
                .build();

        assertThrows(DomainValidationException.class,
                () -> service.completeRun("run-1", request, "tenant-1"));

        verify(runRepository, never()).upsert(any());
        verify(slaMonitoringCache, never()).deregisterFromSlaMonitoring(anyString(), anyString(), any(LocalDate.class));
    }

    @Test
    void startRun_derivedDeadline_freezesSlaTimeAndRegistersForMonitoring() {
        ReflectionTestUtils.setField(service, "liveTrackingEnabled", true);

        LocalDate reportingDate = LocalDate.of(2026, 2, 20);
        Instant start = Instant.parse("2026-02-20T05:00:00Z");
        Instant derivedDeadline = start.plusSeconds(3600);

        StartRunRequest request = StartRunRequest.builder()
                .runId("run-derived")
                .calculatorId("calc-1")
                .calculatorName("Calculator 1")
                .frequency(Frequency.DAILY)
                .reportingDate(reportingDate)
                .startTime(start)
                .build();

        when(runRepository.findById("run-derived", reportingDate)).thenReturn(Optional.empty());
        when(runRepository.upsert(any(CalculatorRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(slaBaselineResolver.resolve(any(StartRunRequest.class), eq(Frequency.DAILY), any()))
                .thenReturn(new SlaBaselineResolver.SlaResolution(
                        3_600_000L,
                        derivedDeadline
                ));

        service.startRun(request, "tenant-1");

        verify(runRepository).upsert(argThat(run ->
                derivedDeadline.equals(run.getSlaTime())
                        && run.getSlaBand() == null));
        verify(slaMonitoringCache).registerForSlaMonitoring(any(CalculatorRun.class));
        verify(eventPublisher, never()).publishEvent(any(SlaBreachedEvent.class));
    }

    @Test
    void startRun_monthlyWithDerivedDeadline_registersForMonitoring() {
        ReflectionTestUtils.setField(service, "liveTrackingEnabled", true);

        LocalDate eom = LocalDate.of(2026, 2, 28);
        Instant start = Instant.parse("2026-02-28T05:00:00Z");
        Instant derivedDeadline = start.plusSeconds(7200);

        StartRunRequest request = StartRunRequest.builder()
                .runId("run-monthly-derived")
                .calculatorId("calc-1")
                .calculatorName("Calculator 1")
                .frequency(Frequency.MONTHLY)
                .reportingDate(eom)
                .startTime(start)
                .build();

        when(runRepository.findById("run-monthly-derived", eom)).thenReturn(Optional.empty());
        when(runRepository.upsert(any(CalculatorRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(slaBaselineResolver.resolve(any(StartRunRequest.class), eq(Frequency.MONTHLY), any()))
                .thenReturn(new SlaBaselineResolver.SlaResolution(
                        7_200_000L,
                        derivedDeadline
                ));

        service.startRun(request, "tenant-1");

        // MONTHLY now registers for live monitoring (previously DAILY-only).
        verify(slaMonitoringCache).registerForSlaMonitoring(any(CalculatorRun.class));
        verify(eventPublisher, never()).publishEvent(any(SlaBreachedEvent.class));
    }

    @Test
    void startRun_noBaseline_skipsMonitoringAndDoesNotBreach() {
        ReflectionTestUtils.setField(service, "liveTrackingEnabled", true);

        LocalDate reportingDate = LocalDate.of(2026, 2, 20);
        Instant start = Instant.parse("2026-02-20T05:00:00Z");

        StartRunRequest request = StartRunRequest.builder()
                .runId("run-ungraded")
                .calculatorId("calc-1")
                .calculatorName("Calculator 1")
                .frequency(Frequency.DAILY)
                .reportingDate(reportingDate)
                .startTime(start)
                .build();

        when(runRepository.findById("run-ungraded", reportingDate)).thenReturn(Optional.empty());
        when(runRepository.upsert(any(CalculatorRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(slaBaselineResolver.resolve(any(StartRunRequest.class), eq(Frequency.DAILY), any()))
                .thenReturn(new SlaBaselineResolver.SlaResolution(
                        null,
                        null
                ));

        service.startRun(request, "tenant-1");

        verify(slaMonitoringCache, never()).registerForSlaMonitoring(any(CalculatorRun.class));
        verify(runRepository).upsert(argThat(run -> run.getSlaTime() == null
                && run.getSlaBand() == null));
        verify(eventPublisher).publishEvent(any(com.company.observability.event.RunStartedEvent.class));
    }

    @Test
    void startRun_resolvedBaselineUsedAsExpectedDurationWhenRequestAndProfileEmpty() {
        // expectedDuration chain: request → profile avg → resolved baseline → null.
        // With no request value and an empty profile, the resolved baseline is the fallback.
        ReflectionTestUtils.setField(service, "liveTrackingEnabled", true);

        LocalDate reportingDate = LocalDate.of(2026, 2, 20);
        Instant start = Instant.parse("2026-02-20T05:00:00Z");
        Instant derivedDeadline = start.plusSeconds(4_200);

        StartRunRequest request = StartRunRequest.builder()
                .runId("run-baseline")
                .calculatorId("calc-1")
                .calculatorName("Calculator 1")
                .frequency(Frequency.DAILY)
                .reportingDate(reportingDate)
                .startTime(start)
                .slaTime("PT1H")
                .build(); // no expectedDurationMs

        when(runRepository.findById("run-baseline", reportingDate)).thenReturn(Optional.empty());
        when(runRepository.upsert(any(CalculatorRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(slaBaselineResolver.resolve(any(StartRunRequest.class), eq(Frequency.DAILY), any()))
                .thenReturn(new SlaBaselineResolver.SlaResolution(3_600_000L, derivedDeadline));

        service.startRun(request, "tenant-1");

        verify(runRepository).upsert(argThat(run ->
                Long.valueOf(3_600_000L).equals(run.getExpectedDurationMs())
                        && derivedDeadline.equals(run.getSlaTime())));
    }

    @Test
    void startRun_persistsRequestExpectedDurationMs() {
        // expectedDurationMs from the request always wins over the resolved baseline.
        ReflectionTestUtils.setField(service, "liveTrackingEnabled", true);

        LocalDate reportingDate = LocalDate.of(2026, 2, 20);
        Instant start = Instant.parse("2026-02-20T05:00:00Z");
        Instant clockDeadline = Instant.parse("2026-02-20T22:00:00Z");

        StartRunRequest request = StartRunRequest.builder()
                .runId("run-clock")
                .calculatorId("calc-1")
                .calculatorName("Calculator 1")
                .frequency(Frequency.DAILY)
                .reportingDate(reportingDate)
                .startTime(start)
                .slaTime("22:00")
                .expectedDurationMs(7_200_000L)
                .build();

        when(runRepository.findById("run-clock", reportingDate)).thenReturn(Optional.empty());
        when(runRepository.upsert(any(CalculatorRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(slaBaselineResolver.resolve(any(StartRunRequest.class), eq(Frequency.DAILY), any()))
                .thenReturn(new SlaBaselineResolver.SlaResolution(null, clockDeadline));

        service.startRun(request, "tenant-1");

        verify(runRepository).upsert(argThat(run ->
                Long.valueOf(7_200_000L).equals(run.getExpectedDurationMs())
                        && clockDeadline.equals(run.getSlaTime())));
    }

    @Test
    void startRun_clockMode_estimatedEndIsNullWhenNoDuration() {
        // With no duration baseline and an empty profile, estimatedEndTime is persisted null —
        // the frozen clock deadline is carried only by slaTime (consumers fall back to it).
        ReflectionTestUtils.setField(service, "liveTrackingEnabled", true);

        LocalDate reportingDate = LocalDate.of(2026, 2, 20);
        Instant start = Instant.parse("2026-02-20T05:00:00Z");
        Instant clockDeadline = Instant.parse("2026-02-20T22:00:00Z");

        StartRunRequest request = StartRunRequest.builder()
                .runId("run-clock-end")
                .calculatorId("calc-1")
                .calculatorName("Calculator 1")
                .frequency(Frequency.DAILY)
                .reportingDate(reportingDate)
                .startTime(start)
                .slaTime("22:00")
                .build(); // no expectedDurationMs, no estimatedEndTime

        when(runRepository.findById("run-clock-end", reportingDate)).thenReturn(Optional.empty());
        when(runRepository.upsert(any(CalculatorRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(slaBaselineResolver.resolve(any(StartRunRequest.class), eq(Frequency.DAILY), any()))
                .thenReturn(new SlaBaselineResolver.SlaResolution(null, clockDeadline));

        service.startRun(request, "tenant-1");

        verify(runRepository).upsert(argThat(run ->
                run.getEstimatedEndTime() == null
                        && clockDeadline.equals(run.getSlaTime())));
    }

    @Test
    void startRun_estimatedEnd_usesResolvedBaselineWhenOnlySlaTimeProvided() {
        ReflectionTestUtils.setField(service, "liveTrackingEnabled", true);

        LocalDate reportingDate = LocalDate.of(2026, 2, 20);
        Instant start = Instant.parse("2026-02-20T05:00:00Z");

        StartRunRequest request = StartRunRequest.builder()
                .runId("run-sla-only")
                .calculatorId("calc-1")
                .calculatorName("Calculator 1")
                .frequency(Frequency.DAILY)
                .reportingDate(reportingDate)
                .startTime(start)
                .slaTime("PT2H")
                .build();

        when(runRepository.findById("run-sla-only", reportingDate)).thenReturn(Optional.empty());
        when(runRepository.upsert(any(CalculatorRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(slaBaselineResolver.resolve(any(StartRunRequest.class), eq(Frequency.DAILY), any()))
                .thenReturn(new SlaBaselineResolver.SlaResolution(
                        7_200_000L,
                        start.plusSeconds(8_640)
                ));

        service.startRun(request, "tenant-1");

        verify(runRepository).upsert(argThat(run ->
                start.plusSeconds(7_200).equals(run.getEstimatedEndTime())));
    }

    @Test
    void completeRun_alreadyBreachedPublishesCompletedEventAndSkipsDuplicateBreachEvent() {
        Instant start = Instant.parse("2026-02-20T10:00:00Z");
        CalculatorRun run = runningRun(start);
        run.setSlaBand(SlaBand.LATE);
        run.setSlaBreachReason("Start time is after SLA deadline");

        when(runRepository.findByIdForUpdate(eq("run-1"), any(LocalDate.class))).thenReturn(Optional.of(run));
        when(slaEvaluationService.evaluateSla(any(CalculatorRun.class)))
                .thenReturn(new SlaEvaluationResult(SlaBand.LATE, "Finished 30 minutes late"));
        when(runRepository.upsert(any(CalculatorRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CompleteRunRequest request = CompleteRunRequest.builder()
                .reportingDate(LocalDate.now())
                .endTime(start.plusSeconds(600))
                .status(CompletionStatus.SUCCESS)
                .build();

        service.completeRun("run-1", request, "tenant-1");

        verify(eventPublisher).publishEvent(any(RunCompletedEvent.class));
        verify(eventPublisher, never()).publishEvent(any(SlaBreachedEvent.class));
    }

    @Test
    void completeRun_newBreachOnCompletion_persistsBreachAndPublishesBreachEvent() {
        Instant start = Instant.parse("2026-02-20T10:00:00Z");
        CalculatorRun run = runningRun(start);
        run.setSlaBand(null);
        run.setSlaBreachReason(null);

        when(runRepository.findByIdForUpdate(eq("run-1"), any(LocalDate.class))).thenReturn(Optional.of(run));
        when(slaEvaluationService.evaluateSla(any(CalculatorRun.class)))
                .thenReturn(new SlaEvaluationResult(SlaBand.LATE, "Finished 30 minutes late"));
        when(runRepository.upsert(any(CalculatorRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CompleteRunRequest request = CompleteRunRequest.builder()
                .reportingDate(LocalDate.now())
                .endTime(start.plusSeconds(1800))
                .status(CompletionStatus.SUCCESS)
                .build();

        service.completeRun("run-1", request, "tenant-1");

        verify(runRepository).upsert(argThat(saved ->
                SlaBand.LATE.equals(saved.getSlaBand())
                        && "Finished 30 minutes late".equals(saved.getSlaBreachReason())));
        verify(eventPublisher).publishEvent(any(SlaBreachedEvent.class));
        verify(eventPublisher, never()).publishEvent(any(RunCompletedEvent.class));
    }

    // ---------------------------------------------------------------
    // startRun — additional coverage
    // ---------------------------------------------------------------

    @Test
    void startRun_idempotent_returnsExistingRunWithoutPublishingEvents() {
        LocalDate reportingDate = LocalDate.of(2026, 4, 10);
        Instant start = Instant.parse("2026-04-10T05:00:00Z");
        CalculatorRun existing = runningRun(start);

        StartRunRequest request = StartRunRequest.builder()
                .runId("run-1")
                .calculatorId("calc-1")
                .calculatorName("Calculator 1")
                .frequency(Frequency.DAILY)
                .reportingDate(reportingDate)
                .startTime(start)
                .slaTime("2026-02-20T05:15:00Z")
                .build();

        when(runRepository.findById("run-1", reportingDate)).thenReturn(Optional.of(existing));

        RunIngestionService.StartRunOutcome result = service.startRun(request, "tenant-1");

        assertThat(result.run()).isEqualTo(existing);
        assertThat(result.created()).isFalse();
        verify(runRepository, never()).upsert(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void startRun_monthly_nonEomDate_rejectedWith400() {
        // Jan 15 is not end-of-month — MONTHLY dashboard queries filter to EOM dates, so the
        // run would be ingested but invisible. Must reject loudly.
        LocalDate nonEom = LocalDate.of(2026, 1, 15);
        Instant start = Instant.parse("2026-01-15T05:00:00Z");

        StartRunRequest request = StartRunRequest.builder()
                .runId("run-monthly")
                .calculatorId("calc-1")
                .calculatorName("Calculator 1")
                .frequency(Frequency.MONTHLY)
                .reportingDate(nonEom)
                .startTime(start)
                .slaTime("05:15")
                .build();

        when(runRepository.findById("run-monthly", nonEom)).thenReturn(Optional.empty());

        assertThrows(DomainValidationException.class,
                () -> service.startRun(request, "tenant-1"));

        verify(runRepository, never()).upsert(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void startRun_monthly_eomDate_idempotentReplayNotRejected() {
        // The duplicate check runs before validation — a replay of an existing run must
        // return it, never re-validate into a rejection.
        LocalDate nonEom = LocalDate.of(2026, 1, 15);
        Instant start = Instant.parse("2026-01-15T05:00:00Z");
        CalculatorRun existing = runningRun(start);

        StartRunRequest request = StartRunRequest.builder()
                .runId("run-1")
                .calculatorId("calc-1")
                .calculatorName("Calculator 1")
                .frequency(Frequency.MONTHLY)
                .reportingDate(nonEom)
                .startTime(start)
                .build();

        when(runRepository.findById("run-1", nonEom)).thenReturn(Optional.of(existing));

        RunIngestionService.StartRunOutcome result = service.startRun(request, "tenant-1");

        assertThat(result.created()).isFalse();
        assertThat(result.run()).isEqualTo(existing);
    }

    // ---------------------------------------------------------------
    // completeRun — additional coverage
    // ---------------------------------------------------------------

    @Test
    void completeRun_endTimeEqualsStartTime_durationIsZeroAndSucceeds() {
        Instant start = Instant.parse("2026-04-10T05:00:00Z");
        CalculatorRun run = runningRun(start);
        when(runRepository.findByIdForUpdate(eq("run-1"), any(LocalDate.class))).thenReturn(Optional.of(run));
        when(slaEvaluationService.evaluateSla(any())).thenReturn(new SlaEvaluationResult(SlaBand.ON_TIME, null));
        when(runRepository.upsert(any())).thenAnswer(inv -> inv.getArgument(0));

        CompleteRunRequest request = CompleteRunRequest.builder()
                .reportingDate(LocalDate.now())
                .endTime(start)   // exactly equal to startTime
                .status(CompletionStatus.SUCCESS)
                .build();

        CalculatorRun result = service.completeRun("run-1", request, "tenant-1");

        assertThat(result.getDurationMs()).isZero();
        verify(eventPublisher).publishEvent(any(RunCompletedEvent.class));
    }

    @Test
    void completeRun_tenantMismatch_throwsDomainAccessDeniedException() {
        Instant start = Instant.parse("2026-04-10T05:00:00Z");
        CalculatorRun run = runningRun(start); // tenantId = "tenant-1"
        when(runRepository.findByIdForUpdate(eq("run-1"), any(LocalDate.class))).thenReturn(Optional.of(run));

        CompleteRunRequest request = CompleteRunRequest.builder()
                .reportingDate(LocalDate.now())
                .endTime(start.plusSeconds(600))
                .status(CompletionStatus.SUCCESS)
                .build();

        assertThrows(DomainAccessDeniedException.class,
                () -> service.completeRun("run-1", request, "different-tenant"));

        verify(runRepository, never()).upsert(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ---------------------------------------------------------------
    // startRun — estimated start/end precedence
    // ---------------------------------------------------------------

    @Test
    void startRun_estimatedTimes_requestValuesWinOverProfile() {
        ReflectionTestUtils.setField(service, "liveTrackingEnabled", true);
        LocalDate reportingDate = LocalDate.of(2026, 2, 20);
        Instant start = Instant.parse("2026-02-20T05:00:00Z");
        Instant reqEstStart = Instant.parse("2026-02-20T04:30:00Z");
        Instant reqEstEnd = Instant.parse("2026-02-20T06:30:00Z");

        StartRunRequest request = StartRunRequest.builder()
                .runId("run-est").calculatorId("calc-1").calculatorName("Calculator 1")
                .frequency(Frequency.DAILY).reportingDate(reportingDate).startTime(start)
                .estimatedStartTime(reqEstStart).estimatedEndTime(reqEstEnd)
                .build();

        when(runRepository.findById("run-est", reportingDate)).thenReturn(Optional.empty());
        when(runRepository.upsert(any(CalculatorRun.class))).thenAnswer(inv -> inv.getArgument(0));
        // Profile has samples, but request values must take precedence.
        when(calculatorProfileService.getProfile("Calculator 1", Frequency.DAILY, null, null))
                .thenReturn(new CalculatorProfile("Calculator 1", "DAILY", null, null, 3_600_000L, 200, 300, 10));
        when(slaBaselineResolver.resolve(any(StartRunRequest.class), eq(Frequency.DAILY), any()))
                .thenReturn(new SlaBaselineResolver.SlaResolution(
                        null,
                        null
                ));

        service.startRun(request, "tenant-1");

        verify(runRepository).upsert(argThat(run ->
                reqEstStart.equals(run.getEstimatedStartTime())
                        && reqEstEnd.equals(run.getEstimatedEndTime())));
    }

    @Test
    void startRun_estimatedTimes_derivedFromProfileWhenRequestOmits() {
        ReflectionTestUtils.setField(service, "liveTrackingEnabled", true);
        LocalDate reportingDate = LocalDate.of(2026, 2, 20);
        Instant start = Instant.parse("2026-02-20T05:00:00Z");

        StartRunRequest request = StartRunRequest.builder()
                .runId("run-est2").calculatorId("calc-1").calculatorName("Calculator 1")
                .frequency(Frequency.DAILY).reportingDate(reportingDate).startTime(start)
                .build(); // no estimated times, no expectedDurationMs

        // avg start = 270 min UTC = 04:30; avg duration = 1h — estimates come from the
        // dimension-scoped (4-arg) profile lookup.
        when(runRepository.findById("run-est2", reportingDate)).thenReturn(Optional.empty());
        when(runRepository.upsert(any(CalculatorRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(calculatorProfileService.getProfile("Calculator 1", Frequency.DAILY, null, null))
                .thenReturn(new CalculatorProfile("Calculator 1", "DAILY", null, null, 3_600_000L, 270, 330, 10));
        when(slaBaselineResolver.resolve(any(StartRunRequest.class), eq(Frequency.DAILY), any()))
                .thenReturn(new SlaBaselineResolver.SlaResolution(
                        3_600_000L,
                        null
                ));

        service.startRun(request, "tenant-1");

        Instant expectedStart = Instant.parse("2026-02-20T04:30:00Z");          // date(start) + 270 min UTC
        Instant expectedEnd = expectedStart.plusMillis(3_600_000L);             // estimatedStart + avgDuration
        verify(runRepository).upsert(argThat(run ->
                expectedStart.equals(run.getEstimatedStartTime())
                        && expectedEnd.equals(run.getEstimatedEndTime())));
    }

    @Test
    void completeRun_wrongReportingDate_throwsNotFoundException() {
        LocalDate wrongDate = LocalDate.of(2026, 1, 1);
        when(runRepository.findByIdForUpdate(eq("run-1"), eq(wrongDate))).thenReturn(Optional.empty());

        CompleteRunRequest request = CompleteRunRequest.builder()
                .reportingDate(wrongDate)
                .endTime(Instant.parse("2026-04-10T06:00:00Z"))
                .status(CompletionStatus.SUCCESS)
                .build();

        assertThrows(com.company.observability.exception.DomainNotFoundException.class,
                () -> service.completeRun("run-1", request, "tenant-1"));

        verify(runRepository, never()).upsert(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ---------------------------------------------------------------
    // WP1 — on-write grading is the final authority at completion
    // ---------------------------------------------------------------

    @Test
    void completeRun_falsePositiveLiveBreach_clearedWhenFinishedBeforeDeadline() {
        // Live detection marked LATE because /complete arrived after the deadline,
        // but the run actually finished before it — on-write ON_TIME must win.
        Instant start = Instant.parse("2026-02-20T10:00:00Z");
        CalculatorRun run = runningRun(start);
        run.setSlaTime(start.plusSeconds(3600));
        run.setSlaBand(SlaBand.LATE);
        run.setSlaBreached(true);
        run.setSlaBreachReason("Still running 5 minutes past SLA deadline (detected live via Redis monitoring)");

        when(runRepository.findByIdForUpdate(eq("run-1"), any(LocalDate.class))).thenReturn(Optional.of(run));
        when(slaEvaluationService.evaluateSla(any(CalculatorRun.class)))
                .thenReturn(new SlaEvaluationResult(SlaBand.ON_TIME, null));
        when(runRepository.upsert(any(CalculatorRun.class))).thenAnswer(inv -> inv.getArgument(0));

        CompleteRunRequest request = CompleteRunRequest.builder()
                .reportingDate(LocalDate.now())
                .endTime(start.plusSeconds(600)) // well before the deadline
                .status(CompletionStatus.SUCCESS)
                .build();

        service.completeRun("run-1", request, "tenant-1");

        verify(runRepository).upsert(argThat(saved ->
                SlaBand.ON_TIME.equals(saved.getSlaBand())
                        && !saved.isSlaBreached()
                        && saved.getSlaBreachReason() == null));
        verify(eventPublisher).publishEvent(any(RunCompletedEvent.class));
        verify(eventPublisher, never()).publishEvent(any(SlaBreachedEvent.class));
    }

    @Test
    void completeRun_staleLiveBand_upgradedByOnWriteGrade() {
        // Live detection set LATE at minute 16; the run kept going and finished VERY_LATE.
        Instant start = Instant.parse("2026-02-20T10:00:00Z");
        CalculatorRun run = runningRun(start);
        run.setSlaTime(start.plusSeconds(900));
        run.setSlaBand(SlaBand.LATE);
        run.setSlaBreached(true);
        run.setSlaBreachReason("Still running 1 minutes past SLA deadline (detected live via Redis monitoring)");

        when(runRepository.findByIdForUpdate(eq("run-1"), any(LocalDate.class))).thenReturn(Optional.of(run));
        when(slaEvaluationService.evaluateSla(any(CalculatorRun.class)))
                .thenReturn(new SlaEvaluationResult(SlaBand.VERY_LATE, "Finished 75 minutes late (VERY_LATE band)"));
        when(runRepository.upsert(any(CalculatorRun.class))).thenAnswer(inv -> inv.getArgument(0));

        CompleteRunRequest request = CompleteRunRequest.builder()
                .reportingDate(LocalDate.now())
                .endTime(start.plusSeconds(5400))
                .status(CompletionStatus.SUCCESS)
                .build();

        service.completeRun("run-1", request, "tenant-1");

        verify(runRepository).upsert(argThat(saved ->
                SlaBand.VERY_LATE.equals(saved.getSlaBand())
                        && saved.isSlaBreached()
                        && "Finished 75 minutes late (VERY_LATE band)".equals(saved.getSlaBreachReason())));
        // Already breached live → no duplicate breach event
        verify(eventPublisher).publishEvent(any(RunCompletedEvent.class));
        verify(eventPublisher, never()).publishEvent(any(SlaBreachedEvent.class));
    }

    // ---------------------------------------------------------------
    // WP4 — scoped profiles at ingestion
    // ---------------------------------------------------------------

    @Test
    void startRun_scopedProfiles_runNumberForBaseline_dimensionForEstimates() {
        ReflectionTestUtils.setField(service, "liveTrackingEnabled", false);
        LocalDate reportingDate = LocalDate.of(2026, 2, 20);
        Instant start = Instant.parse("2026-02-20T05:00:00Z");

        StartRunRequest request = StartRunRequest.builder()
                .runId("run-scoped").calculatorId("calc-1").calculatorName("Calculator 1")
                .frequency(Frequency.DAILY).reportingDate(reportingDate).startTime(start)
                .runNumber("1").region("WMAP")
                .build();

        CalculatorProfile rnProfile = new CalculatorProfile("Calculator 1", "DAILY", "1", null, 3_600_000L, 270, 330, 10);
        CalculatorProfile dimProfile = new CalculatorProfile("Calculator 1", "DAILY", "1", "WMAP", 1_800_000L, 300, 330, 10);
        when(calculatorProfileService.getProfile("Calculator 1", Frequency.DAILY, "1")).thenReturn(rnProfile);
        when(calculatorProfileService.getProfile("Calculator 1", Frequency.DAILY, "1", "WMAP")).thenReturn(dimProfile);

        when(runRepository.findById("run-scoped", reportingDate)).thenReturn(Optional.empty());
        when(runRepository.upsert(any(CalculatorRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(slaBaselineResolver.resolve(any(StartRunRequest.class), eq(Frequency.DAILY), eq(rnProfile)))
                .thenReturn(new SlaBaselineResolver.SlaResolution(null, null));

        service.startRun(request, "tenant-1");

        // Baseline resolver received the run_number-scoped profile (verified by the eq(rnProfile) stub);
        // estimates and expected duration come from the dimension-scoped one (30 min, start 05:00 UTC).
        Instant expectedStart = Instant.parse("2026-02-20T05:00:00Z"); // minute-of-day 300
        verify(runRepository).upsert(argThat(run ->
                expectedStart.equals(run.getEstimatedStartTime())
                        && expectedStart.plusMillis(1_800_000L).equals(run.getEstimatedEndTime())
                        && Long.valueOf(1_800_000L).equals(run.getExpectedDurationMs())));
    }

    // ---------------------------------------------------------------
    // Dimension-agnostic ingestion guard — NONE calcs collapse stray labels to 'ALL'
    // ---------------------------------------------------------------

    @Test
    void startRun_noneCalc_strayRegion_collapsedToAllAndPreservedInAttributes() {
        ReflectionTestUtils.setField(service, "liveTrackingEnabled", false);
        LocalDate reportingDate = LocalDate.of(2026, 2, 20);
        Instant start = Instant.parse("2026-02-20T05:00:00Z");

        when(calculatorNameResolver.dimensionOf("portfolio")).thenReturn(Dimension.NONE);

        StartRunRequest request = StartRunRequest.builder()
                .runId("run-none").calculatorId("calc-1").calculatorName("portfolio")
                .frequency(Frequency.DAILY).reportingDate(reportingDate).startTime(start)
                .region("GLOBAL")
                .additionalAttributes(new java.util.HashMap<>(java.util.Map.of("source", "airflow")))
                .build();

        when(runRepository.findById("run-none", reportingDate)).thenReturn(Optional.empty());
        when(runRepository.upsert(any(CalculatorRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(slaBaselineResolver.resolve(any(StartRunRequest.class), eq(Frequency.DAILY), any()))
                .thenReturn(new SlaBaselineResolver.SlaResolution(null, null));

        service.startRun(request, "tenant-1");

        // region column nulled; label preserved in additional_attributes; pre-existing key survives.
        verify(runRepository).upsert(argThat(run ->
                run.getRegion() == null
                        && run.getRunType() == null
                        && "GLOBAL".equals(run.getAdditionalAttributes().get("region"))
                        && "airflow".equals(run.getAdditionalAttributes().get("source"))));
        // Dimension null → estimates routed to the blended (3-arg) profile, not a per-region slice.
        verify(calculatorProfileService).getProfile("portfolio", Frequency.DAILY, null, null);
    }

    @Test
    void startRun_regionCalc_regionLeftUntouched() {
        ReflectionTestUtils.setField(service, "liveTrackingEnabled", false);
        LocalDate reportingDate = LocalDate.of(2026, 2, 20);
        Instant start = Instant.parse("2026-02-20T05:00:00Z");

        when(calculatorNameResolver.dimensionOf("capital")).thenReturn(Dimension.REGION);

        StartRunRequest request = StartRunRequest.builder()
                .runId("run-region").calculatorId("calc-1").calculatorName("capital")
                .frequency(Frequency.DAILY).reportingDate(reportingDate).startTime(start)
                .region("AMER")
                .build();

        when(runRepository.findById("run-region", reportingDate)).thenReturn(Optional.empty());
        when(runRepository.upsert(any(CalculatorRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(slaBaselineResolver.resolve(any(StartRunRequest.class), eq(Frequency.DAILY), any()))
                .thenReturn(new SlaBaselineResolver.SlaResolution(null, null));

        service.startRun(request, "tenant-1");

        // REGION archetype: region kept as the dimension, estimates routed to the AMER slice.
        verify(runRepository).upsert(argThat(run -> "AMER".equals(run.getRegion())));
        verify(calculatorProfileService).getProfile("capital", Frequency.DAILY, null, "AMER");
    }

    // ---------------------------------------------------------------
    // Run-number-agnostic ingestion guard — non-aware calcs collapse a stray run_number to 'ALL'
    // ---------------------------------------------------------------

    @Test
    void startRun_agnosticCalc_strayRunNumber_nulledAndPreservedInAttributes() {
        ReflectionTestUtils.setField(service, "liveTrackingEnabled", false);
        LocalDate reportingDate = LocalDate.of(2026, 2, 20);
        Instant start = Instant.parse("2026-02-20T05:00:00Z");

        when(calculatorNameResolver.isRunNumberAware("gemini-hedge")).thenReturn(false);

        StartRunRequest request = StartRunRequest.builder()
                .runId("run-agnostic-rn").calculatorId("calc-1").calculatorName("gemini-hedge")
                .frequency(Frequency.DAILY).reportingDate(reportingDate).startTime(start)
                .runNumber("2")
                .additionalAttributes(new java.util.HashMap<>(java.util.Map.of("source", "airflow")))
                .build();

        when(runRepository.findById("run-agnostic-rn", reportingDate)).thenReturn(Optional.empty());
        when(runRepository.upsert(any(CalculatorRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(slaBaselineResolver.resolve(any(StartRunRequest.class), eq(Frequency.DAILY), any()))
                .thenReturn(new SlaBaselineResolver.SlaResolution(null, null));

        service.startRun(request, "tenant-1");

        // run_number column nulled; original preserved in additional_attributes; pre-existing key survives.
        verify(runRepository).upsert(argThat(run ->
                run.getRunNumber() == null
                        && "2".equals(run.getAdditionalAttributes().get("run_number"))
                        && "airflow".equals(run.getAdditionalAttributes().get("source"))));
        // Baseline profile looked up with the normalized (null) run_number.
        verify(calculatorProfileService).getProfile("gemini-hedge", Frequency.DAILY, null);
    }

    @Test
    void startRun_awareCalc_strayRunNumberPreserved() {
        ReflectionTestUtils.setField(service, "liveTrackingEnabled", false);
        LocalDate reportingDate = LocalDate.of(2026, 2, 20);
        Instant start = Instant.parse("2026-02-20T05:00:00Z");

        when(calculatorNameResolver.isRunNumberAware("capital")).thenReturn(true);

        StartRunRequest request = StartRunRequest.builder()
                .runId("run-aware-rn").calculatorId("calc-1").calculatorName("capital")
                .frequency(Frequency.DAILY).reportingDate(reportingDate).startTime(start)
                .runNumber("2")
                .build();

        when(runRepository.findById("run-aware-rn", reportingDate)).thenReturn(Optional.empty());
        when(runRepository.upsert(any(CalculatorRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(slaBaselineResolver.resolve(any(StartRunRequest.class), eq(Frequency.DAILY), any()))
                .thenReturn(new SlaBaselineResolver.SlaResolution(null, null));

        service.startRun(request, "tenant-1");

        // Aware calc keeps its cycle label; baseline profile scoped by run_number "2".
        verify(runRepository).upsert(argThat(run -> "2".equals(run.getRunNumber())));
        verify(calculatorProfileService).getProfile("capital", Frequency.DAILY, "2");
    }

    // ---------------------------------------------------------------
    // WP5 — run_number normalization
    // ---------------------------------------------------------------

    @Test
    void startRun_numericRunNumber_normalized() {
        ReflectionTestUtils.setField(service, "liveTrackingEnabled", false);
        LocalDate reportingDate = LocalDate.of(2026, 2, 20);
        Instant start = Instant.parse("2026-02-20T05:00:00Z");

        StartRunRequest request = StartRunRequest.builder()
                .runId("run-rn").calculatorId("calc-1").calculatorName("Calculator 1")
                .frequency(Frequency.DAILY).reportingDate(reportingDate).startTime(start)
                .runNumber("01")
                .build();

        when(runRepository.findById("run-rn", reportingDate)).thenReturn(Optional.empty());
        when(runRepository.upsert(any(CalculatorRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(slaBaselineResolver.resolve(any(StartRunRequest.class), eq(Frequency.DAILY), any()))
                .thenReturn(new SlaBaselineResolver.SlaResolution(null, null));

        service.startRun(request, "tenant-1");

        verify(runRepository).upsert(argThat(run -> "1".equals(run.getRunNumber())));
    }

    // ---------------------------------------------------------------
    // WP7 — conflicting /complete + tenant consistency
    // ---------------------------------------------------------------

    @Test
    void completeRun_conflictingTerminalStatus_throwsConflict() {
        Instant start = Instant.parse("2026-02-20T10:00:00Z");
        CalculatorRun run = runningRun(start);
        run.setStatus(RunStatus.SUCCESS); // already completed

        when(runRepository.findByIdForUpdate(eq("run-1"), any(LocalDate.class))).thenReturn(Optional.of(run));

        CompleteRunRequest request = CompleteRunRequest.builder()
                .reportingDate(LocalDate.now())
                .endTime(start.plusSeconds(600))
                .status(CompletionStatus.FAILED) // tries to change the recorded outcome
                .build();

        assertThrows(com.company.observability.exception.DomainConflictException.class,
                () -> service.completeRun("run-1", request, "tenant-1"));

        verify(runRepository, never()).upsert(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void completeRun_sameStatusReplay_idempotent200() {
        Instant start = Instant.parse("2026-02-20T10:00:00Z");
        CalculatorRun run = runningRun(start);
        run.setStatus(RunStatus.SUCCESS);

        when(runRepository.findByIdForUpdate(eq("run-1"), any(LocalDate.class))).thenReturn(Optional.of(run));

        CompleteRunRequest request = CompleteRunRequest.builder()
                .reportingDate(LocalDate.now())
                .endTime(start.plusSeconds(600))
                .status(CompletionStatus.SUCCESS)
                .build();

        CalculatorRun result = service.completeRun("run-1", request, "tenant-1");

        assertThat(result).isEqualTo(run);
        verify(runRepository, never()).upsert(any());
    }

    @Test
    void completeRun_missingTenantHeader_onTenantOwnedRun_allowed() {
        Instant start = Instant.parse("2026-02-20T10:00:00Z");
        CalculatorRun run = runningRun(start); // tenantId = "tenant-1"
        when(runRepository.findByIdForUpdate(eq("run-1"), any(LocalDate.class))).thenReturn(Optional.of(run));
        when(slaEvaluationService.evaluateSla(any())).thenReturn(new SlaEvaluationResult(null, null));
        when(runRepository.upsert(any())).thenAnswer(inv -> inv.getArgument(0));

        CompleteRunRequest request = CompleteRunRequest.builder()
                .reportingDate(LocalDate.now())
                .endTime(start.plusSeconds(600))
                .build();

        // Advisory mode: missing header is logged, not blocked
        CalculatorRun result = service.completeRun("run-1", request, null);

        assertThat(result.getStatus()).isEqualTo(RunStatus.SUCCESS);
    }

    @Test
    void completeRun_nullStoredTenant_withHeader_allowed() {
        Instant start = Instant.parse("2026-02-20T10:00:00Z");
        CalculatorRun run = runningRun(start);
        run.setTenantId(null); // ingested without a header
        when(runRepository.findByIdForUpdate(eq("run-1"), any(LocalDate.class))).thenReturn(Optional.of(run));
        when(slaEvaluationService.evaluateSla(any())).thenReturn(new SlaEvaluationResult(null, null));
        when(runRepository.upsert(any())).thenAnswer(inv -> inv.getArgument(0));

        CompleteRunRequest request = CompleteRunRequest.builder()
                .reportingDate(LocalDate.now())
                .endTime(start.plusSeconds(600))
                .build();

        // Previously this 403'd (!tenantId.equals(null) is always true) — must be allowed
        CalculatorRun result = service.completeRun("run-1", request, "tenant-a");

        assertThat(result.getStatus()).isEqualTo(RunStatus.SUCCESS);
    }

    private CalculatorRun runningRun(Instant start) {
        return CalculatorRun.builder()
                .runId("run-1")
                .calculatorId("calc-1")
                .calculatorName("Calculator 1")
                .tenantId("tenant-1")
                .frequency(Frequency.DAILY)
                .reportingDate(LocalDate.now())
                .startTime(start)
                .status(RunStatus.RUNNING)
                .createdAt(start)
                .slaBand(null)
                .build();
    }
}

