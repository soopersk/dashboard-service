package com.company.observability.cache;

import com.company.observability.config.CalculatorProperties;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.dto.response.RunHistoryResponse;
import com.company.observability.dto.response.RunPerformanceData;
import com.company.observability.dto.response.RunPerformanceData.RunDataPoint;
import com.company.observability.event.RunCompletedEvent;
import com.company.observability.event.RunStartedEvent;
import com.company.observability.event.SlaBreachedEvent;
import com.company.observability.domain.SlaEvaluationResult;
import com.company.observability.service.CalculatorNameResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnalyticsCacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private SetOperations<String, String> setOperations;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private AnalyticsCacheService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new AnalyticsCacheService(redisTemplate, objectMapper, new SimpleMeterRegistry(),
                new CalculatorNameResolver(new CalculatorProperties()));
        // lenient: used by eviction tests only — getFromCache/putInCache tests don't need opsForSet from setUp
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOperations);
    }

    @Test
    void onRunStarted_evictsExecutionsAndHistoryKeys_fromNameIndex() {
        CalculatorRun run = run("calc-1", "tenant-a");
        String nameIndex = "obs:analytics:index:Calculator";
        Set<String> nameKeys = new LinkedHashSet<>(List.of(
                "obs:analytics:executions:Calculator:DAILY:30:all",
                "obs:analytics:history:Calculator:all:all:20:od:value:od-1"
        ));

        when(setOperations.members(nameIndex)).thenReturn(nameKeys);

        service.onRunStarted(new RunStartedEvent(run));

        // a new run changes both executions and run-history results
        verify(redisTemplate).delete(List.of(
                "obs:analytics:executions:Calculator:DAILY:30:all",
                "obs:analytics:history:Calculator:all:all:20:od:value:od-1",
                nameIndex
        ));
    }

    @Test
    void putRunHistory_writesKeyWithTtl_andTracksInNameIndex() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        RunHistoryResponse response = new RunHistoryResponse("Calculator", List.of());

        service.putRunHistory("Calculator", Frequency.MONTHLY, LocalDate.of(2026, 9, 30), null, 1,
                response, Duration.ofSeconds(30));

        String key = "obs:analytics:history:Calculator:MONTHLY:2026-09-30:1:od:none";
        verify(valueOperations).set(eq(key), anyString(), eq(Duration.ofSeconds(30)));
        verify(setOperations).add("obs:analytics:index:Calculator", key);
    }

    @Test
    void runHistoryKey_absentOdRunId_differsFromLiteralAll() {
        String absent = AnalyticsCacheService.buildRunHistoryKey("calc", null, null, null, 20);
        String literalAll = AnalyticsCacheService.buildRunHistoryKey("calc", null, null, "all", 20);
        String literalNone = AnalyticsCacheService.buildRunHistoryKey("calc", null, null, "none", 20);

        assertThat(absent).isEqualTo("obs:analytics:history:calc:all:all:20:od:none");
        assertThat(literalAll).isEqualTo("obs:analytics:history:calc:all:all:20:od:value:all");
        assertThat(Set.of(absent, literalAll, literalNone)).hasSize(3);
        // a ':' inside the id can't shift the fixed segments (od segment is last)
        assertThat(AnalyticsCacheService.buildRunHistoryKey("calc", null, null, "a:20", 5))
                .isNotEqualTo(AnalyticsCacheService.buildRunHistoryKey("calc", null, null, "a", 20));
    }

    @Test
    void runHistoryKey_encodesCalculatorName() {
        assertThat(AnalyticsCacheService.buildRunHistoryKey("capitalcalc", null, null, null, 20))
                .isEqualTo("obs:analytics:history:capitalcalc:all:all:20:od:none");
        assertThat(AnalyticsCacheService.buildRunHistoryKey("a:b%c", null, null, null, 20))
                .isEqualTo("obs:analytics:history:a%3Ab%25c:all:all:20:od:none");
        // unencoded, name "x:all:all:20:od:value:y" + no odRunId would equal name "x" + odRunId ... collision class
        String impersonating = AnalyticsCacheService.buildRunHistoryKey("x:all:all:20:od:value:y", null, null, null, 20);
        String genuine = AnalyticsCacheService.buildRunHistoryKey("x", null, null, "y:all:all:20:od:none", 20);
        assertThat(impersonating).isNotEqualTo(genuine);
    }

    @Test
    void getRunHistory_roundTripsOnDemandFields() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        RunHistoryResponse.RunEntry entry = new RunHistoryResponse.RunEntry(
                "r1", "id-1", "DAILY", LocalDate.of(2026, 10, 20), "1", null, null, "RUNNING",
                Instant.parse("2026-10-21T05:00:00Z"), null, null, 60000L,
                Instant.parse("2026-10-21T07:00:00Z"), null, false,
                "od-1", "3", null);
        String key = "obs:analytics:history:Calculator:all:all:20:od:value:od-1";
        when(valueOperations.get(key))
                .thenReturn(objectMapper.writeValueAsString(new RunHistoryResponse("Calculator", List.of(entry))));

        RunHistoryResponse cached = service.getRunHistory("Calculator", null, null, "od-1", 20);

        assertThat(cached.runs()).containsExactly(entry);
    }

    @Test
    void onRunCompleted_evictsAllKeys_fromNameIndex() {
        CalculatorRun run = run("calc-1", "tenant-a");
        String nameIndex = "obs:analytics:index:Calculator";
        Set<String> nameKeys = new LinkedHashSet<>(List.of(
                "obs:analytics:executions:Calculator:DAILY:30:all"
        ));

        when(setOperations.members(nameIndex)).thenReturn(nameKeys);

        service.onRunCompleted(new RunCompletedEvent(run));

        // name-index: all keys + the index itself deleted
        verify(redisTemplate).delete(List.of(
                "obs:analytics:executions:Calculator:DAILY:30:all",
                nameIndex
        ));
    }

    @Test
    void onSlaBreached_evictsAllKeys_fromNameIndex() {
        CalculatorRun run = run("calc-1", "tenant-a");
        String nameIndex = "obs:analytics:index:Calculator";
        Set<String> nameKeys = new LinkedHashSet<>(List.of("obs:analytics:executions:Calculator:DAILY:30:all"));

        when(setOperations.members(nameIndex)).thenReturn(nameKeys);

        service.onSlaBreached(new SlaBreachedEvent(run, new SlaEvaluationResult(com.company.observability.domain.enums.SlaBand.LATE, "b")));

        verify(redisTemplate).delete(List.of("obs:analytics:executions:Calculator:DAILY:30:all", nameIndex));
    }

    // ---------------------------------------------------------------
    // Executions round-trip — the regression this fix addresses.
    // A record with a populated List<record> field must survive put→get.
    // ---------------------------------------------------------------

    @Test
    void executionsRoundTrip_recordWithPopulatedRunsList_survivesSerialization() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        RunDataPoint dp = new RunDataPoint(
                "run-1", LocalDate.of(2026, 5, 1),
                Instant.parse("2026-05-01T04:00:00Z"), Instant.parse("2026-05-01T05:00:00Z"),
                3_600_000L, "SUCCESS", null, "ON_TIME",
                null, Instant.parse("2026-05-01T04:00:00Z"), Instant.parse("2026-05-01T06:00:00Z"),
                "1", 3_600_000L);
        RunPerformanceData response = new RunPerformanceData(
                "Calc", "Calc", "DAILY", 30, 3_600_000L, 1, 0, 1, 0, 0,
                List.of(dp), Instant.parse("2026-05-01T04:00:00Z"), Instant.parse("2026-05-01T06:00:00Z"));

        LocalDate asOfDate = LocalDate.of(2026, 5, 1);
        String key = "obs:analytics:executions:Calc:DAILY:30:all:" + asOfDate;

        // Capture the JSON the service writes, then feed it back on read — a true round-trip.
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        service.putInCache("executions", "Calc", "DAILY", 30, null, asOfDate, response);
        verify(valueOperations).set(eq(key), jsonCaptor.capture(), eq(Duration.ofMinutes(5)));

        when(valueOperations.get(key)).thenReturn(jsonCaptor.getValue());
        RunPerformanceData result =
                service.getFromCache("executions", "Calc", "DAILY", 30, null, asOfDate, RunPerformanceData.class);

        assertThat(result).isNotNull();
        assertThat(result.runs()).hasSize(1);
        assertThat(result.runs().get(0).runId()).isEqualTo("run-1");
        assertThat(result.runs().get(0).status()).isEqualTo("SUCCESS");
        assertThat(result.runs().get(0).startTime()).isEqualTo(Instant.parse("2026-05-01T04:00:00Z"));
        assertThat(result.totalRuns()).isEqualTo(1);
    }

    // ---------------------------------------------------------------
    // Helpers — eviction tests
    // ---------------------------------------------------------------

    private CalculatorRun run(String calculatorId, String tenantId) {
        return CalculatorRun.builder()
                .runId("run-1")
                .calculatorId(calculatorId)
                .calculatorName("Calculator")
                .tenantId(tenantId)
                .frequency(Frequency.DAILY)
                .reportingDate(LocalDate.of(2026, 4, 5))
                .startTime(Instant.parse("2026-04-05T04:00:00Z"))
                .status(RunStatus.RUNNING)
                .build();
    }
}

