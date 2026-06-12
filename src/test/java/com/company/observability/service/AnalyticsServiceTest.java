package com.company.observability.service;

import com.company.observability.cache.AnalyticsCacheService;
import com.company.observability.domain.RunWithSlaStatus;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.domain.enums.SlaBand;
import com.company.observability.dto.response.RunPerformanceData;
import com.company.observability.repository.CalculatorRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.company.observability.config.CalculatorProperties;


@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @Mock
    private CalculatorRunRepository calculatorRunRepository;
    @Mock
    private AnalyticsCacheService cacheService;
    @Mock
    private CalculatorProfileService calculatorProfileService;

    private AnalyticsService service;

    // Passthrough resolver — any unknown name maps to itself (no aliases configured)
    private final CalculatorNameResolver passthroughResolver = passthroughResolver();

    @BeforeEach
    void setUp() {
        service = new AnalyticsService(
                calculatorRunRepository,
                cacheService,
                calculatorProfileService,
                new com.company.observability.config.SlaProperties(),
                passthroughResolver
        );
    }

    private static CalculatorNameResolver passthroughResolver() {
        CalculatorProperties props = new CalculatorProperties();
        // empty map → all names pass through unchanged
        return new CalculatorNameResolver(props);
    }

    // ── getRunExecutionsByName — cache behaviour ──────────────────────────────

    @Test
    void getRunExecutionsByName_cacheHit_skipsDb() {
        RunPerformanceData cached = new RunPerformanceData(
                "cap", "Capital", "DAILY", 30, 0L, 0, 0, 0, 0, 0,
                List.of(), null, null);
        when(cacheService.getFromCache(
                eq("executions"), eq("cap"), eq("DAILY"), eq(30), isNull(),
                any(LocalDate.class), eq(RunPerformanceData.class)))
                .thenReturn(cached);

        RunPerformanceData result = service.getRunExecutionsByName("cap", 30, Frequency.DAILY, null, LocalDate.now());

        assertEquals(cached, result);
        verifyNoInteractions(calculatorRunRepository);
    }

    @Test
    void getRunExecutionsByName_cacheMiss_queriesDbAndPopulatesCache() {
        when(cacheService.getFromCache(any(), any(), any(), anyInt(), any(), any(), any()))
                .thenReturn(null);
        when(calculatorRunRepository.findRunsByName(
                eq("cap"), eq(Frequency.DAILY), eq(30), isNull(), any(LocalDate.class)))
                .thenReturn(List.of());

        service.getRunExecutionsByName("cap", 30, Frequency.DAILY, null, LocalDate.now());

        verify(calculatorRunRepository)
                .findRunsByName(eq("cap"), eq(Frequency.DAILY), eq(30), isNull(), any(LocalDate.class));
        verify(cacheService).putInCache(
                eq("executions"), eq("cap"), eq("DAILY"), eq(30), isNull(), any(LocalDate.class), any());
    }

    @Test
    void getRunExecutionsByName_runNumberDistinguishesKeys() {
        when(cacheService.getFromCache(any(), any(), any(), anyInt(), eq("2"), any(), any()))
                .thenReturn(null);
        when(calculatorRunRepository.findRunsByName(
                eq("cap"), any(), anyInt(), eq("2"), any(LocalDate.class)))
                .thenReturn(List.of());

        service.getRunExecutionsByName("cap", 30, Frequency.DAILY, "2", LocalDate.now());

        verify(cacheService).getFromCache(
                eq("executions"), eq("cap"), eq("DAILY"), eq(30), eq("2"), any(LocalDate.class), any());
        verify(cacheService).putInCache(
                eq("executions"), eq("cap"), eq("DAILY"), eq(30), eq("2"), any(LocalDate.class), any());
    }

    @Test
    void getRunExecutionsByName_blankRunNumber_normalisedToNull() {
        when(cacheService.getFromCache(any(), any(), any(), anyInt(), isNull(), any(), any()))
                .thenReturn(null);
        when(calculatorRunRepository.findRunsByName(any(), any(), anyInt(), isNull(), any(LocalDate.class)))
                .thenReturn(List.of());

        service.getRunExecutionsByName("cap", 30, Frequency.DAILY, "  ", LocalDate.now());

        verify(cacheService).getFromCache(
                eq("executions"), eq("cap"), eq("DAILY"), eq(30), isNull(), any(LocalDate.class), any());
        verify(calculatorRunRepository)
                .findRunsByName(eq("cap"), eq(Frequency.DAILY), eq(30), isNull(), any(LocalDate.class));
    }

    @Test
    void getRunExecutionsByName_multiAlias_mergesRunsFromAllRealCalculators() {
        CalculatorProperties props = new CalculatorProperties();
        props.setAliases(Map.of(
                "capital", List.of("capitalcalc", "capitalcalcmedium")
        ));
        AnalyticsService aliasService = new AnalyticsService(
                calculatorRunRepository,
                cacheService,
                calculatorProfileService,
                new com.company.observability.config.SlaProperties(),
                new CalculatorNameResolver(props)
        );

        when(cacheService.getFromCache(any(), eq("capital"), any(), anyInt(), any(), any(), any()))
                .thenReturn(null);

        LocalDate day = LocalDate.of(2026, 6, 1);
        Instant start = Instant.parse("2026-06-01T05:00:00Z");
        Instant end = Instant.parse("2026-06-01T06:00:00Z");

        RunWithSlaStatus run1 = new RunWithSlaStatus(
                "run-1", "id-1", "capitalcalc", day,
                start, end, 3_600_000L, null, start,
                Frequency.DAILY, RunStatus.SUCCESS, null, null, null, null, null);
        RunWithSlaStatus run2 = new RunWithSlaStatus(
                "run-2", "id-2", "capitalcalcmedium", day,
                start, end, 1_800_000L, null, start,
                Frequency.DAILY, RunStatus.SUCCESS, null, null, null, null, null);

        when(calculatorRunRepository.findRunsByName(eq("capitalcalc"), any(), anyInt(), any(), any(LocalDate.class)))
                .thenReturn(List.of(run1));
        when(calculatorRunRepository.findRunsByName(eq("capitalcalcmedium"), any(), anyInt(), any(), any(LocalDate.class)))
                .thenReturn(List.of(run2));
        when(calculatorProfileService.getProfile(any(), any()))
                .thenReturn(new com.company.observability.domain.CalculatorProfile("capitalcalc", "DAILY", null, null, 0, 0, 0, 0));

        RunPerformanceData result = aliasService.getRunExecutionsByName("capital", 30, Frequency.DAILY, null, LocalDate.now());

        assertEquals("capital", result.calculatorId());
        assertEquals(2, result.runs().size());
        verify(calculatorRunRepository).findRunsByName(eq("capitalcalc"), any(), anyInt(), any(), any(LocalDate.class));
        verify(calculatorRunRepository).findRunsByName(eq("capitalcalcmedium"), any(), anyInt(), any(), any(LocalDate.class));
        verify(cacheService).putInCache(eq("executions"), eq("capital"), any(), anyInt(), any(), any(LocalDate.class), any());
    }

    @Test
    void getRunExecutionsByName_singleAlias_queriesRealNameOnly() {
        CalculatorProperties props = new CalculatorProperties();
        props.setAliases(Map.of("portfolio", List.of("portfoliocalc")));
        AnalyticsService aliasService = new AnalyticsService(
                calculatorRunRepository,
                cacheService,
                calculatorProfileService,
                new com.company.observability.config.SlaProperties(),
                new CalculatorNameResolver(props)
        );

        when(cacheService.getFromCache(any(), eq("portfolio"), any(), anyInt(), any(), any(), any()))
                .thenReturn(null);
        when(calculatorRunRepository.findRunsByName(eq("portfoliocalc"), any(), anyInt(), any(), any(LocalDate.class)))
                .thenReturn(List.of());

        aliasService.getRunExecutionsByName("portfolio", 30, Frequency.DAILY, null, LocalDate.now());

        verify(calculatorRunRepository).findRunsByName(eq("portfoliocalc"), any(), anyInt(), any(), any(LocalDate.class));
        verify(calculatorRunRepository, never()).findRunsByName(eq("portfolio"), any(), anyInt(), any(), any(LocalDate.class));
        verify(cacheService).putInCache(eq("executions"), eq("portfolio"), any(), anyInt(), any(), any(LocalDate.class), any());
    }

    // ── reference line resolution ──────────────────────────────────────────────

    @Test
    void getRunExecutionsByName_referenceLinesUsesStoredSlaTimeWhenPresent() {
        LocalDate day = LocalDate.of(2026, 5, 11);
        Instant start = Instant.parse("2026-05-11T05:00:00Z");
        Instant end = Instant.parse("2026-05-11T05:30:00Z");
        Instant runSla = Instant.parse("2026-05-11T06:30:00Z");

        RunWithSlaStatus run = new RunWithSlaStatus(
                "run-1", "calc-1", "Portfolio", day,
                start, end, 1_800_000L,
                runSla, start, Frequency.DAILY,
                RunStatus.SUCCESS, null, null, null, "1", 300000L);

        when(cacheService.getFromCache(any(), any(), any(), anyInt(), any(), any(), any()))
                .thenReturn(null);
        when(calculatorRunRepository.findRunsByName(
                eq("Portfolio"), eq(Frequency.DAILY), eq(30), isNull(), any(LocalDate.class)))
                .thenReturn(List.of(run));
        // avg start = 270 min UTC (04:30); 10 samples → trusted.
        when(calculatorProfileService.getProfile("Portfolio", Frequency.DAILY))
                .thenReturn(new com.company.observability.domain.CalculatorProfile(
                        "Portfolio", "DAILY", null, null, 3_600_000L, 270, 330, 10));

        RunPerformanceData result = service.getRunExecutionsByName("Portfolio", 30, Frequency.DAILY, null, LocalDate.now());

        Instant expectedStart = Instant.parse("2026-05-11T04:30:00Z");
        assertEquals(expectedStart, result.estimatedStartTime());
        assertEquals(runSla, result.slaTime());
    }

    @Test
    void getRunExecutionsByName_referenceLinesBuffersProfileAverageWhenNoFrozenDeadline() {
        LocalDate day = LocalDate.of(2026, 5, 11);
        Instant start = Instant.parse("2026-05-11T05:00:00Z");
        Instant end = Instant.parse("2026-05-11T05:30:00Z");

        RunWithSlaStatus run = new RunWithSlaStatus(
                "run-1", "calc-1", "Portfolio", day,
                start, end, 1_800_000L,
                null, start, Frequency.DAILY,
                RunStatus.SUCCESS, null, null, null, "1", 300000L);

        when(cacheService.getFromCache(any(), any(), any(), anyInt(), any(), any(), any()))
                .thenReturn(null);
        when(calculatorRunRepository.findRunsByName(
                eq("Portfolio"), eq(Frequency.DAILY), eq(30), isNull(), any(LocalDate.class)))
                .thenReturn(List.of(run));
        when(calculatorProfileService.getProfile("Portfolio", Frequency.DAILY))
                .thenReturn(new com.company.observability.domain.CalculatorProfile(
                        "Portfolio", "DAILY", null, null, 3_600_000L, 270, 330, 10));

        RunPerformanceData result = service.getRunExecutionsByName("Portfolio", 30, Frequency.DAILY, null, LocalDate.now());

        // estStart = day @ 04:30 UTC; buffered = 3_600_000*1.2 + 15m = 72m + 15m = 87m
        Instant expectedStart = Instant.parse("2026-05-11T04:30:00Z");
        assertEquals(expectedStart, result.estimatedStartTime());
        assertEquals(expectedStart.plusMillis(87L * 60 * 1000), result.slaTime());
    }

    @Test
    void getRunExecutionsByName_emptyRuns_returnsZeroedResponse() {
        when(cacheService.getFromCache(any(), any(), any(), anyInt(), any(), any(), any()))
                .thenReturn(null);
        when(calculatorRunRepository.findRunsByName(any(), any(), anyInt(), any(), any(LocalDate.class)))
                .thenReturn(List.of());

        RunPerformanceData result = service.getRunExecutionsByName("cap", 7, Frequency.DAILY, null, LocalDate.now());

        assertEquals("cap", result.calculatorId());
        assertNull(result.calculatorName());
        assertEquals(0, result.totalRuns());
        assertTrue(result.runs().isEmpty());
        assertNull(result.estimatedStartTime());
        assertNull(result.slaTime());
    }

    @Test
    void getRunExecutionsByName_splitRunsAppearAsIndependentRows() {
        LocalDate day = LocalDate.of(2026, 5, 11);
        Instant start1 = Instant.parse("2026-05-11T03:59:50Z");
        Instant end1 = Instant.parse("2026-05-11T04:08:10Z");
        Instant start2 = Instant.parse("2026-05-11T04:00:05Z");
        Instant end2 = Instant.parse("2026-05-11T04:15:45Z");
        Instant slaTime = Instant.parse("2026-05-11T06:30:00Z");

        RunWithSlaStatus split1 = new RunWithSlaStatus(
                "run-split-1", "calc-1", "Portfolio", day,
                start1, end1, 500000L,
                slaTime, start1, Frequency.DAILY,
                RunStatus.SUCCESS, null, null, "corr-1", "1", 300000L);

        RunWithSlaStatus split2 = new RunWithSlaStatus(
                "run-split-2", "calc-1", "Portfolio", day,
                start2, end2, 940000L,
                slaTime, start2, Frequency.DAILY,
                RunStatus.SUCCESS, SlaBand.VERY_LATE, "Time exceeded", "corr-1", "1", 300000L);

        when(cacheService.getFromCache(any(), any(), any(), anyInt(), any(), any(), any()))
                .thenReturn(null);
        when(calculatorRunRepository.findRunsByName(
                eq("Portfolio"), eq(Frequency.DAILY), eq(30), isNull(), any(LocalDate.class)))
                .thenReturn(List.of(split1, split2));
        when(calculatorProfileService.getProfile("Portfolio", Frequency.DAILY))
                .thenReturn(new com.company.observability.domain.CalculatorProfile(
                        "Portfolio", "DAILY", null, null, 0, 0, 0, 0));

        RunPerformanceData result = service.getRunExecutionsByName("Portfolio", 30, Frequency.DAILY, null, LocalDate.now());

        assertEquals(2, result.runs().size());
        assertNull(result.runs().get(0).subRunIds());
        assertNull(result.runs().get(1).subRunIds());
        assertEquals("run-split-1", result.runs().get(0).runId());
        assertEquals("run-split-2", result.runs().get(1).runId());
        assertEquals("ON_TIME", result.runs().get(0).slaStatus());
        assertEquals("VERY_LATE", result.runs().get(1).slaStatus());
        assertEquals((500000L + 940000L) / 2, result.meanDurationMs());
        assertEquals(1, result.slaMetCount());
        assertEquals(1, result.veryLateCount());
        assertEquals(0, result.runningRuns());
    }
}
