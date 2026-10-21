package com.company.observability.cache;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.dto.response.RunHistoryResponse;
import com.company.observability.event.RunCompletedEvent;
import com.company.observability.event.RunStartedEvent;
import com.company.observability.event.SlaBreachedEvent;
import com.company.observability.service.CalculatorNameResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.company.observability.util.ObservabilityConstants.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final CalculatorNameResolver nameResolver;

    private static final String ANALYTICS_PREFIX = "obs:analytics:";
    private static final String ANALYTICS_INDEX_PREFIX = "obs:analytics:index:";
    private static final String RUN_HISTORY_PREFIX = "history";
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);
    private static final Duration INDEX_TTL = Duration.ofHours(1);

    // ================================================================
    // Generic cache operations with Redis resilience
    // ================================================================

    /**
     * runNumber-aware get — for executions cache keyed by calculatorName.
     * Key: obs:analytics:executions:{name}:{freq}:{days}:{runNumber|all}:{asOfDate}
     */
    public <T> T getFromCache(String keyPrefix, String calculatorKey,
                              String frequency, int days, String runNumber,
                              LocalDate asOfDate, Class<T> responseType) {
        return read(buildKeyWithRunNumber(keyPrefix, calculatorKey, frequency, days, runNumber, asOfDate),
                keyPrefix, responseType);
    }

    /**
     * runNumber-aware put — for executions cache keyed by calculatorName.
     */
    public void putInCache(String keyPrefix, String calculatorKey,
                           String frequency, int days, String runNumber,
                           LocalDate asOfDate, Object response) {
        write(calculatorKey, buildKeyWithRunNumber(keyPrefix, calculatorKey, frequency, days, runNumber, asOfDate),
                response, DEFAULT_TTL);
    }

    /**
     * Run history cache for {@code /calculators/{name}/runs}.
     * Key: obs:analytics:history:{urlEncodedName}:{freq|all}:{reportingDate|all}:{limit}:{od:none|od:value:{odRunId}}
     */
    public RunHistoryResponse getRunHistory(String calculatorName, Frequency frequency,
                                            LocalDate reportingDate, String odRunId, int limit) {
        return read(buildRunHistoryKey(calculatorName, frequency, reportingDate, odRunId, limit),
                RUN_HISTORY_PREFIX, RunHistoryResponse.class);
    }

    public void putRunHistory(String calculatorName, Frequency frequency, LocalDate reportingDate,
                              String odRunId, int limit, RunHistoryResponse response, Duration ttl) {
        write(calculatorName, buildRunHistoryKey(calculatorName, frequency, reportingDate, odRunId, limit),
                response, ttl);
    }

    private <T> T read(String key, String keyPrefix, Class<T> responseType) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json != null) {
                meterRegistry.counter(CACHE_ANALYTICS_HIT, "prefix", keyPrefix).increment();
                log.debug("event=cache.read outcome=hit key={}", key);
                return objectMapper.readValue(json, responseType);
            }
        } catch (Exception e) {
            log.warn("event=cache.read outcome=failure key={} error={}", key, e.getMessage());
        }
        meterRegistry.counter(CACHE_ANALYTICS_MISS, "prefix", keyPrefix).increment();
        return null;
    }

    /** Writes the entry and tracks it in the calculatorName index so run events evict it. */
    private void write(String calculatorKey, String key, Object response, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(response), ttl);
            trackKey(buildIndexKey(calculatorKey), key);
            log.debug("event=cache.write outcome=success key={}", key);
        } catch (Exception e) {
            log.warn("event=cache.write outcome=failure key={} error={}", key, e.getMessage());
        }
    }

    // ================================================================
    // Event-driven invalidation
    // ================================================================

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onRunStarted(RunStartedEvent event) {
        // A new run changes both executions and run-history results — evict every analytics key.
        evictForCalculator(event.getRun());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onRunCompleted(RunCompletedEvent event) {
        evictForCalculator(event.getRun());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onSlaBreached(SlaBreachedEvent event) {
        evictForCalculator(event.getRun());
    }

    private void evictForCalculator(CalculatorRun run) {
        // Analytics caches (executions, run history) are all tracked in the calculatorName index.
        evictIndex(buildIndexKey(run.getCalculatorName()), run.getCalculatorName());
        // Also evict the alias cache entry when this real calculator belongs to a UI alias
        nameResolver.findAliasFor(run.getCalculatorName())
                .ifPresent(alias -> evictIndex(buildIndexKey(alias), alias));
    }

    private void evictIndex(String indexKey, String calculatorKey) {
        try {
            Set<String> indexedKeys = redisTemplate.opsForSet().members(indexKey);
            if (indexedKeys != null && !indexedKeys.isEmpty()) {
                List<String> keysToDelete = indexedKeys.stream()
                        .map(Object::toString)
                        .collect(Collectors.toList());
                keysToDelete.add(indexKey);
                redisTemplate.delete(keysToDelete);
                meterRegistry.counter(CACHE_ANALYTICS_EVICTION).increment();
                log.debug("event=cache.evict outcome=success calculatorKey={} keysEvicted={}",
                        calculatorKey, indexedKeys.size());
            }
        } catch (Exception e) {
            log.warn("event=cache.evict outcome=failure calculatorKey={} error={}",
                    calculatorKey, e.getMessage());
        }
    }

    // ================================================================
    // Key builders
    // ================================================================

    private String buildKeyWithRunNumber(String prefix, String calculatorKey,
                                         String frequency, int days, String runNumber,
                                         LocalDate asOfDate) {
        String rn = (runNumber == null) ? "all" : runNumber;
        return ANALYTICS_PREFIX + prefix + ":" + calculatorKey
                + ":" + frequency + ":" + days + ":" + rn + ":" + asOfDate;
    }

    /**
     * One key per filter combination, never shared between combinations. The free-form calculator
     * name is URL-encoded so it cannot impersonate later segments; the free-form odRunId is tagged
     * ({@code od:none} vs {@code od:value:{id}}) so no value can collide with "absent", and it is last.
     */
    static String buildRunHistoryKey(String calculatorName, Frequency frequency,
                                     LocalDate reportingDate, String odRunId, int limit) {
        return ANALYTICS_PREFIX + RUN_HISTORY_PREFIX + ":" + URLEncoder.encode(calculatorName, StandardCharsets.UTF_8)
                + ":" + (frequency == null ? "all" : frequency.name())
                + ":" + (reportingDate == null ? "all" : reportingDate)
                + ":" + limit
                + ":" + (odRunId == null ? "od:none" : "od:value:" + odRunId);
    }

    private String buildIndexKey(String calculatorId) {
        return ANALYTICS_INDEX_PREFIX + calculatorId;
    }

    private void trackKey(String indexKey, String key) {
        redisTemplate.opsForSet().add(indexKey, key);
        redisTemplate.expire(indexKey, INDEX_TTL);
    }

}
