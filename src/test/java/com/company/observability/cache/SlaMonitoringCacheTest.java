package com.company.observability.cache;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.util.TestFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SlaMonitoringCache}.
 *
 * <p>Strategy: Mockito only. Tests verify that the correct Redis commands are
 * issued (or suppressed) based on guard conditions. Guard conditions are
 * exercised via {@code ReflectionTestUtils.setField} for the {@code liveTrackingEnabled}
 * flag and by constructing {@link CalculatorRun} objects with specific states.
 */
@ExtendWith(MockitoExtension.class)
class SlaMonitoringCacheTest {

    private static final String SLA_DEADLINES_ZSET = "obs:sla:deadlines";
    private static final String SLA_RUN_INFO_HASH  = "obs:sla:run_info";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOps;

    @Mock
    private HashOperations<String, String, String> hashOps;

    private SlaMonitoringCache cache;

    @BeforeEach
    void setUp() {
        cache = new SlaMonitoringCache(redisTemplate, new ObjectMapper(), new SimpleMeterRegistry());
        ReflectionTestUtils.setField(cache, "liveTrackingEnabled", true);
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        // doReturn bypasses the compile-time generic mismatch:
        // StringRedisTemplate.opsForHash() declares HashOperations<String,Object,Object>
        // but our mock is <String,String,String> — same wire behaviour, different type token.
        lenient().doReturn(hashOps).when(redisTemplate).opsForHash();
    }

    // ---------------------------------------------------------------
    // registerForSlaMonitoring — guard conditions
    // ---------------------------------------------------------------

    @Test
    void register_whenLiveTrackingDisabled_doesNotWriteToRedis() {
        ReflectionTestUtils.setField(cache, "liveTrackingEnabled", false);
        CalculatorRun run = TestFixtures.aRunningRun();

        cache.registerForSlaMonitoring(run);

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void register_whenSlaTimeNull_doesNotWriteToRedis() {
        CalculatorRun run = CalculatorRun.builder()
                .runId(TestFixtures.DEFAULT_RUN_ID)
                .calculatorId(TestFixtures.DEFAULT_CALC_ID)
                .tenantId(TestFixtures.DEFAULT_TENANT_ID)
                .reportingDate(TestFixtures.DEFAULT_DATE)
                .startTime(TestFixtures.DEFAULT_START)
                .status(RunStatus.RUNNING)
                .slaTime(null)
                .createdAt(TestFixtures.DEFAULT_START)
                .build();

        cache.registerForSlaMonitoring(run);

        verify(zSetOps, never()).add(anyString(), any(), anyDouble());
        verify(hashOps, never()).put(anyString(), anyString(), anyString());
    }

    @Test
    void register_whenStatusNotRunning_doesNotWriteToRedis() {
        CalculatorRun run = TestFixtures.aCompletedRun(); // status = SUCCESS

        cache.registerForSlaMonitoring(run);

        verify(zSetOps, never()).add(anyString(), any(), anyDouble());
        verify(hashOps, never()).put(anyString(), anyString(), anyString());
    }

    // ---------------------------------------------------------------
    // registerForSlaMonitoring — happy path
    // ---------------------------------------------------------------

    @Test
    void register_validRun_writesZsetScoreEqualToSlaTimeMillis() {
        CalculatorRun run = TestFixtures.aRunningRun();
        long expectedScore = run.getSlaTime().toEpochMilli();

        cache.registerForSlaMonitoring(run);

        verify(zSetOps).add(eq(SLA_DEADLINES_ZSET), anyString(), eq((double) expectedScore));
    }

    @Test
    void register_validRun_writesRunInfoHashWithCorrectKey() {
        CalculatorRun run = TestFixtures.aRunningRun();
        // expected key pattern: {tenantId}:{runId}:{reportingDate}
        String expectedField = run.getTenantId() + ":" + run.getRunId() + ":" + run.getReportingDate();

        cache.registerForSlaMonitoring(run);

        verify(hashOps).put(eq(SLA_RUN_INFO_HASH), eq(expectedField), anyString());
    }

    @Test
    void register_validRun_doesNotExpireMonitoringStructures() {
        // No structure-level TTL: a whole-key expiry would silently drop every monitored run
        // (e.g. a long run registered before a quiet weekend). GC is by deregistration.
        CalculatorRun run = TestFixtures.aRunningRun();

        cache.registerForSlaMonitoring(run);

        verify(redisTemplate, org.mockito.Mockito.never()).expire(anyString(), any());
    }

    // ---------------------------------------------------------------
    // deregisterFromSlaMonitoring
    // ---------------------------------------------------------------

    @Test
    void deregister_callsZremAndHdelWithCorrectKey() {
        String runId = TestFixtures.DEFAULT_RUN_ID;
        String tenantId = TestFixtures.DEFAULT_TENANT_ID;
        LocalDate reportingDate = TestFixtures.DEFAULT_DATE;
        String expectedKey = tenantId + ":" + runId + ":" + reportingDate;

        cache.deregisterFromSlaMonitoring(runId, tenantId, reportingDate);

        verify(zSetOps).remove(eq(SLA_DEADLINES_ZSET), eq(expectedKey));
        verify(hashOps).delete(eq(SLA_RUN_INFO_HASH), eq(expectedKey));
    }

    // ---------------------------------------------------------------
    // getBreachedRuns — null hash values are skipped
    // ---------------------------------------------------------------

    @Test
    void getBreachedRuns_nullHashValue_isSkipped() {
        String runKey = "tenant-1:run-orphan:2026-04-10";

        Set<String> breachedKeys = new LinkedHashSet<>(List.of(runKey));
        when(zSetOps.rangeByScore(eq(SLA_DEADLINES_ZSET), eq(0.0), anyDouble()))
                .thenReturn(breachedKeys);
        // HGET returns null — orphaned entry (run already cleaned up from hash)
        when(hashOps.get(eq(SLA_RUN_INFO_HASH), eq(runKey))).thenReturn(null);

        List<Map<String, Object>> result = cache.getBreachedRuns();

        assertThat(result).isEmpty();
    }

    @Test
    void getBreachedRuns_validHashValue_isIncluded() throws Exception {
        String runKey = TestFixtures.DEFAULT_TENANT_ID + ":" + TestFixtures.DEFAULT_RUN_ID
                + ":" + TestFixtures.DEFAULT_DATE;
        Map<String, Object> runInfo = Map.of(
                "runId", TestFixtures.DEFAULT_RUN_ID,
                "calculatorId", TestFixtures.DEFAULT_CALC_ID,
                "tenantId", TestFixtures.DEFAULT_TENANT_ID
        );
        String runInfoJson = new ObjectMapper().writeValueAsString(runInfo);

        Set<String> breachedKeys = new LinkedHashSet<>(List.of(runKey));
        when(zSetOps.rangeByScore(eq(SLA_DEADLINES_ZSET), eq(0.0), anyDouble()))
                .thenReturn(breachedKeys);
        when(hashOps.get(eq(SLA_RUN_INFO_HASH), eq(runKey))).thenReturn(runInfoJson);

        List<Map<String, Object>> result = cache.getBreachedRuns();

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("runId", TestFixtures.DEFAULT_RUN_ID);
    }

    @Test
    void getBreachedRuns_whenNothingBreached_returnsEmptyList() {
        when(zSetOps.rangeByScore(eq(SLA_DEADLINES_ZSET), eq(0.0), anyDouble()))
                .thenReturn(Set.of());

        List<Map<String, Object>> result = cache.getBreachedRuns();

        assertThat(result).isEmpty();
    }
}
