package com.company.observability.service;

import com.company.observability.cache.CalculatorStateCacheService;
import com.company.observability.config.SlaProperties;
import com.company.observability.domain.CalculatorProfile;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.dto.response.CalculatorBatchRunsResponse;
import com.company.observability.dto.response.CalculatorBatchRunsResponse.CalculatorEntry;
import com.company.observability.repository.CalculatorRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CalculatorStateServiceTest {

    @Mock
    CalculatorRunRepository runRepository;

    @Mock
    CalculatorStateCacheService stateCache;

    @Mock
    CalculatorProfileService profileService;

    @Mock
    CalculatorNameResolver nameResolver;

    @Mock
    Clock clock;

    // Real SlaProperties — still needed for getMinSampleSize() (profile estimation path).
    CalculatorStateService service;

    private static final LocalDate DATE = LocalDate.of(2026, 3, 6);
    private static final Frequency FREQ = Frequency.DAILY;
    private static final String FREQ_NAME = "DAILY";
    private static final Instant SLA_TIME = Instant.parse("2026-03-06T15:00:00Z");
    private static final Instant T_MINUS_3 = Instant.parse("2026-03-06T12:00:00Z");
    private static final Instant T_MINUS_2 = Instant.parse("2026-03-06T13:00:00Z");
    private static final Instant T_MINUS_1 = Instant.parse("2026-03-06T14:00:00Z");
    private static final Instant NOW       = Instant.parse("2026-03-06T14:45:00Z");

    // Zero-sample profile — not enough history to derive estimates.
    private static final CalculatorProfile NO_HISTORY_PROFILE =
            new CalculatorProfile("test-calc", "DAILY", null, null, 0, 0, 0, 0);

    @BeforeEach
    void setUp() {
        lenient().when(clock.instant()).thenReturn(NOW);
        service = new CalculatorStateService(
                runRepository, new SlaProperties(),
                stateCache, profileService, nameResolver, clock);
        // Default: cache returns no hits (all misses) so DB is called — matches all pre-existing tests
        lenient().when(stateCache.getEntries(any(), anyString(), any(), any()))
                .thenReturn(new HashMap<>());
        // Every pre-existing test uses an unconfigured name ("calc") while asserting run_number-
        // scoped behaviour, so the suite's implicit contract is "aware". Default to aware to keep
        // those tests meaning what they meant; the agnostic test overrides.
        lenient().when(nameResolver.isRunNumberAware(anyString())).thenReturn(true);
        // Default: no profile history and no fallback run — empty-runs case returns empty entry
        lenient().when(profileService.getProfile(anyString(), any(Frequency.class), any()))
                .thenReturn(NO_HISTORY_PROFILE);
        lenient().when(runRepository.findLatestRunEstimatesByName(anyString(), any(Frequency.class), any(), anyInt()))
                .thenReturn(Optional.empty());
    }

    @Test
    void returnsEmptyRunsForMissingCalculator() {
        when(runRepository.findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), eq("1"), any()))
                .thenReturn(List.of());

        Map<String, CalculatorBatchRunsResponse.CalculatorEntry> result =
                service.getState(DATE, FREQ, "1", List.of("missing-calc"), false);

        assertThat(result).containsKey("missing-calc");
        assertThat(result.get("missing-calc").runs()).isEmpty();
    }

    @Test
    void splitGroupsCollapsedIntoOneEntry() {
        CalculatorRun s1 = buildRun("cap", "r-s1", RunStatus.SUCCESS, "WMAP", null, "1", "corr-1", T_MINUS_3, T_MINUS_1, SLA_TIME);
        CalculatorRun s2 = buildRun("cap", "r-s2", RunStatus.FAILED,  "WMAP", null, "1", "corr-1", T_MINUS_3, T_MINUS_2, SLA_TIME);
        CalculatorRun s3 = buildRun("cap", "r-s3", RunStatus.RUNNING, "WMAP", null, "1", "corr-1", T_MINUS_3, null,       SLA_TIME);

        when(runRepository.findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), eq("1"), any()))
                .thenReturn(List.of(s1, s2, s3));

        var result = service.getState(DATE, FREQ, "1", List.of("cap"), false);
        var entries = result.get("cap").runs();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).status()).isEqualTo("RUNNING");   // worst-wins
        assertThat(entries.get(0).isRerun()).isFalse();              // splits != rerun
    }

    @Test
    void standaloneRerunDetectedByMultipleAttemptsInSameDimension() {
        CalculatorRun attempt1 = buildRun("cap", "r-1", RunStatus.FAILED,  "LDNL", null, "1", null, T_MINUS_3, T_MINUS_2, SLA_TIME);
        CalculatorRun attempt2 = buildRun("cap", "r-2", RunStatus.SUCCESS, "LDNL", null, "1", null, T_MINUS_1, NOW,       SLA_TIME);

        when(runRepository.findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), eq("1"), any()))
                .thenReturn(List.of(attempt1, attempt2));

        var result = service.getState(DATE, FREQ, "1", List.of("cap"), false);
        var entries = result.get("cap").runs();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).status()).isEqualTo("SUCCESS");
        assertThat(entries.get(0).isRerun()).isTrue();
    }

    @Test
    void regionalRunsGroupedUnderOneCalculatorId() {
        List<CalculatorRun> dbRuns = List.of(
                buildRun("capital", "r-wmap", RunStatus.SUCCESS, "WMAP", null, "1", null, T_MINUS_3, T_MINUS_1, SLA_TIME),
                buildRun("capital", "r-wmde", RunStatus.SUCCESS, "WMDE", null, "1", null, T_MINUS_3, T_MINUS_2, SLA_TIME)
        );
        when(runRepository.findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), eq("1"), any()))
                .thenReturn(dbRuns);

        var result = service.getState(DATE, FREQ, "1", List.of("capital"), false);
        var entry = result.get("capital");

        assertThat(entry.runs()).hasSize(2);
        assertThat(entry.runs()).extracting(CalculatorBatchRunsResponse.RunEntry::region)
                .containsExactlyInAnyOrder("WMAP", "WMDE");
    }

    // ── Duration-based slaStatus tests ──────────────────────────────────────

    /**
     * A RUNNING run whose live job has set slaBreached=true must surface as LATE/VERY_LATE,
     * not ON_TIME. Previously the old classify() returned ON_TIME for every RUNNING run.
     */
    @Test
    void runningRunWithSlaBreachedTrueIsNotOnTime() {
        CalculatorRun run = buildRun("calc", "r-1", RunStatus.RUNNING, "WMAP", null, "1", null,
                T_MINUS_3, null, SLA_TIME);
        run.setSlaBand(com.company.observability.domain.enums.SlaBand.LATE);

        when(runRepository.findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), eq("1"), any()))
                .thenReturn(List.of(run));

        var entries = service.getState(DATE, FREQ, "1", List.of("calc"), false).get("calc").runs();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).slaStatus()).isNotEqualTo("ON_TIME");
    }

    /**
     * A completed run graded LATE by SlaEvaluationService → slaStatus surfaces as LATE.
     */
    @Test
    void completedRunWithLateBandIsLate() {
        CalculatorRun run = buildRun("calc", "r-1", RunStatus.SUCCESS, "WMAP", null, "1", null,
                T_MINUS_3, SLA_TIME.plusSeconds(10 * 60), SLA_TIME);
        run.setSlaBand(com.company.observability.domain.enums.SlaBand.LATE);

        when(runRepository.findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), eq("1"), any()))
                .thenReturn(List.of(run));

        var entries = service.getState(DATE, FREQ, "1", List.of("calc"), false).get("calc").runs();

        assertThat(entries.get(0).slaStatus()).isEqualTo("LATE");
    }

    /**
     * A completed run graded VERY_LATE by SlaEvaluationService → slaStatus surfaces as VERY_LATE.
     */
    @Test
    void completedRunWithVeryLateBandIsVeryLate() {
        CalculatorRun run = buildRun("calc", "r-1", RunStatus.SUCCESS, "WMAP", null, "1", null,
                T_MINUS_3, SLA_TIME.plusSeconds(20 * 60), SLA_TIME);
        run.setSlaBand(com.company.observability.domain.enums.SlaBand.VERY_LATE);

        when(runRepository.findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), eq("1"), any()))
                .thenReturn(List.of(run));

        var entries = service.getState(DATE, FREQ, "1", List.of("calc"), false).get("calc").runs();

        assertThat(entries.get(0).slaStatus()).isEqualTo("VERY_LATE");
    }

    /**
     * expectedDurationMs must be propagated into RunEntry for standalone runs.
     */
    @Test
    void expectedDurationMsPropagatedForStandaloneRun() {
        CalculatorRun run = buildRun("calc", "r-1", RunStatus.SUCCESS, "WMAP", null, "1", null,
                T_MINUS_3, T_MINUS_1, SLA_TIME);
        run.setExpectedDurationMs(5_400_000L);  // 90 min expected

        when(runRepository.findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), eq("1"), any()))
                .thenReturn(List.of(run));

        var entries = service.getState(DATE, FREQ, "1", List.of("calc"), false).get("calc").runs();

        assertThat(entries.get(0).expectedDurationMs()).isEqualTo(5_400_000L);
    }

    /**
     * expectedDurationMs must survive split-group collapse (collapseSplitGroup path).
     */
    @Test
    void expectedDurationMsPropagatedForSplitGroup() {
        CalculatorRun s1 = buildRun("cap", "r-s1", RunStatus.SUCCESS, "WMAP", null, "1", "corr-1",
                T_MINUS_3, T_MINUS_1, SLA_TIME);
        s1.setExpectedDurationMs(7_200_000L);  // 120 min
        CalculatorRun s2 = buildRun("cap", "r-s2", RunStatus.SUCCESS, "WMAP", null, "1", "corr-1",
                T_MINUS_2, T_MINUS_1, SLA_TIME);
        s2.setExpectedDurationMs(7_200_000L);

        when(runRepository.findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), eq("1"), any()))
                .thenReturn(List.of(s1, s2));

        var entries = service.getState(DATE, FREQ, "1", List.of("cap"), false).get("cap").runs();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).expectedDurationMs()).isEqualTo(7_200_000L);
    }

    // ── Cache behaviour ─────────────────────────────────────────────────────

    @Test
    void fullCacheHit_skipsDbCall() {
        CalculatorEntry cachedEntry = new CalculatorEntry("cap", null, List.of());
        when(stateCache.getEntries(eq(DATE), eq(FREQ_NAME), isNull(), eq(List.of("cap"))))
                .thenReturn(Map.of("cap", cachedEntry));

        Map<String, CalculatorEntry> result = service.getState(DATE, FREQ, null, List.of("cap"), false);

        assertThat(result).containsKey("cap");
        verifyNoInteractions(runRepository);
    }

    @Test
    void partialCacheHit_queriesDbOnlyForMisses() {
        CalculatorRun run = buildRun("other", "r-1", RunStatus.SUCCESS, "WMAP", null, "1",
                null, T_MINUS_3, T_MINUS_1, SLA_TIME);
        CalculatorEntry cachedEntry = new CalculatorEntry("cap", null, List.of());

        when(stateCache.getEntries(eq(DATE), eq(FREQ_NAME), eq("1"), eq(List.of("cap", "other"))))
                .thenReturn(new HashMap<>(Map.of("cap", cachedEntry)));
        when(runRepository.findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), eq("1"), eq(List.of("other"))))
                .thenReturn(List.of(run));

        Map<String, CalculatorEntry> result = service.getState(DATE, FREQ, "1", List.of("cap", "other"), false);

        assertThat(result).containsKey("cap");
        assertThat(result).containsKey("other");
        // DB was only called for the miss ("other"), not for "cap"
        verify(runRepository).findAllRunsByDateAndDimension(DATE, FREQ, "1", List.of("other"));
    }

    @Test
    void absentCalculator_cachedAsEmptyEntry_toPreventRepeatDbHits() {
        when(runRepository.findAllRunsByDateAndDimension(any(), any(), any(), any()))
                .thenReturn(List.of());

        service.getState(DATE, FREQ, null, List.of("missing-calc"), false);

        // The empty-runs entry must be stored so a subsequent request hits cache, not DB
        verify(stateCache).putEntries(eq(DATE), eq(FREQ_NAME), isNull(), argThat(m ->
                m.containsKey("missing-calc") && m.get("missing-calc").runs().isEmpty()));
    }

    @Test
    void blankRunNumber_treatedAsNull_cachesUnder_all_sentinel() {
        when(runRepository.findAllRunsByDateAndDimension(any(), any(), isNull(), any()))
                .thenReturn(List.of());

        service.getState(DATE, FREQ, "  ", List.of("calc"), false);

        // Blank must normalise to null before reaching cache and repo
        verify(stateCache).getEntries(eq(DATE), eq(FREQ_NAME), isNull(), any());
        verify(runRepository).findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), isNull(), any());
    }

    // ── Not-started business-day anchoring (bug fix) ────────────────────────

    /**
     * A Friday reporting date with run_number=1 must anchor the synthetic not-started estimate
     * to the following Monday (T+1 business day), not to the reporting date itself — otherwise the
     * estimate (and its SLA grading) would sit on Friday and falsely read VERY_LATE when queried
     * on Monday.
     */
    @Test
    void notStartedEntry_anchorsEstimatesToNextBusinessDay() {
        LocalDate friday = LocalDate.of(2026, 2, 20);
        CalculatorProfile profile = new CalculatorProfile("calc", "DAILY", null, null, 3_600_000L, 480, 540, 10);
        when(profileService.getProfile(eq("calc"), eq(FREQ), eq("1"))).thenReturn(profile);
        when(runRepository.findAllRunsByDateAndDimension(eq(friday), eq(FREQ), eq("1"), any()))
                .thenReturn(List.of());
        // Provide a minimal latest run so the WP11 unknown-run-number guard does not fire;
        // no slaTime → offset falls back to parseRunNumber("1") = 1 → T+1 = Monday
        CalculatorRun latestRun = new CalculatorRun();
        latestRun.setCalculatorName("calc");
        when(runRepository.findLatestRunEstimatesByName(eq("calc"), eq(FREQ), eq("1"), anyInt()))
                .thenReturn(Optional.of(latestRun));

        var entries = service.getState(friday, FREQ, "1", List.of("calc"), false).get("calc").runs();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).status()).isEqualTo("NOT_STARTED");
        // Anchored to Monday 2026-02-23 at 08:00Z, NOT Friday 2026-02-20
        assertThat(entries.get(0).estimatedStartTime()).isEqualTo(Instant.parse("2026-02-23T08:00:00Z"));
        assertThat(entries.get(0).estimatedEndTime()).isEqualTo(Instant.parse("2026-02-23T09:00:00Z"));
    }

    // ── buildNotStartedEntry: estimates and deadline resolved independently ──

    /**
     * Profile path: when the profile has sufficient samples AND the latest run has a frozen SLA,
     * the synthetic entry carries the projected SLA and grades against it — not estEnd.
     * This fixes the previous early-return that skipped the latest-run SLA lookup on the profile path.
     */
    @Test
    void notStartedEntry_profilePath_carriesProjectedSlaFromLatestRun() {
        // Profile has samples → estimates sourced from profile
        CalculatorProfile profile = new CalculatorProfile("calc", "DAILY", null, null, 3_600_000L, 540, 600, 10);
        when(profileService.getProfile(eq("calc"), eq(FREQ), eq("1"))).thenReturn(profile);

        // Latest run has a frozen SLA well in the future
        CalculatorRun latest = new CalculatorRun();
        latest.setCalculatorId("calc-id");
        latest.setCalculatorName("calc");
        latest.setSlaTime(Instant.parse("2026-03-10T15:00:00Z")); // future clock-time SLA
        latest.setEstimatedStartTime(Instant.parse("2026-03-05T09:00:00Z"));
        latest.setExpectedDurationMs(3_600_000L);
        when(runRepository.findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), eq("1"), any()))
                .thenReturn(List.of());
        when(runRepository.findLatestRunEstimatesByName(eq("calc"), eq(FREQ), eq("1"), anyInt()))
                .thenReturn(Optional.of(latest));

        var entries = service.getState(DATE, FREQ, "1", List.of("calc"), false).get("calc").runs();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).status()).isEqualTo("NOT_STARTED");
        // Profile-sourced estimates must be present
        assertThat(entries.get(0).estimatedStartTime()).isNotNull();
        assertThat(entries.get(0).estimatedEndTime()).isNotNull();
        // SLA must be projected from the latest run's frozen clock-time (not null)
        assertThat(entries.get(0).sla()).isNotNull();
    }

    /**
     * When the profile has no sufficient samples and the latest run has no stored estimates,
     * but it does have a frozen SLA, the entry is still created (deadline-only) rather than
     * returning the empty brand-new entry.
     */
    @Test
    void notStartedEntry_noEstimates_butHasSla_returnsEntryWithSlaOnly() {
        // Profile insufficient
        when(profileService.getProfile(eq("calc"), eq(FREQ), isNull())).thenReturn(NO_HISTORY_PROFILE);

        // Latest run: no estimate data, but has SLA
        CalculatorRun latest = new CalculatorRun();
        latest.setCalculatorId("calc-id");
        latest.setCalculatorName("calc");
        latest.setSlaTime(Instant.parse("2026-03-10T15:00:00Z"));
        latest.setEstimatedStartTime(null);
        latest.setExpectedDurationMs(null);

        when(runRepository.findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), isNull(), any()))
                .thenReturn(List.of());
        when(runRepository.findLatestRunEstimatesByName(eq("calc"), eq(FREQ), isNull(), anyInt()))
                .thenReturn(Optional.of(latest));

        var entries = service.getState(DATE, FREQ, null, List.of("calc"), false).get("calc").runs();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).status()).isEqualTo("NOT_STARTED");
        assertThat(entries.get(0).sla()).isNotNull();
        assertThat(entries.get(0).estimatedStartTime()).isNull();
    }

    /**
     * Brand-new calculator with no profile and no latest run → empty entry (no history at all).
     */
    @Test
    void notStartedEntry_noProfileNoLatestRun_returnsEmptyEntry() {
        when(profileService.getProfile(eq("new-calc"), eq(FREQ), isNull())).thenReturn(NO_HISTORY_PROFILE);
        when(runRepository.findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), isNull(), any()))
                .thenReturn(List.of());
        // findLatestRunEstimatesByName already returns Optional.empty() from setUp

        var entries = service.getState(DATE, FREQ, null, List.of("new-calc"), false).get("new-calc").runs();

        assertThat(entries).isEmpty();
    }

    /**
     * DAILY projection must derive the T+N offset from the latest run's reportingDate→slaTime
     * distance, overriding the run_number fallback. Latest run reported Tue with a Wed deadline
     * (T+1); querying a Friday with run_number=2 must still project T+1 (Monday), not T+2 (Tuesday).
     */
    @Test
    void notStartedEntry_daily_derivesOffsetFromLatestRunDistance() {
        when(profileService.getProfile(eq("calc"), eq(FREQ), eq("2"))).thenReturn(NO_HISTORY_PROFILE);

        CalculatorRun latest = new CalculatorRun();
        latest.setCalculatorId("calc-id");
        latest.setCalculatorName("calc");
        latest.setReportingDate(LocalDate.of(2026, 3, 3));          // Tuesday
        latest.setSlaTime(Instant.parse("2026-03-04T15:00:00Z"));   // Wednesday → T+1

        when(runRepository.findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), eq("2"), any()))
                .thenReturn(List.of());
        when(runRepository.findLatestRunEstimatesByName(eq("calc"), eq(FREQ), eq("2"), anyInt()))
                .thenReturn(Optional.of(latest));

        var entries = service.getState(DATE, FREQ, "2", List.of("calc"), false).get("calc").runs();

        assertThat(entries).hasSize(1);
        // derived N=1 wins over run_number=2 → query Fri 2026-03-06 + 1 biz day = Mon 2026-03-09 at 15:00
        assertThat(entries.get(0).sla()).isEqualTo(Instant.parse("2026-03-09T15:00:00Z"));
    }

    /**
     * The latest-run lookup must be scoped by run_number so a RUN1 projection does not borrow
     * RUN2's frozen deadline.
     */
    @Test
    void notStartedEntry_scopesLatestRunLookupByRunNumber() {
        when(runRepository.findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), eq("1"), any()))
                .thenReturn(List.of());

        service.getState(DATE, FREQ, "1", List.of("calc"), false);

        verify(runRepository).findLatestRunEstimatesByName(eq("calc"), eq(FREQ), eq("1"), anyInt());
    }

    /**
     * WP11: a run_number filter with zero scoped history must return an empty entry, not a
     * synthetic projection — run_number=99 must not invent a T+99 deadline from the fallback.
     */
    @Test
    void notStartedEntry_unknownRunNumber_returnsEmptyEntryNotProjection() {
        when(runRepository.findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), eq("99"), any()))
                .thenReturn(List.of());
        // findLatestRunEstimatesByName returns Optional.empty() from setUp — no rn=99 history

        var entries = service.getState(DATE, FREQ, "99", List.of("calc"), false).get("calc").runs();

        assertThat(entries).isEmpty();
        // The projection machinery must not even be consulted
        verifyNoInteractions(profileService);
    }

    /**
     * WP10: without a run_number filter, scheduled RUN1/RUN2 cycles in the same dimension are
     * distinct entries — not collapsed latest-wins, and not mislabeled as reruns.
     */
    @Test
    void noRunNumberFilter_multiCycleRunsStayDistinct_notMarkedRerun() {
        CalculatorRun run1 = buildRun("cap", "r-1", RunStatus.SUCCESS, "LDNL", null, "1", null, T_MINUS_3, T_MINUS_2, SLA_TIME);
        CalculatorRun run2 = buildRun("cap", "r-2", RunStatus.RUNNING, "LDNL", null, "2", null, T_MINUS_1, null, SLA_TIME);

        when(runRepository.findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), isNull(), any()))
                .thenReturn(List.of(run1, run2));

        var entries = service.getState(DATE, FREQ, null, List.of("cap"), false).get("cap").runs();

        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(CalculatorBatchRunsResponse.RunEntry::status)
                .containsExactlyInAnyOrder("SUCCESS", "RUNNING");
        assertThat(entries).allSatisfy(e -> assertThat(e.isRerun()).isFalse());
    }

    /**
     * MONTHLY deadlines are start-anchored, so a not-started projection anchors the cutoff on the
     * estimated start date (with overnight roll), not on a T+N business-day offset.
     */
    @Test
    void notStartedEntry_monthly_anchorsSlaOnEstimatedStart() {
        LocalDate eom = LocalDate.of(2026, 2, 28);
        // Profile avg start = 1320 min UTC (22:00); 10 samples → trusted.
        CalculatorProfile profile = new CalculatorProfile("calc", "MONTHLY", null, null, 3_600_000L, 1320, 1380, 10);
        when(profileService.getProfile(eq("calc"), eq(Frequency.MONTHLY), eq("1"))).thenReturn(profile);

        CalculatorRun latest = new CalculatorRun();
        latest.setCalculatorId("calc-id");
        latest.setCalculatorName("calc");
        latest.setSlaTime(Instant.parse("2026-01-31T02:00:00Z")); // 02:00 cutoff

        when(runRepository.findAllRunsByDateAndDimension(eq(eom), eq(Frequency.MONTHLY), eq("1"), any()))
                .thenReturn(List.of());
        when(runRepository.findLatestRunEstimatesByName(eq("calc"), eq(Frequency.MONTHLY), eq("1"), anyInt()))
                .thenReturn(Optional.of(latest));

        var entries = service.getState(eom, Frequency.MONTHLY, "1", List.of("calc"), false).get("calc").runs();

        assertThat(entries).hasSize(1);
        Instant estStart = entries.get(0).estimatedStartTime();
        assertThat(estStart).isNotNull();
        // MONTHLY SLA = clockTimeDeadline(estStart, 02:00, UTC) — start-anchored, overnight roll
        assertThat(entries.get(0).sla()).isEqualTo(
                com.company.observability.util.TimeUtils.clockTimeDeadline(
                        estStart, java.time.LocalTime.of(2, 0), java.time.ZoneOffset.UTC));
    }

    // ── runNumber stamped on NOT_STARTED projection ─────────────────────────

    /**
     * When a run_number is requested and the calculator has no runs for that date,
     * the synthetic NOT_STARTED entry must carry the requested run_number so the
     * strict-filter in mergeEntries keeps it rather than dropping it as a null.
     */
    @Test
    void notStartedEntry_stampedWithRequestedRunNumber() {
        CalculatorProfile profile = new CalculatorProfile("calc", "DAILY", null, null, 3_600_000L, 480, 540, 10);
        when(profileService.getProfile(eq("calc"), eq(FREQ), eq("1"))).thenReturn(profile);

        CalculatorRun latest = new CalculatorRun();
        latest.setCalculatorName("calc");
        when(runRepository.findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), eq("1"), any()))
                .thenReturn(List.of());
        when(runRepository.findLatestRunEstimatesByName(eq("calc"), eq(FREQ), eq("1"), anyInt()))
                .thenReturn(Optional.of(latest));

        var entries = service.getState(DATE, FREQ, "1", List.of("calc"), false).get("calc").runs();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).status()).isEqualTo("NOT_STARTED");
        assertThat(entries.get(0).runNumber()).isEqualTo("1");
    }

    // ── nocache bypass ──────────────────────────────────────────────────────

    // ── run-number-agnostic calculators ignore the run_number filter ─────────

    /**
     * A run-number-agnostic calculator (market-risk, modelled-exposure, gemini-hedge) queried with
     * an explicit run_number must ignore the filter: ingestion nulls a stray run_number, so its
     * entire history is un-numbered and a scoped lookup matches zero rows — which made the WP11
     * unknown-run-number guard wrongly return an empty entry. Regression for the missing-entry bug
     * on marketriskrwacalcdev / modelledexposurecalcdev / geminihedgefundcalcdev.
     */
    @Test
    void notStartedEntry_agnosticCalculator_ignoresRunNumberFilter() {
        when(nameResolver.isRunNumberAware("agnostic-calc")).thenReturn(false);

        CalculatorProfile profile =
                new CalculatorProfile("agnostic-calc", "DAILY", null, null, 3_600_000L, 540, 600, 3);
        when(profileService.getProfile(eq("agnostic-calc"), eq(FREQ), eq("1"))).thenReturn(profile);

        // All real history is un-numbered → only the UNSCOPED lookup can find it.
        CalculatorRun latest = new CalculatorRun();
        latest.setCalculatorId("mr-id");
        latest.setCalculatorName("agnostic-calc");
        latest.setReportingDate(DATE);
        latest.setSlaTime(SLA_TIME);
        when(runRepository.findLatestRunEstimatesByName(eq("agnostic-calc"), eq(FREQ), isNull(), anyInt()))
                .thenReturn(Optional.of(latest));
        when(runRepository.findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), eq("1"), any()))
                .thenReturn(List.of());

        var entries = service.getState(DATE, FREQ, "1", List.of("agnostic-calc"), false)
                .get("agnostic-calc").runs();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).status()).isEqualTo("NOT_STARTED");
        assertThat(entries.get(0).estimatedStartTime()).isNotNull();
        assertThat(entries.get(0).sla()).isNotNull();
        // The scoped lookup must never be issued for an agnostic calculator.
        verify(runRepository, never())
                .findLatestRunEstimatesByName(anyString(), any(Frequency.class), eq("1"), anyInt());
    }

    // ── nocache bypass ──────────────────────────────────────────────────────

    @Test
    void getState_nocache_skipsCacheAndFetchesAllFromDb() {
        when(runRepository.findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), isNull(), any()))
                .thenReturn(List.of());

        service.getState(DATE, FREQ, null, List.of("cap", "other"), true);

        // Cache read must NOT be called — nocache=true skips it entirely
        verify(stateCache, never()).getEntries(any(), any(), any(), any());
        // Both names must be fetched from DB (no cache partials)
        verify(runRepository).findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), isNull(), eq(List.of("cap", "other")));
        // Fresh result must still be written back to cache
        verify(stateCache).putEntries(eq(DATE), eq(FREQ_NAME), isNull(), argThat(m ->
                m.containsKey("cap") && m.containsKey("other")));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private CalculatorRun buildRun(String calcId, String runId, RunStatus status,
                                   String region, String runType, String runNumber,
                                   String correlationId, Instant createdAt,
                                   Instant endTime, Instant slaTime) {
        CalculatorRun run = new CalculatorRun();
        run.setCalculatorId(calcId);
        run.setCalculatorName(calcId);
        run.setRunId(runId);
        run.setStatus(status);
        run.setRegion(region);
        run.setRunType(runType);
        run.setRunNumber(runNumber);
        run.setCorrelationId(correlationId);
        run.setCreatedAt(createdAt);
        run.setEndTime(endTime);
        run.setSlaTime(slaTime);
        run.setReportingDate(DATE);
        return run;
    }
}
