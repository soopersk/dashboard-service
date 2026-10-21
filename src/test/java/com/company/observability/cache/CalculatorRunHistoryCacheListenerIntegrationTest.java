package com.company.observability.cache;

import com.company.observability.config.RunHistoryCacheProperties;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.SlaEvaluationResult;
import com.company.observability.domain.enums.SlaBand;
import com.company.observability.event.RunCompletedEvent;
import com.company.observability.event.RunStartedEvent;
import com.company.observability.event.SlaBreachedEvent;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(CalculatorRunHistoryCacheListenerIntegrationTest.Config.class)
class CalculatorRunHistoryCacheListenerIntegrationTest extends RedisIntegrationTestBase {

    private static final String GEN_KEY = CalculatorRunHistoryCacheService.generationKey("calc-a");
    private static final String SEED = "0000000000000001";

    @Configuration
    @EnableAsync
    @EnableTransactionManagement
    static class Config {
        @Bean
        LettuceConnectionFactory redisConnectionFactory(@Value("${spring.data.redis.host}") String host,
                                                        @Value("${spring.data.redis.port}") int port) {
            return new LettuceConnectionFactory(host, port);
        }

        @Bean
        StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
            return new StringRedisTemplate(connectionFactory);
        }

        @Bean
        CalculatorRunHistoryCacheService historyCache(StringRedisTemplate redis) {
            return new CalculatorRunHistoryCacheService(redis, JsonMapper.builder().findAndAddModules().build(),
                    new SimpleMeterRegistry(), new RunHistoryCacheProperties());
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return new NoOpTransactionManager();
        }

        @Bean
        ManualTaskExecutor taskExecutor() {
            return new ManualTaskExecutor();
        }
    }

    /** Real synchronization lifecycle (begin → commit/rollback → afterCompletion) without a DB. */
    static class NoOpTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }

    /** Queues {@code @Async} tasks; the test decides when (and whether) they run. */
    static class ManualTaskExecutor implements TaskExecutor {
        private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        /** Runs every queued task on the calling thread; returns how many ran. */
        int drain() {
            int ran = 0;
            for (Runnable task; (task = tasks.poll()) != null; ran++) {
                task.run();
            }
            return ran;
        }

        void clear() {
            tasks.clear();
        }
    }

    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private StringRedisTemplate redis;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ManualTaskExecutor executor;

    @BeforeEach
    void seedGeneration() {
        executor.clear();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        redis.opsForValue().set(GEN_KEY, SEED);
    }

    static Stream<Object> events() {
        CalculatorRun run = CalculatorRun.builder().calculatorName("calc-a").build();
        return Stream.of(
                new RunStartedEvent(run),
                new RunCompletedEvent(run),
                new SlaBreachedEvent(run, new SlaEvaluationResult(SlaBand.LATE, "test")));
    }

    @ParameterizedTest
    @MethodSource("events")
    void commit_submitsOneAsyncInvalidation_onlyAfterCommit(Object event) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            publisher.publishEvent(event);
            assertThat(executor.drain()).as("tasks submitted before commit").isZero();
            assertThat(generation()).as("generation before commit").isEqualTo(SEED);
        });

        assertThat(generation()).as("listener must not run on the committing thread").isEqualTo(SEED);
        assertThat(executor.drain()).as("async tasks submitted at commit").isEqualTo(1);
        assertThat(generation()).matches("[0-9a-f]{16}").isNotEqualTo(SEED);
    }

    @ParameterizedTest
    @MethodSource("events")
    void rollback_submitsNothing_andLeavesGenerationUnchanged(Object event) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            publisher.publishEvent(event);
            status.setRollbackOnly();
        });

        assertThat(executor.drain()).isZero();
        assertThat(generation()).isEqualTo(SEED);
    }

    @Test
    void publishOutsideTransaction_isIgnored() {
        // Documents assumption 3: publish sites MUST be transactional, or invalidation silently never runs.
        publisher.publishEvent(new RunStartedEvent(CalculatorRun.builder().calculatorName("calc-a").build()));

        assertThat(executor.drain()).isZero();
        assertThat(generation()).isEqualTo(SEED);
    }

    private String generation() {
        return redis.opsForValue().get(GEN_KEY);
    }
}
