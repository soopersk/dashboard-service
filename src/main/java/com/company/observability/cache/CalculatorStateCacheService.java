package com.company.observability.cache;

import com.company.observability.dto.response.CalculatorBatchRunsResponse.CalculatorEntry;
import com.company.observability.dto.response.CalculatorBatchRunsResponse.RunEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.company.observability.util.ObservabilityConstants.*;

/**
 * Per-calculator Redis cache for the {@code GET /api/v1/calculators/batch/runs} endpoint.
 *
 * <p>Key: {@code obs:state:{calculatorName}:{reportingDate}:{frequency}:{runNumber|all}}
 *
 * <p>TTL is state-aware:
 * <ul>
 *   <li>any RUNNING → 30 s</li>
 *   <li>empty / any null status / all NOT_STARTED → 60 s</li>
 *   <li>not all-SUCCESS-clean (failure, breach, cancellation, unknown) → 5 min</li>
 *   <li>all SUCCESS &amp; clean, but a recent reporting date → 5 min (snapshot may still be partial)</li>
 *   <li>all SUCCESS &amp; clean and an old reporting date (&gt;3 days) → 4 h</li>
 * </ul>
 *
 * <p>Invalidation is TTL-only — no event listeners. A SUCCESS snapshot can be <em>partial</em>
 * (e.g. a multi-region calculator where one region finished before the next started, or a
 * re-trigger after SUCCESS). The 4 h bucket is therefore allowlist-only and gated on the reporting
 * date being old enough that no new runs are plausible; the current cycle stays at 5 min.
 *
 * <p>All Redis ops are best-effort: exceptions are swallowed and the caller falls back to DB.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CalculatorStateCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    private static final String KEY_PREFIX = "obs:state:";

    static final Duration TTL_ANY_RUNNING            = Duration.ofSeconds(30);
    static final Duration TTL_NOT_STARTED            = Duration.ofSeconds(60);
    static final Duration TTL_TERMINAL_WITH_FAILURES = Duration.ofMinutes(5);
    static final Duration TTL_TERMINAL_CLEAN         = Duration.ofHours(4);

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Bulk get for the given calculator names. Returns only cache hits keyed by name;
     * misses are simply absent from the returned map.
     */
    public Map<String, CalculatorEntry> getEntries(
            LocalDate reportingDate, String frequency, String runNumber,
            List<String> calculatorNames) {

        Map<String, CalculatorEntry> hits = new HashMap<>();
        for (String name : calculatorNames) {
            String key = buildKey(name, reportingDate, frequency, runNumber);
            try {
                String json = redisTemplate.opsForValue().get(key);
                if (json != null) {
                    CalculatorEntry entry = objectMapper.readValue(json, CalculatorEntry.class);
                    hits.put(name, entry);
                    meterRegistry.counter(CACHE_STATE_HIT, "calculator", name).increment();
                    log.debug("event=state.cache.read outcome=hit key={}", key);
                } else {
                    meterRegistry.counter(CACHE_STATE_MISS, "calculator", name).increment();
                    log.debug("event=state.cache.read outcome=miss key={}", key);
                }
            } catch (Exception e) {
                log.warn("event=state.cache.read outcome=failure key={} error={}", key, e.getMessage());
                meterRegistry.counter(CACHE_STATE_MISS, "calculator", name).increment();
            }
        }
        return hits;
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Stores each entry with its own state-aware TTL.
     */
    public void putEntries(
            LocalDate reportingDate, String frequency, String runNumber,
            Map<String, CalculatorEntry> entries) {

        entries.forEach((name, entry) -> {
            String key = buildKey(name, reportingDate, frequency, runNumber);
            Duration ttl = determineTtl(entry, reportingDate);
            try {
                String json = objectMapper.writeValueAsString(entry);
                redisTemplate.opsForValue().set(key, json, ttl);
                log.debug("event=state.cache.write outcome=success key={} ttl={}", key, ttl);
            } catch (Exception e) {
                log.warn("event=state.cache.write outcome=failure key={} error={}", key, e.getMessage());
            }
        });
    }

    // ── TTL selection ─────────────────────────────────────────────────────────

    /**
     * State-aware TTL selection based on the run states in the entry and the reporting date.
     */
    Duration determineTtl(CalculatorEntry entry, LocalDate reportingDate) {
        List<RunEntry> runs = entry.runs();

        if (runs == null || runs.isEmpty()) {
            return TTL_NOT_STARTED;
        }

        // Empty / any null status / any NOT_STARTED → not yet a stable terminal snapshot
        if (runs.stream().anyMatch(r -> r.status() == null)) {
            return TTL_NOT_STARTED;
        }

        boolean anyRunning = runs.stream()
                .anyMatch(r -> "RUNNING".equals(r.status()));
        if (anyRunning) {
            return TTL_ANY_RUNNING;
        }

        boolean allNotStarted = runs.stream()
                .allMatch(r -> "NOT_STARTED".equals(r.status()));
        if (allNotStarted) {
            return TTL_NOT_STARTED;
        }

        // Allowlist-only: only an all-SUCCESS, SLA-clean snapshot is a candidate for the long TTL.
        // Anything else (FAILED/TIMEOUT/CANCELLED/breached/unknown status) → short TTL.
        boolean allSuccessClean = runs.stream().allMatch(r ->
                "SUCCESS".equals(r.status())
                        && !"LATE".equals(r.slaStatus())
                        && !"VERY_LATE".equals(r.slaStatus()));
        if (!allSuccessClean) {
            return TTL_TERMINAL_WITH_FAILURES;
        }

        // A SUCCESS snapshot may still be partial (later regions / re-triggers after SUCCESS).
        // Only an old reporting date is truly stable. The 3-day horizon mirrors the DAILY query window.
        return reportingDate.isBefore(LocalDate.now().minusDays(3))
                ? TTL_TERMINAL_CLEAN
                : TTL_TERMINAL_WITH_FAILURES;
    }

    // ── Key builder ───────────────────────────────────────────────────────────

    private String buildKey(String calculatorName, LocalDate reportingDate,
                            String frequency, String runNumber) {
        String rn = (runNumber == null) ? "all" : runNumber;
        return KEY_PREFIX + calculatorName + ":" + reportingDate + ":" + frequency + ":" + rn;
    }
}
