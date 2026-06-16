package com.company.observability.cache;

import com.company.observability.dto.response.CalculatorBatchRunsResponse.CalculatorEntry;
import com.company.observability.dto.response.CalculatorBatchRunsResponse.RunEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static com.company.observability.cache.CalculatorStateCacheService.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CalculatorStateCacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private CalculatorStateCacheService service;
    private ObjectMapper objectMapper;

    private static final LocalDate DATE  = LocalDate.of(2026, 5, 1);   // old reporting date (> 3 days ago)
    private static final LocalDate TODAY = LocalDate.now();            // current cycle
    private static final String    FREQ  = "DAILY";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new CalculatorStateCacheService(redisTemplate, objectMapper, new SimpleMeterRegistry());
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    // ── TTL tiers ─────────────────────────────────────────────────────────────

    @Test
    void determineTtl_emptyRuns_returnsNotStarted() {
        CalculatorEntry entry = new CalculatorEntry("calc", null, List.of());
        assertThat(service.determineTtl(entry, DATE, "DAILY")).isEqualTo(TTL_NOT_STARTED);
    }

    @Test
    void determineTtl_anyRunning_returns30s() {
        CalculatorEntry entry = new CalculatorEntry("calc", null, List.of(runEntry("RUNNING", null)));
        assertThat(service.determineTtl(entry, DATE, "DAILY")).isEqualTo(TTL_ANY_RUNNING);
    }

    @Test
    void determineTtl_slaBreached_returnsTerminalWithFailures() {
        CalculatorEntry entry = new CalculatorEntry("calc", null, List.of(runEntry("SUCCESS", "VERY_LATE")));
        assertThat(service.determineTtl(entry, DATE, "DAILY")).isEqualTo(TTL_TERMINAL_WITH_FAILURES);
    }

    @Test
    void determineTtl_terminalFailure_returnsTerminalWithFailures() {
        CalculatorEntry entry = new CalculatorEntry("calc", null, List.of(runEntry("FAILED", null)));
        assertThat(service.determineTtl(entry, DATE, "DAILY")).isEqualTo(TTL_TERMINAL_WITH_FAILURES);
    }

    @Test
    void determineTtl_cleanSuccessOnOldDate_returns1h() {
        CalculatorEntry entry = new CalculatorEntry("calc", null, List.of(runEntry("SUCCESS", null)));
        assertThat(service.determineTtl(entry, DATE, "DAILY")).isEqualTo(TTL_TERMINAL_CLEAN);
    }

    @Test
    void determineTtl_cleanSuccessOnCurrentDate_returns5min() {
        // Snapshot may still be partial (later regions / re-triggers) on the current cycle → short TTL.
        CalculatorEntry entry = new CalculatorEntry("calc", null, List.of(runEntry("SUCCESS", null)));
        assertThat(service.determineTtl(entry, TODAY, "DAILY")).isEqualTo(TTL_TERMINAL_WITH_FAILURES);
    }

    @Test
    void determineTtl_mixedSuccessAndNotStarted_returns5min() {
        // Not all NOT_STARTED, and not all SUCCESS-clean → short TTL, never the 4h bucket.
        CalculatorEntry entry = new CalculatorEntry("calc", null,
                List.of(runEntry("SUCCESS", null), runEntry("NOT_STARTED", "ON_TIME")));
        assertThat(service.determineTtl(entry, DATE, "DAILY")).isEqualTo(TTL_TERMINAL_WITH_FAILURES);
    }

    @Test
    void determineTtl_cancelledOnOldDate_returns5min() {
        // CANCELLED runs are the most likely to be re-triggered → never the long TTL.
        CalculatorEntry entry = new CalculatorEntry("calc", null, List.of(runEntry("CANCELLED", null)));
        assertThat(service.determineTtl(entry, DATE, "DAILY")).isEqualTo(TTL_TERMINAL_WITH_FAILURES);
    }

    // ── get/put round-trip ────────────────────────────────────────────────────

    @Test
    void putEntries_storesEachWithDynamicTtl() throws Exception {
        CalculatorEntry entry = new CalculatorEntry("cap", null, List.of(runEntry("SUCCESS", null)));

        service.putEntries(DATE, FREQ, null, Map.of("cap", entry));

        String expectedKey = "obs:state:cap:" + DATE + ":DAILY:all";
        verify(valueOps).set(eq(expectedKey), anyString(), eq(TTL_TERMINAL_CLEAN));
    }

    @Test
    void putEntries_withRunNumber_includesRunNumberInKey() throws Exception {
        CalculatorEntry entry = new CalculatorEntry("cap", null, List.of(runEntry("SUCCESS", null)));

        service.putEntries(DATE, FREQ, "1", Map.of("cap", entry));

        String expectedKey = "obs:state:cap:" + DATE + ":DAILY:1";
        verify(valueOps).set(eq(expectedKey), anyString(), eq(TTL_TERMINAL_CLEAN));
    }

    @Test
    void getEntries_cacheHit_returnsEntry() throws Exception {
        String key = "obs:state:cap:" + DATE + ":DAILY:all";
        CalculatorEntry entry = new CalculatorEntry("cap", null, List.of());
        when(valueOps.get(key)).thenReturn(objectMapper.writeValueAsString(entry));

        Map<String, CalculatorEntry> result = service.getEntries(DATE, FREQ, null, List.of("cap"));

        assertThat(result).containsKey("cap");
    }

    @Test
    void getEntries_cacheMiss_returnsEmptyMap() {
        when(valueOps.get(anyString())).thenReturn(null);

        Map<String, CalculatorEntry> result = service.getEntries(DATE, FREQ, null, List.of("cap"));

        assertThat(result).isEmpty();
    }

    @Test
    void getEntries_redisFailure_swallowedAndReturnsMiss() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("Redis down"));

        // Should not throw — best-effort
        Map<String, CalculatorEntry> result = service.getEntries(DATE, FREQ, null, List.of("cap"));

        assertThat(result).isEmpty();
    }

    @Test
    void putEntries_redisFailure_swallowed() {
        CalculatorEntry entry = new CalculatorEntry("cap", null, List.of(runEntry("SUCCESS", null)));
        doThrow(new RuntimeException("Redis down"))
                .when(valueOps).set(anyString(), any(), any(Duration.class));

        // Should not throw — best-effort
        service.putEntries(DATE, FREQ, null, Map.of("cap", entry));
    }

    // ── Eviction ──────────────────────────────────────────────────────────────

    @Test
    void determineTtl_cleanSuccessMonthly_lastMonth_returns5min() {
        LocalDate lastMonthEnd = YearMonth.now().minusMonths(1).atEndOfMonth();
        CalculatorEntry entry = new CalculatorEntry("calc", null, List.of(runEntry("SUCCESS", null)));
        assertThat(service.determineTtl(entry, lastMonthEnd, "MONTHLY"))
                .isEqualTo(TTL_TERMINAL_WITH_FAILURES);
    }

    @Test
    void determineTtl_cleanSuccessMonthly_twoMonthsAgo_returns1h() {
        LocalDate twoMonthsAgoEnd = YearMonth.now().minusMonths(2).atEndOfMonth();
        CalculatorEntry entry = new CalculatorEntry("calc", null, List.of(runEntry("SUCCESS", null)));
        assertThat(service.determineTtl(entry, twoMonthsAgoEnd, "MONTHLY"))
                .isEqualTo(TTL_TERMINAL_CLEAN);
    }

    @Test
    void evictEntry_withRunNumber_deletesBothKeys() {
        when(redisTemplate.delete(anyList())).thenReturn(2L);
        service.evictEntry("cap", DATE, FREQ, "1");

        verify(redisTemplate).delete(List.of(
                "obs:state:cap:" + DATE + ":DAILY:all",
                "obs:state:cap:" + DATE + ":DAILY:1"));
    }

    @Test
    void evictEntry_nullRunNumber_deletesOnlyAllKey() {
        service.evictEntry("cap", DATE, FREQ, null);

        verify(redisTemplate).delete(List.of("obs:state:cap:" + DATE + ":DAILY:all"));
    }

    @Test
    void evictEntry_redisFailure_swallowed() {
        doThrow(new RuntimeException("Redis down")).when(redisTemplate).delete(anyList());

        // Should not throw — best-effort
        service.evictEntry("cap", DATE, FREQ, "1");
    }

    @Test
    void evictEntry_redisFailure_incrementsFailureCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        service = new CalculatorStateCacheService(redisTemplate, objectMapper, registry);
        doThrow(new RuntimeException("Redis down")).when(redisTemplate).delete(anyList());

        service.evictEntry("cap", DATE, FREQ, "1");

        assertThat(registry.counter("obs.cache.state.eviction.failure",
                "calculator", "cap").count()).isEqualTo(1.0);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private RunEntry runEntry(String status, String slaStatus) {
        return RunEntry.builder()
                .runId("r-1")
                .status(status)
                .slaStatus(slaStatus)
                .isRerun(false)
                .build();
    }
}
