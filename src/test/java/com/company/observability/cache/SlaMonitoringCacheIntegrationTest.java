package com.company.observability.cache;

import com.company.observability.config.RedisCacheConfig;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.util.TestFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link SlaMonitoringCache} against a real Redis instance.
 *
 * <p>Verifies the complete lifecycle: register → query breached/approaching → deregister.
 * Each test begins with {@code FLUSHALL} for isolation.
 */
@SpringBootTest(classes = {RedisCacheConfig.class, SlaMonitoringCache.class})
@Import(SlaMonitoringCacheIntegrationTest.TestRedisConfig.class)
class SlaMonitoringCacheIntegrationTest extends RedisIntegrationTestBase {

    @TestConfiguration
    static class TestRedisConfig {

        @Bean
        @Primary
        LettuceConnectionFactory redisConnectionFactory(
                @Value("${spring.data.redis.host}") String host,
                @Value("${spring.data.redis.port}") int port) {
            LettuceConnectionFactory factory = new LettuceConnectionFactory(host, port);
            factory.afterPropertiesSet();
            return factory;
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
            return new StringRedisTemplate(connectionFactory);
        }
    }

    @Autowired
    private SlaMonitoringCache cache;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        ReflectionTestUtils.setField(cache, "liveTrackingEnabled", true);
    }

    // ---------------------------------------------------------------
    // register → getBreachedRuns
    // ---------------------------------------------------------------

    @Test
    void register_pastDeadline_appearsInBreachedList() {
        // SLA was 5 minutes ago — deadline is in the past
        CalculatorRun run = runWithSlaTime(Instant.now().minusSeconds(300));

        cache.registerForSlaMonitoring(run);

        List<Map<String, Object>> breached = cache.getBreachedRuns();

        assertThat(breached).hasSize(1);
        assertThat(breached.get(0)).containsEntry("runId", run.getRunId());
    }

    @Test
    void register_futureDeadline_notInBreachedList() {
        // SLA is 1 hour away — should not appear in breached list
        CalculatorRun run = runWithSlaTime(Instant.now().plusSeconds(3600));

        cache.registerForSlaMonitoring(run);

        List<Map<String, Object>> breached = cache.getBreachedRuns();

        assertThat(breached).isEmpty();
    }

    // ---------------------------------------------------------------
    // deregister
    // ---------------------------------------------------------------

    @Test
    void deregister_removesFromBothZsetAndHash() {
        CalculatorRun run = runWithSlaTime(Instant.now().minusSeconds(300));
        cache.registerForSlaMonitoring(run);
        assertThat(cache.getBreachedRuns()).hasSize(1); // sanity

        cache.deregisterFromSlaMonitoring(
                run.getRunId(), run.getTenantId(), run.getReportingDate());

        assertThat(cache.getBreachedRuns()).isEmpty();
        assertThat(cache.getMonitoredRunCount()).isZero();
    }

    // ---------------------------------------------------------------
    // getApproachingSlaRuns
    // ---------------------------------------------------------------

    @Test
    void getApproachingSla_runWithin10Minutes_isReturned() {
        // SLA is 5 minutes from now — within the 10-minute warning window
        CalculatorRun run = runWithSlaTime(Instant.now().plusSeconds(300));

        cache.registerForSlaMonitoring(run);

        List<Map<String, Object>> approaching = cache.getApproachingSlaRuns(10);

        assertThat(approaching).hasSize(1);
        assertThat(approaching.get(0)).containsEntry("runId", run.getRunId());
    }

    @Test
    void getApproachingSla_runOutside10Minutes_isNotReturned() {
        // SLA is 20 minutes from now — outside the 10-minute warning window
        CalculatorRun run = runWithSlaTime(Instant.now().plusSeconds(1200));

        cache.registerForSlaMonitoring(run);

        List<Map<String, Object>> approaching = cache.getApproachingSlaRuns(10);

        assertThat(approaching).isEmpty();
    }

    // ---------------------------------------------------------------
    // getMonitoredRunCount
    // ---------------------------------------------------------------

    @Test
    void getMonitoredRunCount_accurateAfterRegisterAndDeregister() {
        CalculatorRun run1 = runWithSlaTime(Instant.now().plusSeconds(600), "run-A");
        CalculatorRun run2 = runWithSlaTime(Instant.now().plusSeconds(900), "run-B");

        assertThat(cache.getMonitoredRunCount()).isZero();

        cache.registerForSlaMonitoring(run1);
        assertThat(cache.getMonitoredRunCount()).isEqualTo(1);

        cache.registerForSlaMonitoring(run2);
        assertThat(cache.getMonitoredRunCount()).isEqualTo(2);

        cache.deregisterFromSlaMonitoring(run1.getRunId(), run1.getTenantId(), run1.getReportingDate());
        assertThat(cache.getMonitoredRunCount()).isEqualTo(1);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private CalculatorRun runWithSlaTime(Instant slaTime) {
        return runWithSlaTime(slaTime, TestFixtures.DEFAULT_RUN_ID);
    }

    private CalculatorRun runWithSlaTime(Instant slaTime, String runId) {
        return CalculatorRun.builder()
                .runId(runId)
                .calculatorId(TestFixtures.DEFAULT_CALC_ID)
                .calculatorName(TestFixtures.DEFAULT_CALC_NAME)
                .tenantId(TestFixtures.DEFAULT_TENANT_ID)
                .frequency(com.company.observability.domain.enums.Frequency.DAILY)
                .reportingDate(TestFixtures.DEFAULT_DATE)
                .startTime(TestFixtures.DEFAULT_START)
                .status(com.company.observability.domain.enums.RunStatus.RUNNING)
                .slaTime(slaTime)
                .slaBand(null)
                .createdAt(TestFixtures.DEFAULT_START)
                .build();
    }
}
