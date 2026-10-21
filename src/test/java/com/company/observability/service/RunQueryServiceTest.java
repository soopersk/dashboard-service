package com.company.observability.service;

import com.company.observability.cache.AnalyticsCacheService;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.domain.enums.SlaBand;
import com.company.observability.dto.response.RunHistoryResponse;
import com.company.observability.repository.CalculatorRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RunQueryServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 20);

    @Mock
    private CalculatorRunRepository runRepository;

    @Mock
    private AnalyticsCacheService cacheService;

    @InjectMocks
    private RunQueryService service;

    @Test
    void cacheHit_returnsCachedWithoutDbRead() {
        RunHistoryResponse cached = new RunHistoryResponse("calc-a", List.of());
        when(cacheService.getRunHistory("calc-a", Frequency.DAILY, DATE, null, 1)).thenReturn(cached);

        assertThat(service.getRunHistory("calc-a", Frequency.DAILY, DATE, null, 1, false)).isSameAs(cached);
        verifyNoInteractions(runRepository);
    }

    @Test
    void cacheMiss_readsDb_mapsOnDemandFields_cachesWithSettledTtl() {
        when(runRepository.findRunHistory("calc-a", null, null, "od-1", 20))
                .thenReturn(List.of(run("r1", RunStatus.SUCCESS,
                        Map.of("odRunId", "od-1", "consRunVersion", 3, "calculatorRunVersion", "7",
                                "region", "stray-internal"))));

        RunHistoryResponse response = service.getRunHistory("calc-a", null, null, "od-1", 20, false);

        assertThat(response.calculatorName()).isEqualTo("calc-a");
        RunHistoryResponse.RunEntry entry = response.runs().get(0);
        assertThat(entry.runId()).isEqualTo("r1");
        assertThat(entry.status()).isEqualTo("SUCCESS");
        assertThat(entry.slaBand()).isEqualTo("ON_TIME");
        assertThat(entry.odRunId()).isEqualTo("od-1");
        assertThat(entry.consRunVersion()).isEqualTo("3");        // numeric → string
        assertThat(entry.calculatorRunVersion()).isEqualTo("7");
        assertThat(entry.region()).isNull();                        // stray attribute not exposed
        verify(cacheService).putRunHistory("calc-a", null, null, "od-1", 20, response, RunQueryService.SETTLED_TTL);
    }

    @Test
    void scheduledRun_withoutAttributes_mapsOnDemandFieldsToNull() {
        when(runRepository.findRunHistory("calc-a", null, null, null, 20))
                .thenReturn(List.of(run("r1", RunStatus.SUCCESS, null)));

        RunHistoryResponse.RunEntry entry = service.getRunHistory("calc-a", null, null, null, 20, false).runs().get(0);

        assertThat(entry.odRunId()).isNull();
        assertThat(entry.consRunVersion()).isNull();
        assertThat(entry.calculatorRunVersion()).isNull();
    }

    @Test
    void runningOrEmptyResult_cachedWithLiveTtl() {
        when(runRepository.findRunHistory(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(run("r1", RunStatus.RUNNING, null)))
                .thenReturn(List.of());

        RunHistoryResponse running = service.getRunHistory("calc-a", null, null, null, 20, false);
        RunHistoryResponse empty = service.getRunHistory("calc-a", null, null, "od-x", 20, false);

        verify(cacheService).putRunHistory("calc-a", null, null, null, 20, running, RunQueryService.LIVE_TTL);
        verify(cacheService).putRunHistory("calc-a", null, null, "od-x", 20, empty, RunQueryService.LIVE_TTL);
    }

    @Test
    void nocache_skipsCacheReadButRefreshesCache() {
        when(runRepository.findRunHistory("calc-a", null, null, null, 20)).thenReturn(List.of());

        service.getRunHistory("calc-a", null, null, null, 20, true);

        verify(cacheService, never()).getRunHistory(any(), any(), any(), any(), anyInt());
        verify(cacheService).putRunHistory(eq("calc-a"), eq(null), eq(null), eq(null), eq(20), any(), any());
    }

    private static CalculatorRun run(String runId, RunStatus status, Map<String, Object> attrs) {
        return CalculatorRun.builder()
                .runId(runId)
                .calculatorId("id-a")
                .calculatorName("calc-a")
                .frequency(Frequency.DAILY)
                .reportingDate(DATE)
                .startTime(Instant.parse("2026-10-21T05:00:00Z"))
                .status(status)
                .slaBand(SlaBand.ON_TIME)
                .additionalAttributes(attrs)
                .build();
    }
}
