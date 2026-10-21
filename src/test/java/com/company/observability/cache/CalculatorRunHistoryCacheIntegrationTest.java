package com.company.observability.cache;

import com.company.observability.config.RunHistoryCacheProperties;
import com.company.observability.dto.response.RunHistoryResponse;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CalculatorRunHistoryCacheIntegrationTest extends RedisIntegrationTestBase {

    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private CalculatorRunHistoryCacheService cache;
    private final AtomicInteger loads = new AtomicInteger();

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        cache = new CalculatorRunHistoryCacheService(redis, JsonMapper.builder().findAndAddModules().build(),
                new SimpleMeterRegistry(), new RunHistoryCacheProperties());
        loads.set(0);
    }

    @AfterEach
    void tearDown() {
        factory.destroy();
    }

    @Test
    void secondRead_isServedFromCache() {
        get("SUCCESS");
        get("SUCCESS");
        assertThat(loads).hasValue(1);
    }

    @Test
    void invalidation_forcesReload() {
        get("SUCCESS");
        cache.invalidate("calc-a", "run_started");
        get("SUCCESS");
        assertThat(loads).hasValue(2);
    }

    @Test
    void readThatRacedACommit_isNeverServed() {
        // Request 1 reads the pre-START state; START commits + invalidates while its query runs.
        RunHistoryResponse stale = cache.getOrLoad("calc-a", null, null, null, 1, false, () -> {
            loads.incrementAndGet();
            cache.invalidate("calc-a", "run_started");
            return response("SUCCESS");
        });
        assertThat(stale.runs().get(0).status()).isEqualTo("SUCCESS");

        // Request 2 must not see request 1's snapshot.
        assertThat(get("RUNNING").runs().get(0).status()).isEqualTo("RUNNING");
        assertThat(loads).hasValue(2);
    }

    @Test
    void evictedGenerationKey_doesNotResurrectOldSnapshots() {
        get("SUCCESS");                                                     // snapshot under g1
        cache.invalidate("calc-a", "run_started");                          // g2
        redis.delete(CalculatorRunHistoryCacheService.generationKey("calc-a")); // eviction/expiry
        assertThat(get("RUNNING").runs().get(0).status()).isEqualTo("RUNNING"); // fresh seed ≠ g1
        assertThat(loads).hasValue(2);
    }

    @Test
    void ttl_followsSnapshotState() {
        get("RUNNING");
        String gen = redis.opsForValue().get(CalculatorRunHistoryCacheService.generationKey("calc-a"));
        String key = CalculatorRunHistoryCacheService.snapshotKey("calc-a", gen, null, null, 1, null);
        assertThat(redis.getExpire(key)).isBetween(1L, 10L);
        assertThat(redis.getExpire(CalculatorRunHistoryCacheService.generationKey("calc-a"))).isBetween(840L, 900L);

        cache.getOrLoad("calc-a", null, null, null, 1, true, () -> response("SUCCESS")); // nocache refresh
        assertThat(redis.getExpire(key)).isBetween(240L, 300L);
    }

    @Test
    void corruptSnapshot_isReplacedWithValidJson() {
        get("SUCCESS");
        String gen = redis.opsForValue().get(CalculatorRunHistoryCacheService.generationKey("calc-a"));
        String key = CalculatorRunHistoryCacheService.snapshotKey("calc-a", gen, null, null, 1, null);
        redis.opsForValue().set(key, "{not-json");

        get("SUCCESS");
        get("SUCCESS");
        assertThat(loads).hasValue(2);                                     // reloaded once, then cached again
        assertThat(redis.opsForValue().get(key)).startsWith("{\"calculatorName\"");
    }

    private RunHistoryResponse get(String status) {
        return cache.getOrLoad("calc-a", null, null, null, 1, false, () -> {
            loads.incrementAndGet();
            return response(status);
        });
    }

    private static RunHistoryResponse response(String status) {
        return new RunHistoryResponse("calc-a", List.of(new RunHistoryResponse.RunEntry(
                "r1", "id-a", "DAILY", LocalDate.of(2026, 10, 20), "1", null, null, status,
                Instant.parse("2026-10-21T05:00:00Z"), null, null, 60000L,
                Instant.parse("2026-10-21T07:00:00Z"), null, false, null, null, null)));
    }
}
