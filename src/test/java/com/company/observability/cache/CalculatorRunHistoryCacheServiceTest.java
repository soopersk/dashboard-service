package com.company.observability.cache;

import com.company.observability.config.RunHistoryCacheProperties;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.dto.response.RunHistoryResponse;
import com.company.observability.event.RunStartedEvent;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.company.observability.util.ObservabilityConstants.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)   // ops.get(...) is stubbed per key; other keys must return null
class CalculatorRunHistoryCacheServiceTest {

    private static final String GEN_KEY = "obs:calculator-runs:v2:g:calc-a";
    private static final String G1 = "000000000000a001";
    private static final String KEY_G1 = "obs:calculator-runs:v2:r:calc-a:" + G1 + ":all:all:20:od:none";

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> ops;

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final RunHistoryCacheProperties properties = new RunHistoryCacheProperties();
    private CalculatorRunHistoryCacheService cache;

    @BeforeEach
    void setUp() {
        cache = new CalculatorRunHistoryCacheService(redisTemplate, objectMapper, meterRegistry, properties);
        lenient().when(redisTemplate.opsForValue()).thenReturn(ops);
    }

    // ---------------------------------------------------------------- keys

    @Test
    void snapshotKey_format() {
        assertThat(CalculatorRunHistoryCacheService.snapshotKey("capitalcalc", "abc", LocalDate.of(2026, 10, 20),
                Frequency.DAILY, 1, null))
                .isEqualTo("obs:calculator-runs:v2:r:capitalcalc:abc:2026-10-20:DAILY:1:od:none");
        assertThat(CalculatorRunHistoryCacheService.snapshotKey("capitalcalc", "abc", null, null, 20, "od-123"))
                .isEqualTo("obs:calculator-runs:v2:r:capitalcalc:abc:all:all:20:od:value:od-123");
    }

    @Test
    void snapshotKey_absentOdRunId_neverCollidesWithLiteralValues() {
        Set<String> keys = Set.of(
                CalculatorRunHistoryCacheService.snapshotKey("c", "g", null, null, 20, null),
                CalculatorRunHistoryCacheService.snapshotKey("c", "g", null, null, 20, "none"),
                CalculatorRunHistoryCacheService.snapshotKey("c", "g", null, null, 20, "all"),
                CalculatorRunHistoryCacheService.snapshotKey("c", "g", null, null, 20, "od:none"));
        assertThat(keys).hasSize(4);
    }

    @Test
    void snapshotKey_everyDimensionParticipates() {
        LocalDate d = LocalDate.of(2026, 10, 20);
        Set<String> keys = Set.of(
                CalculatorRunHistoryCacheService.snapshotKey("c", "g1", d, Frequency.DAILY, 20, null),
                CalculatorRunHistoryCacheService.snapshotKey("c", "g2", d, Frequency.DAILY, 20, null),            // generation
                CalculatorRunHistoryCacheService.snapshotKey("c", "g1", d.minusDays(1), Frequency.DAILY, 20, null), // date
                CalculatorRunHistoryCacheService.snapshotKey("c", "g1", null, Frequency.DAILY, 20, null),         // date absent
                CalculatorRunHistoryCacheService.snapshotKey("c", "g1", d, Frequency.MONTHLY, 20, null),          // frequency
                CalculatorRunHistoryCacheService.snapshotKey("c", "g1", d, null, 20, null),                       // frequency absent
                CalculatorRunHistoryCacheService.snapshotKey("c", "g1", d, Frequency.DAILY, 21, null),            // limit
                CalculatorRunHistoryCacheService.snapshotKey("c2", "g1", d, Frequency.DAILY, 20, null));          // scope
        assertThat(keys).hasSize(8);
    }

    @Test
    void snapshotKey_colonInOdRunId_cannotShiftFixedSegments() {
        assertThat(CalculatorRunHistoryCacheService.snapshotKey("c", "g", null, null, 5, "a:20"))
                .isNotEqualTo(CalculatorRunHistoryCacheService.snapshotKey("c", "g", null, null, 20, "a"));
    }

    @Test
    void scope_distinctNamesStayDistinct() {
        Set<String> scopes = Set.of(
                CalculatorRunHistoryCacheService.scope("a b"),
                CalculatorRunHistoryCacheService.scope("a+b"),
                CalculatorRunHistoryCacheService.scope("a:b"),
                CalculatorRunHistoryCacheService.scope("a%3Ab"),
                CalculatorRunHistoryCacheService.scope("a%b"));
        assertThat(scopes).hasSize(5).allSatisfy(scope -> assertThat(scope).doesNotContain(":"));
    }

    @Test
    void scope_encodesNameSoItCannotImpersonateSegments() {
        assertThat(CalculatorRunHistoryCacheService.scope("capitalcalc")).isEqualTo("capitalcalc");
        assertThat(CalculatorRunHistoryCacheService.scope("a:b%c d+e")).isEqualTo("a%3Ab%25c+d%2Be");
    }

    // ---------------------------------------------------------------- read path

    @Test
    void hit_returnsSnapshot_withoutLoading() throws Exception {
        when(ops.get(GEN_KEY)).thenReturn(G1);
        when(ops.get(KEY_G1)).thenReturn(objectMapper.writeValueAsString(response("SUCCESS")));

        RunHistoryResponse result = cache.getOrLoad("calc-a", null, null, null, 20, false, failingLoader());

        assertThat(result.runs()).extracting(RunHistoryResponse.RunEntry::status).containsExactly("SUCCESS");
        assertThat(count(CACHE_HISTORY_LOOKUP, "hit")).isEqualTo(1);
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void miss_allTerminal_loadsAndWritesWithTerminalTtl() {
        when(ops.get(GEN_KEY)).thenReturn(G1);
        RunHistoryResponse loaded = response("SUCCESS");

        assertThat(cache.getOrLoad("calc-a", null, null, null, 20, false, () -> loaded)).isSameAs(loaded);

        verify(ops).set(eq(KEY_G1), anyString(), eq(Duration.ofMinutes(5)));
        assertThat(count(CACHE_HISTORY_LOOKUP, "miss")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "FAILED", "TIMEOUT", "CANCELLED"})
    void miss_eachTerminalStatus_writesWithTerminalTtl(String status) {
        when(ops.get(GEN_KEY)).thenReturn(G1);

        cache.getOrLoad("calc-a", null, null, null, 20, false, () -> response(status));

        verify(ops).set(eq(KEY_G1), anyString(), eq(Duration.ofMinutes(5)));
    }

    @Test
    void miss_emptyOrRunning_writesWithTransientTtl() {
        when(ops.get(GEN_KEY)).thenReturn(G1);

        cache.getOrLoad("calc-a", null, null, null, 20, false, () -> new RunHistoryResponse("calc-a", List.of()));
        cache.getOrLoad("calc-a", null, null, "od-1", 20, false, () -> response("RUNNING"));

        verify(ops).set(eq(KEY_G1), anyString(), eq(Duration.ofSeconds(10)));
        verify(ops).set(eq("obs:calculator-runs:v2:r:calc-a:" + G1 + ":all:all:20:od:value:od-1"),
                anyString(), eq(Duration.ofSeconds(10)));
    }

    @Test
    void missingGeneration_seedsTokenWithNx_andUsesIt() {
        when(ops.get(GEN_KEY)).thenReturn(null);
        when(ops.setIfAbsent(eq(GEN_KEY), anyString(), eq(Duration.ofMinutes(15)))).thenReturn(true);

        cache.getOrLoad("calc-a", null, null, null, 20, false, () -> response("SUCCESS"));

        verify(ops).set(matches("obs:calculator-runs:v2:r:calc-a:[0-9a-f]{16}:all:all:20:od:none"),
                anyString(), any(Duration.class));
    }

    @Test
    void lostSeedingRace_usesWinnersToken() {
        when(ops.get(GEN_KEY)).thenReturn(null, G1);
        when(ops.setIfAbsent(eq(GEN_KEY), anyString(), any(Duration.class))).thenReturn(false);

        cache.getOrLoad("calc-a", null, null, null, 20, false, () -> response("SUCCESS"));

        verify(ops).set(eq(KEY_G1), anyString(), any(Duration.class));
    }

    @Test
    void tokenVanishesAfterLostSeed_servesFromDb_withoutWriting() {
        when(ops.get(GEN_KEY)).thenReturn(null);
        when(ops.setIfAbsent(eq(GEN_KEY), anyString(), any(Duration.class))).thenReturn(false);
        RunHistoryResponse loaded = response("SUCCESS");

        assertThat(cache.getOrLoad("calc-a", null, null, null, 20, false, () -> loaded)).isSameAs(loaded);

        verify(ops, times(2)).get(GEN_KEY);
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void generationReplacedDuringLoad_writeStaysOnCapturedGeneration() {
        when(ops.get(GEN_KEY)).thenReturn(G1);

        cache.getOrLoad("calc-a", null, null, null, 20, false, () -> {
            cache.invalidate("calc-a", "run_started");   // commit + invalidation land mid-query
            return response("SUCCESS");
        });

        verify(ops).set(eq(GEN_KEY), argThat(token -> !token.equals(G1)), eq(Duration.ofMinutes(15)));
        verify(ops).set(eq(KEY_G1), anyString(), any(Duration.class));   // dead generation — unreachable
    }

    @Test
    void nocache_skipsSnapshotRead_butOverwritesSnapshot() {
        when(ops.get(GEN_KEY)).thenReturn(G1);

        cache.getOrLoad("calc-a", null, null, null, 20, true, () -> response("SUCCESS"));

        verify(ops, never()).get(KEY_G1);
        verify(ops).set(eq(KEY_G1), anyString(), any(Duration.class));
        assertThat(count(CACHE_HISTORY_LOOKUP, "bypass")).isEqualTo(1);
    }

    @Test
    void corruptSnapshot_isMiss_overwrittenNotDeleted() {
        when(ops.get(GEN_KEY)).thenReturn(G1);
        when(ops.get(KEY_G1)).thenReturn("{not-json");

        cache.getOrLoad("calc-a", null, null, null, 20, false, () -> response("SUCCESS"));

        verify(redisTemplate, never()).delete(anyString());
        verify(ops).set(eq(KEY_G1), anyString(), any(Duration.class));
        assertThat(count(CACHE_HISTORY_LOOKUP, "corrupt")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null",                                                                    // JSON null
            "{\"calculatorName\":\"calc-a\"}",                                          // runs missing
            "{\"calculatorName\":\"calc-a\",\"runs\":[null]}",                          // null entry (NPE in anyLive)
            "{\"calculatorName\":\"calc-a\",\"runs\":[{}]}",                            // no identity/status (would be 'settled')
            "{\"runs\":[]}",                                                             // calculator name missing
            "{\"calculatorName\":\"calc-b\",\"runs\":[]}",                              // another calculator's snapshot
            "{\"calculatorName\":\"calc-a\",\"runs\":[{\"status\":\"SUCCESS\"}]}",      // runId missing
            "{\"calculatorName\":\"calc-a\",\"runs\":[{\"runId\":\"r1\",\"status\":\"PAUSED\"}]}" // unknown status
    })
    void invalidSnapshot_isCorrupt_andLoadsFromDb(String json) {
        when(ops.get(GEN_KEY)).thenReturn(G1);
        when(ops.get(KEY_G1)).thenReturn(json);
        AtomicInteger loads = new AtomicInteger();

        RunHistoryResponse result = cache.getOrLoad("calc-a", null, null, null, 20, false,
                () -> { loads.incrementAndGet(); return response("SUCCESS"); });

        assertThat(loads).hasValue(1);
        assertThat(result.runs()).extracting(RunHistoryResponse.RunEntry::status).containsExactly("SUCCESS");
        assertThat(count(CACHE_HISTORY_LOOKUP, "corrupt")).isEqualTo(1);
        assertThat(count(CACHE_HISTORY_LOOKUP, "hit")).isZero();
        verify(ops).set(eq(KEY_G1), anyString(), any(Duration.class));   // overwritten with the DB result
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"calculatorName\":\"calc-a\",\"runs\":[]}",                                       // empty is valid
            "{\"calculatorName\":\"calc-a\",\"runs\":[{\"runId\":\"r1\",\"status\":\"RUNNING\"}]}" // display fields optional
    })
    void minimalValidSnapshot_isHit(String json) {
        when(ops.get(GEN_KEY)).thenReturn(G1);
        when(ops.get(KEY_G1)).thenReturn(json);

        cache.getOrLoad("calc-a", null, null, null, 20, false, failingLoader());

        assertThat(count(CACHE_HISTORY_LOOKUP, "hit")).isEqualTo(1);
    }

    @Test
    void generationReadFailure_servesFromDb_withoutWriting() {
        when(ops.get(GEN_KEY)).thenThrow(new RedisConnectionFailureException("down"));
        RunHistoryResponse loaded = response("SUCCESS");

        assertThat(cache.getOrLoad("calc-a", null, null, null, 20, false, () -> loaded)).isSameAs(loaded);

        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
        assertThat(count(CACHE_HISTORY_LOOKUP, "error")).isEqualTo(1);
    }

    @Test
    void snapshotReadFailure_servesFromDb() {
        when(ops.get(GEN_KEY)).thenReturn(G1);
        when(ops.get(KEY_G1)).thenThrow(new RedisConnectionFailureException("down"));
        RunHistoryResponse loaded = response("SUCCESS");

        assertThat(cache.getOrLoad("calc-a", null, null, null, 20, false, () -> loaded)).isSameAs(loaded);
        assertThat(count(CACHE_HISTORY_LOOKUP, "error")).isEqualTo(1);
    }

    @Test
    void writeFailure_stillReturnsResponse() {
        when(ops.get(GEN_KEY)).thenReturn(G1);
        doThrow(new RedisConnectionFailureException("down")).when(ops).set(eq(KEY_G1), anyString(), any(Duration.class));
        RunHistoryResponse loaded = response("SUCCESS");

        assertThat(cache.getOrLoad("calc-a", null, null, null, 20, false, () -> loaded)).isSameAs(loaded);
        assertThat(count(CACHE_HISTORY_WRITE, "error")).isEqualTo(1);
    }

    @Test
    void snapshot_roundTripsAllFields_includingExplicitNulls() {
        when(ops.get(GEN_KEY)).thenReturn(G1);
        RunHistoryResponse full = new RunHistoryResponse("calc-a", List.of(
                new RunHistoryResponse.RunEntry("r1", "id-a", "MONTHLY", LocalDate.of(2026, 9, 30), "2", "WMAP", "FULL",
                        "FAILED", Instant.parse("2026-10-01T05:00:00.123Z"), Instant.parse("2026-10-01T06:30:00Z"),
                        5_400_000L, 3_600_000L, Instant.parse("2026-10-01T06:00:00Z"), "VERY_LATE", true,
                        "od-1", "3", "7"),
                new RunHistoryResponse.RunEntry("r2", "id-a", "DAILY", LocalDate.of(2026, 9, 29), null, null, null,
                        "SUCCESS", Instant.parse("2026-09-30T05:00:00Z"), null, null, null, null, null, false,
                        null, null, null)));
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);

        cache.getOrLoad("calc-a", null, null, null, 20, false, () -> full);
        verify(ops).set(eq(KEY_G1), json.capture(), any(Duration.class));
        when(ops.get(KEY_G1)).thenReturn(json.getValue());

        assertThat(cache.getOrLoad("calc-a", null, null, null, 20, false, failingLoader())).isEqualTo(full);
        assertThat(count(CACHE_HISTORY_LOOKUP, "hit")).isEqualTo(1);
    }

    @Test
    void serializationFailureOnWrite_returnsResponse_andIsMetered() throws Exception {
        ObjectMapper failingMapper = spy(objectMapper);
        doThrow(new JsonMappingException(null, "boom")).when(failingMapper).writeValueAsString(any());
        cache = new CalculatorRunHistoryCacheService(redisTemplate, failingMapper, meterRegistry, properties);
        when(ops.get(GEN_KEY)).thenReturn(G1);
        RunHistoryResponse loaded = response("SUCCESS");

        assertThat(cache.getOrLoad("calc-a", null, null, null, 20, false, () -> loaded)).isSameAs(loaded);

        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
        assertThat(count(CACHE_HISTORY_WRITE, "error")).isEqualTo(1);
    }

    @Test
    void loaderFailure_propagates_andNothingIsWritten() {
        when(ops.get(GEN_KEY)).thenReturn(G1);

        assertThatThrownBy(() -> cache.getOrLoad("calc-a", null, null, null, 20, false, failingLoader()))
                .isInstanceOf(IllegalStateException.class);
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void disabled_loadsFromDb_withNoRedisIo() {
        properties.setEnabled(false);
        RunHistoryResponse loaded = response("SUCCESS");

        assertThat(cache.getOrLoad("calc-a", null, null, null, 20, false, () -> loaded)).isSameAs(loaded);
        verifyNoInteractions(redisTemplate);
    }

    // ---------------------------------------------------------------- invalidation

    @Test
    void onRunStarted_replacesGenerationWithFreshToken() {
        cache.onRunStarted(new RunStartedEvent(CalculatorRun.builder().calculatorName("calc-a").build()));

        verify(ops).set(eq(GEN_KEY), matches("[0-9a-f]{16}"), eq(Duration.ofMinutes(15)));
        assertThat(count(CACHE_HISTORY_INVALIDATION, "success")).isEqualTo(1);
    }

    @Test
    void invalidate_encodesScope() {
        cache.invalidate("a:b", "run_completed");
        verify(ops).set(eq("obs:calculator-runs:v2:g:a%3Ab"), anyString(), any(Duration.class));
    }

    @Test
    void invalidate_runsEvenWhenDisabled() {
        properties.setEnabled(false);
        cache.invalidate("calc-a", "run_completed");
        verify(ops).set(eq(GEN_KEY), anyString(), any(Duration.class));
    }

    @Test
    void invalidateFailure_isSwallowedAndMetered() {
        doThrow(new RedisConnectionFailureException("down")).when(ops).set(eq(GEN_KEY), anyString(), any(Duration.class));

        cache.invalidate("calc-a", "sla_breached");

        assertThat(count(CACHE_HISTORY_INVALIDATION, "error")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- fixtures

    private double count(String name, String outcome) {
        var counter = meterRegistry.find(name).tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private static java.util.function.Supplier<RunHistoryResponse> failingLoader() {
        return () -> { throw new IllegalStateException("loader must not be called"); };
    }

    private static RunHistoryResponse response(String status) {
        return new RunHistoryResponse("calc-a", List.of(new RunHistoryResponse.RunEntry(
                "r1", "id-a", "DAILY", LocalDate.of(2026, 10, 20), "1", null, null, status,
                Instant.parse("2026-10-21T05:00:00Z"), null, null, 60000L,
                Instant.parse("2026-10-21T07:00:00Z"), null, false,
                "od-1", "3", "7")));
    }
}
