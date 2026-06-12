package com.company.observability.cache;

import com.company.observability.config.RedisCacheConfig;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.util.TestFixtures;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the slimmed {@link RedisCalculatorCache} against a real Redis
 * instance started via Testcontainers — running-set round-trip only.
 *
 * <p>Each test begins with a {@code FLUSHALL} to guarantee isolation.
 */
@SpringBootTest(classes = {RedisCacheConfig.class, RedisCalculatorCache.class})
@Import(RedisCalculatorCacheIntegrationTest.TestRedisConfig.class)
class RedisCalculatorCacheIntegrationTest extends RedisIntegrationTestBase {

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
        StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
            return new StringRedisTemplate(connectionFactory);
        }
    }

    @Autowired
    private RedisCalculatorCache cache;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void flushAll() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void runningSetMembership_updatesOnStatusChange() {
        CalculatorRun running = TestFixtures.aRunningRun();
        cache.trackRunningState(running);

        String member = running.getCalculatorId() + ":DAILY";
        assertThat(cache.getRunningCalculators()).contains(member);

        // Now track the same calculator as completed
        CalculatorRun completed = TestFixtures.aCompletedRun(
                running.getRunId(), running.getCalculatorId(), running.getTenantId());
        cache.trackRunningState(completed);

        assertThat(cache.getRunningCalculators()).doesNotContain(member);
    }
}
