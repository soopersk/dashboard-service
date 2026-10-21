package com.company.observability.cache;

import com.company.observability.config.RunHistoryCacheProperties;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.dto.response.RunHistoryResponse;
import com.company.observability.event.RunCompletedEvent;
import com.company.observability.event.RunStartedEvent;
import com.company.observability.event.SlaBreachedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.company.observability.util.ObservabilityConstants.*;

/**
 * Generation-invalidated cache for {@code GET /calculators/{name}/runs}.
 *
 * <p>Every snapshot key embeds the calculator's current generation token. A run lifecycle event
 * (after commit) replaces the token, which makes every snapshot of that calculator unreachable in
 * one {@code SET}. A request captures the token <em>before</em> its DB read and writes under that
 * token, so a read that raced a commit lands under a dead generation: requests that resolve the
 * generation after the invalidation never see it. (A request already in flight across the
 * invalidation may still return the previous snapshot once — accepted eventual consistency.)
 * Tokens are random, not counters, so a lost/evicted generation key can't resurrect old snapshots.
 *
 * <p>Redis is best-effort throughout: any Redis or serialization failure degrades to a DB read.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CalculatorRunHistoryCacheService {

    static final String KEY_PREFIX = "obs:calculator-runs:v2:";

    private static final Set<String> KNOWN_STATUSES = Arrays.stream(RunStatus.values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final RunHistoryCacheProperties properties;

    /**
     * Returns the cached snapshot for this filter combination, or runs {@code loader} (the DB read),
     * caches its result and returns it. {@code nocache=true} skips the read but still refreshes the
     * snapshot. Loader exceptions propagate; cache failures never do.
     */
    public RunHistoryResponse getOrLoad(String calculatorName, Frequency frequency, LocalDate reportingDate,
                                        String odRunId, int limit, boolean nocache,
                                        Supplier<RunHistoryResponse> loader) {
        if (!properties.isEnabled()) {
            return loader.get();
        }
        String scope = scope(calculatorName);
        String generation = resolveGeneration(scope);
        if (generation == null) {
            return loader.get();                     // Redis unavailable: DB only, nothing safe to write under
        }
        String key = snapshotKey(scope, generation, reportingDate, frequency, limit, odRunId);

        if (nocache) {
            countLookup("bypass");
        } else {
            RunHistoryResponse cached = read(key, calculatorName);
            if (cached != null) {
                return cached;
            }
        }

        RunHistoryResponse response = loader.get();
        write(key, response);                        // captured generation, never re-resolved (plan §4.2)
        return response;
    }

    // ================================================================
    // Invalidation — runs regardless of `enabled` (a pod with the cache off must still
    // invalidate for pods that have it on).
    // ================================================================

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onRunStarted(RunStartedEvent event) {
        invalidate(event.getRun().getCalculatorName(), "run_started");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onRunCompleted(RunCompletedEvent event) {
        invalidate(event.getRun().getCalculatorName(), "run_completed");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onSlaBreached(SlaBreachedEvent event) {
        invalidate(event.getRun().getCalculatorName(), "sla_breached");
    }

    void invalidate(String calculatorName, String trigger) {
        try {
            redisTemplate.opsForValue().set(generationKey(scope(calculatorName)), newGeneration(),
                    properties.getGenerationTtl());
            meterRegistry.counter(CACHE_HISTORY_INVALIDATION, "outcome", "success").increment();
            log.debug("event=history_cache.invalidate outcome=success calculator={} trigger={}", calculatorName, trigger);
        } catch (Exception e) {
            meterRegistry.counter(CACHE_HISTORY_INVALIDATION, "outcome", "error").increment();
            log.warn("event=history_cache.invalidate outcome=failure calculator={} trigger={} error={}",
                    calculatorName, trigger, e.getClass().getSimpleName());
        }
    }

    // ================================================================
    // Internals
    // ================================================================

    /** Current token; seeds one with NX when missing. Null when Redis is unavailable. */
    private String resolveGeneration(String scope) {
        String generationKey = generationKey(scope);
        try {
            ValueOperations<String, String> ops = redisTemplate.opsForValue();
            String generation = ops.get(generationKey);
            if (generation != null) {
                return generation;
            }
            String seeded = newGeneration();
            if (Boolean.TRUE.equals(ops.setIfAbsent(generationKey, seeded, properties.getGenerationTtl()))) {
                return seeded;
            }
            return ops.get(generationKey);           // lost the seeding race; null only if it vanished again
        } catch (Exception e) {
            countLookup("error");
            log.warn("event=history_cache.generation outcome=failure scope={} error={}",
                    scope, e.getClass().getSimpleName());
            return null;
        }
    }

    private RunHistoryResponse read(String key, String calculatorName) {
        String json;
        try {
            json = redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            countLookup("error");
            log.warn("event=history_cache.read outcome=failure key={} error={}", key, e.getClass().getSimpleName());
            return null;
        }
        if (json == null) {
            countLookup("miss");
            return null;
        }
        RunHistoryResponse cached;
        try {
            cached = objectMapper.readValue(json, RunHistoryResponse.class);
        } catch (Exception e) {
            return corrupt(key, e.getClass().getSimpleName());
        }
        if (!isValid(cached, calculatorName)) {
            return corrupt(key, "InvalidSnapshot");
        }
        countLookup("hit");
        return cached;
    }

    /**
     * Nothing that fails this check may leave the cache boundary: the controller and the TTL choice
     * call {@code anyLive()}, which dereferences every entry's status. Only identity and status are
     * required — display fields may be null, so adding a nullable field stays non-breaking (plan §5).
     */
    static boolean isValid(RunHistoryResponse snapshot, String calculatorName) {
        return snapshot != null
                && calculatorName.equals(snapshot.calculatorName())
                && snapshot.runs() != null
                && snapshot.runs().stream().allMatch(e -> e != null
                        && e.runId() != null
                        && e.status() != null && KNOWN_STATUSES.contains(e.status()));
    }

    /** Not deleted: the write after the DB load overwrites it (plan C6). Never logs payload. */
    private RunHistoryResponse corrupt(String key, String reason) {
        countLookup("corrupt");
        log.warn("event=history_cache.read outcome=corrupt key={} error={}", key, reason);
        return null;
    }

    private void write(String key, RunHistoryResponse response) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(response),
                    response.anyLive() ? properties.getTransientTtl() : properties.getTerminalTtl());
            meterRegistry.counter(CACHE_HISTORY_WRITE, "outcome", "success").increment();
        } catch (Exception e) {
            meterRegistry.counter(CACHE_HISTORY_WRITE, "outcome", "error").increment();
            log.warn("event=history_cache.write outcome=failure key={} error={}", key, e.getClass().getSimpleName());
        }
    }

    private void countLookup(String outcome) {
        meterRegistry.counter(CACHE_HISTORY_LOOKUP, "outcome", outcome).increment();
    }

    private static String newGeneration() {
        return String.format("%016x", ThreadLocalRandom.current().nextLong());
    }

    // ================================================================
    // Key builders (package-private for tests) — plan §5
    // ================================================================

    static String scope(String calculatorName) {
        return URLEncoder.encode(calculatorName, StandardCharsets.UTF_8);
    }

    static String generationKey(String scope) {
        return KEY_PREFIX + "g:" + scope;
    }

    static String snapshotKey(String scope, String generation, LocalDate reportingDate, Frequency frequency,
                              int limit, String odRunId) {
        return KEY_PREFIX + "r:" + scope + ":" + generation
                + ":" + (reportingDate == null ? "all" : reportingDate)
                + ":" + (frequency == null ? "all" : frequency.name())
                + ":" + limit
                + ":" + (odRunId == null ? "od:none" : "od:value:" + odRunId);
    }
}
