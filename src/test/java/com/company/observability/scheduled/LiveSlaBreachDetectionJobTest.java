package com.company.observability.scheduled;

import com.company.observability.cache.SlaMonitoringCache;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.domain.enums.SlaBand;
import com.company.observability.event.SlaBreachedEvent;
import com.company.observability.repository.CalculatorRunRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LiveSlaBreachDetectionJobTest {

    @Mock private SlaMonitoringCache slaMonitoringCache;
    @Mock private CalculatorRunRepository runRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private PlatformTransactionManager transactionManager;

    private LiveSlaBreachDetectionJob job;

    @BeforeEach
    void setUp() {
        job = new LiveSlaBreachDetectionJob(
                slaMonitoringCache, runRepository, eventPublisher, new SimpleMeterRegistry(),
                new com.company.observability.logging.LifecycleLogger(),
                new com.company.observability.config.SlaProperties(),
                new TransactionTemplate(transactionManager));
        job.registerGauges();
        // lenient: only called by breach-detection tests that reach recordMetrics, not early-warning tests
        lenient().when(slaMonitoringCache.getMonitoredRunCount()).thenReturn(0L);
    }

    // ---------------------------------------------------------------
    // detectLiveSlaBreaches — guard conditions (skip logic)
    // ---------------------------------------------------------------

    @Test
    void detectBreaches_completedRun_deregisteredWithoutMarkOrEvent() {
        LocalDate date = LocalDate.of(2026, 4, 10);
        when(slaMonitoringCache.getBreachedRuns())
                .thenReturn(List.of(runInfo("run-1", "tenant-1", date)));

        CalculatorRun completedRun = CalculatorRun.builder()
                .runId("run-1").calculatorId("calc-1").calculatorName("Calculator 1")
                .tenantId("tenant-1").frequency(Frequency.DAILY).reportingDate(date)
                .startTime(Instant.parse("2026-04-10T04:00:00Z")).status(RunStatus.SUCCESS)
                .slaTime(Instant.now().minusSeconds(600)).slaBand(null)
                .createdAt(Instant.parse("2026-04-10T04:00:00Z")).build();
        when(runRepository.findById("run-1", date)).thenReturn(Optional.of(completedRun));

        job.detectLiveSlaBreaches();

        verify(runRepository, never()).markSlaBreach(anyString(), any(), any(), anyString());
        verify(eventPublisher, never()).publishEvent(any());
        verify(slaMonitoringCache).deregisterFromSlaMonitoring("run-1", "tenant-1", date);
    }

    @Test
    void detectBreaches_alreadyBreachedRun_deregisteredWithoutMarkOrEvent() {
        LocalDate date = LocalDate.of(2026, 4, 10);
        when(slaMonitoringCache.getBreachedRuns())
                .thenReturn(List.of(runInfo("run-1", "tenant-1", date)));

        CalculatorRun alreadyBreached = CalculatorRun.builder()
                .runId("run-1").calculatorId("calc-1").calculatorName("Calculator 1")
                .tenantId("tenant-1").frequency(Frequency.DAILY).reportingDate(date)
                .startTime(Instant.parse("2026-04-10T04:00:00Z")).status(RunStatus.RUNNING)
                .slaTime(Instant.now().minusSeconds(600)).slaBand(SlaBand.LATE)
                .slaBreachReason("Already breached at start")
                .createdAt(Instant.parse("2026-04-10T04:00:00Z")).build();
        when(runRepository.findById("run-1", date)).thenReturn(Optional.of(alreadyBreached));

        job.detectLiveSlaBreaches();

        verify(runRepository, never()).markSlaBreach(anyString(), any(), any(), anyString());
        verify(eventPublisher, never()).publishEvent(any());
        verify(slaMonitoringCache).deregisterFromSlaMonitoring("run-1", "tenant-1", date);
    }

    @Test
    void detectBreaches_runNotFoundInDb_deregisteredGracefully() {
        LocalDate date = LocalDate.of(2026, 4, 10);
        when(slaMonitoringCache.getBreachedRuns())
                .thenReturn(List.of(runInfo("run-missing", "tenant-1", date)));
        when(runRepository.findById("run-missing", date)).thenReturn(Optional.empty());

        job.detectLiveSlaBreaches();

        verify(runRepository, never()).markSlaBreach(anyString(), any(), any(), anyString());
        verify(eventPublisher, never()).publishEvent(any());
        verify(slaMonitoringCache).deregisterFromSlaMonitoring("run-missing", "tenant-1", date);
    }

    // ---------------------------------------------------------------
    // detectLiveSlaBreaches — happy path
    // ---------------------------------------------------------------

    @Test
    void detectBreaches_validRunningRun_marksBreachAndPublishesEvent() {
        LocalDate date = LocalDate.of(2026, 4, 10);
        when(slaMonitoringCache.getBreachedRuns())
                .thenReturn(List.of(runInfo("run-1", "tenant-1", date)));

        CalculatorRun run = runningRun("run-1", date);
        when(runRepository.findById("run-1", date)).thenReturn(Optional.of(run));
        when(runRepository.markSlaBreach(eq("run-1"), eq(date), any(SlaBand.class), anyString())).thenReturn(1);

        job.detectLiveSlaBreaches();

        verify(runRepository).markSlaBreach(eq("run-1"), eq(date), any(SlaBand.class), anyString());
        verify(eventPublisher).publishEvent(any(SlaBreachedEvent.class));
        verify(slaMonitoringCache).deregisterFromSlaMonitoring("run-1", "tenant-1", date);
    }

    // ---------------------------------------------------------------
    // detectLiveSlaBreaches — lookup strategy
    // ---------------------------------------------------------------

    @Test
    void detectBreaches_reportingDatePresent_usesPartitionedLookup() {
        LocalDate date = LocalDate.of(2026, 4, 10);
        when(slaMonitoringCache.getBreachedRuns())
                .thenReturn(List.of(runInfo("run-1", "tenant-1", date)));
        when(runRepository.findById("run-1", date)).thenReturn(Optional.empty());

        job.detectLiveSlaBreaches();

        verify(runRepository).findById("run-1", date);
        verify(runRepository, never()).findById("run-1");
    }

    @Test
    void detectBreaches_reportingDateAbsent_usesFullScanFallback() {
        // run info without reportingDate → should fall back to full-scan findById(runId)
        when(slaMonitoringCache.getBreachedRuns())
                .thenReturn(List.of(runInfoWithoutDate("run-1", "tenant-1")));
        when(runRepository.findById("run-1")).thenReturn(Optional.empty());

        job.detectLiveSlaBreaches();

        verify(runRepository).findById("run-1");
        verify(runRepository, never()).findById(anyString(), any(LocalDate.class));
    }

    // ---------------------------------------------------------------
    // detectLiveSlaBreaches — resilience
    // ---------------------------------------------------------------

    @Test
    void detectBreaches_oneRunThrows_remainingRunsStillProcessed() {
        LocalDate date = LocalDate.of(2026, 4, 10);
        Map<String, Object> failingRun = runInfo("run-fail", "tenant-1", date);
        Map<String, Object> goodRun    = runInfo("run-good", "tenant-1", date);

        when(slaMonitoringCache.getBreachedRuns()).thenReturn(List.of(failingRun, goodRun));

        // First run triggers an exception during DB lookup
        when(runRepository.findById("run-fail", date))
                .thenThrow(new RuntimeException("DB error"));

        // Second run proceeds normally
        CalculatorRun run = runningRun("run-good", date);
        when(runRepository.findById("run-good", date)).thenReturn(Optional.of(run));
        when(runRepository.markSlaBreach(eq("run-good"), eq(date), any(SlaBand.class), anyString())).thenReturn(1);

        // Must not throw — per-run exceptions are caught and logged
        job.detectLiveSlaBreaches();

        verify(eventPublisher).publishEvent(any(SlaBreachedEvent.class));
    }

    // ---------------------------------------------------------------
    // sweepOverdueRunsFromDb — DB fallback path
    // ---------------------------------------------------------------

    @Test
    void dbSweep_overdueRunMissingFromRedis_marksBreachAndPublishesEvent() {
        LocalDate date = LocalDate.of(2026, 4, 10);
        CalculatorRun run = runningRun("run-orphan", date);
        when(runRepository.findOverdueRunningRuns()).thenReturn(List.of(run));
        when(runRepository.markSlaBreach(eq("run-orphan"), eq(date), any(SlaBand.class), anyString())).thenReturn(1);

        job.sweepOverdueRunsFromDb();

        verify(runRepository).markSlaBreach(eq("run-orphan"), eq(date), any(SlaBand.class), anyString());
        verify(eventPublisher).publishEvent(any(SlaBreachedEvent.class));
        // Stale Redis entry (if any) is dropped so the fast path stops re-examining it
        verify(slaMonitoringCache).deregisterFromSlaMonitoring("run-orphan", "tenant-1", date);
    }

    @Test
    void dbSweep_guardLostRace_noEventNoDeregistration() {
        LocalDate date = LocalDate.of(2026, 4, 10);
        CalculatorRun run = runningRun("run-1", date);
        when(runRepository.findOverdueRunningRuns()).thenReturn(List.of(run));
        // Another writer (completion / Redis path) won the sla_band IS NULL guard
        when(runRepository.markSlaBreach(eq("run-1"), eq(date), any(SlaBand.class), anyString())).thenReturn(0);

        job.sweepOverdueRunsFromDb();

        verify(eventPublisher, never()).publishEvent(any());
        verify(slaMonitoringCache, never()).deregisterFromSlaMonitoring(anyString(), anyString(), any());
    }

    @Test
    void dbSweep_noOverdueRuns_completesQuietly() {
        when(runRepository.findOverdueRunningRuns()).thenReturn(List.of());

        job.sweepOverdueRunsFromDb();

        verify(runRepository, never()).markSlaBreach(anyString(), any(), any(), anyString());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void dbSweep_oneRunThrows_remainingRunsStillProcessed() {
        LocalDate date = LocalDate.of(2026, 4, 10);
        CalculatorRun failing = runningRun("run-fail", date);
        CalculatorRun good = runningRun("run-good", date);
        when(runRepository.findOverdueRunningRuns()).thenReturn(List.of(failing, good));
        when(runRepository.markSlaBreach(eq("run-fail"), eq(date), any(SlaBand.class), anyString()))
                .thenThrow(new RuntimeException("DB error"));
        when(runRepository.markSlaBreach(eq("run-good"), eq(date), any(SlaBand.class), anyString())).thenReturn(1);

        job.sweepOverdueRunsFromDb();

        verify(eventPublisher).publishEvent(any(SlaBreachedEvent.class));
        verify(slaMonitoringCache).deregisterFromSlaMonitoring("run-good", "tenant-1", date);
    }

    // ---------------------------------------------------------------
    // detectApproachingSla — early-warning path
    // ---------------------------------------------------------------

    @Test
    void detectApproachingSla_runsInWindow_completesWithoutException() {
        long slaEpochMs = Instant.now().plusSeconds(300).toEpochMilli();
        Map<String, Object> runInfo = new HashMap<>();
        runInfo.put("runId", "run-1");
        runInfo.put("calculatorId", "calc-1");
        runInfo.put("calculatorName", "Calculator 1");
        runInfo.put("tenantId", "tenant-1");
        runInfo.put("slaTime", slaEpochMs);

        when(slaMonitoringCache.getApproachingSlaRuns(10)).thenReturn(List.of(runInfo));

        job.detectApproachingSla();

        verify(slaMonitoringCache).getApproachingSlaRuns(10);
    }

    @Test
    void detectApproachingSla_noRunsInWindow_completesQuietly() {
        when(slaMonitoringCache.getApproachingSlaRuns(10)).thenReturn(List.of());

        job.detectApproachingSla();

        verify(slaMonitoringCache).getApproachingSlaRuns(10);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /** A RUNNING, un-breached run with SLA time in the past (already breached from a time perspective). */
    private static CalculatorRun runningRun(String runId, LocalDate date) {
        return CalculatorRun.builder()
                .runId(runId)
                .calculatorId("calc-1")
                .calculatorName("Calculator 1")
                .tenantId("tenant-1")
                .frequency(Frequency.DAILY)
                .reportingDate(date)
                .startTime(Instant.parse("2026-04-10T04:00:00Z"))
                .status(RunStatus.RUNNING)
                .slaTime(Instant.now().minusSeconds(600)) // already past deadline
                .slaBand(null)
                .createdAt(Instant.parse("2026-04-10T04:00:00Z"))
                .build();
    }

    /** Run info map as returned by {@link SlaMonitoringCache#getBreachedRuns()}, with reportingDate set. */
    private static Map<String, Object> runInfo(String runId, String tenantId, LocalDate date) {
        Map<String, Object> m = new HashMap<>();
        m.put("runId", runId);
        m.put("tenantId", tenantId);
        m.put("calculatorId", "calc-1");
        m.put("calculatorName", "Calculator 1");
        m.put("reportingDate", date.toString());
        return m;
    }

    /** Run info map without reportingDate — triggers full-scan fallback in the job. */
    private static Map<String, Object> runInfoWithoutDate(String runId, String tenantId) {
        Map<String, Object> m = new HashMap<>();
        m.put("runId", runId);
        m.put("tenantId", tenantId);
        m.put("calculatorId", "calc-1");
        m.put("calculatorName", "Calculator 1");
        // no "reportingDate" key
        return m;
    }
}
