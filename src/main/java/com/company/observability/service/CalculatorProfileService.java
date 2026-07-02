package com.company.observability.service;

import com.company.observability.config.AggregationProperties;
import com.company.observability.config.SlaProperties;
import com.company.observability.domain.CalculatorProfile;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.repository.DailyAggregateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Cache-aside access to slowly-changing {@link CalculatorProfile}s (avg runtime, avg start/end).
 *
 * <p>Profiles are warmed nightly by {@code DailyAggregationJob}; on a cache miss the profile is
 * read once from {@code calculator_sli_daily} and cached. This removes the per-run-start DB
 * query that the SLA baseline and estimated start/end previously incurred.
 *
 * <p>Resilient like {@code AnalyticsCacheService}: Redis failures degrade to a DB read and
 * never throw.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CalculatorProfileService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final DailyAggregateRepository dailyAggregateRepository;
    private final SlaProperties slaProperties;
    private final AggregationProperties aggregationProperties;
    private final MeterRegistry meterRegistry;
    private final CalculatorNameResolver nameResolver;

    private static final String PROFILE_PREFIX = "obs:profile:";

    /** Cache-aside read. Never throws; falls back to a DB read (and a zero-sample profile on error). */
    public CalculatorProfile getProfile(String calculatorName, Frequency frequency) {
        String key = key(calculatorName, frequency, null, null);

        CalculatorProfile cached = readFromCache(key);
        if (cached != null) {
            meterRegistry.counter("obs.profile.cache", "result", "hit").increment();
            return cached;
        }
        meterRegistry.counter("obs.profile.cache", "result", "miss").increment();

        CalculatorProfile profile = dailyAggregateRepository.findProfile(
                calculatorName, frequency.name(), slaProperties.lookbackDays(frequency));
        writeToCache(key, profile);
        return profile;
    }

    /**
     * Run_number-scoped cache-aside read. Returns a profile aggregated only from runs with
     * the given {@code runNumber} — gives accurate start/end estimates when a calculator runs
     * in multiple cycles (T+1 vs T+2) with different timing.
     *
     * <p>Two tiers, no blending: the exact run_number slice from {@code calculator_sli_daily}
     * (Tier 1), then — only when that slice has <b>zero</b> history — the last few raw runs from
     * {@code calculator_runs} (Tier 2, RECENT_EXACT). Sparse exact data is always kept rather than
     * diluted by a cross-run_number average. When both tiers miss, a zero-sample sentinel is
     * returned (never null) so callers' {@code hasSufficientSamples()} guard handles it uniformly.
     *
     * <p>A null {@code runNumber} (Archetype B — no run_number dimension) routes to the blended
     * {@link #getProfile(String, Frequency)}: with no run_number to scope by, the blended profile
     * <i>is</i> the exact slice.
     */
    public CalculatorProfile getProfile(String calculatorName, Frequency frequency, String runNumber) {
        // run_number is a real scoping dimension only for run-number-aware calculators. For an
        // agnostic calc (or a null run_number) the blended profile IS the exact slice — this also
        // defends query paths (/batch/runs placeholders) where run_number is client-supplied.
        if (runNumber == null || !nameResolver.isRunNumberAware(calculatorName)) {
            return getProfile(calculatorName, frequency);
        }
        String key = key(calculatorName, frequency, runNumber, null);

        CalculatorProfile cached = readFromCache(key);
        if (cached != null) {
            meterRegistry.counter("obs.profile.cache", "result", "hit", "scoped", "true").increment();
            return cached;
        }
        meterRegistry.counter("obs.profile.cache", "result", "miss", "scoped", "true").increment();

        // Tier 1: exact run_number slice from the nightly aggregate.
        CalculatorProfile profile = dailyAggregateRepository.findProfileByRunNumber(
                calculatorName, frequency.name(), slaProperties.lookbackDays(frequency), runNumber);
        if (profile.totalRuns() > 0) {
            return cacheAndReturn(key, tagAggregateConfidence(profile));
        }

        // Tier 2: last raw runs for the exact run_number slice.
        CalculatorProfile recent = dailyAggregateRepository.findRecentExactByRunNumber(
                calculatorName, frequency.name(), slaProperties.lookbackDays(frequency), runNumber);
        if (recent.totalRuns() > 0) {
            return cacheAndReturn(key, recent.withConfidence(CalculatorProfile.ProfileConfidence.RECENT_EXACT));
        }

        // Both tiers missed — zero-sample sentinel (cached briefly).
        return cacheAndReturn(key, CalculatorProfile.fromSums(
                calculatorName, frequency.name(), runNumber, null, 0, 0, 0, 0));
    }

    /**
     * Dimension-scoped cache-aside read. Returns a profile aggregated only from rows matching
     * the given {@code dimensionValue} (e.g. "WMAP") and {@code runNumber}.
     *
     * <p>Same two-tier, no-blend contract as {@link #getProfile(String, Frequency, String)}:
     * exact dimension slice from the aggregate (Tier 1), then the last raw runs for that exact
     * slice (Tier 2, RECENT_EXACT), then a zero-sample sentinel. Never falls back to a coarser
     * (dimension-collapsed) profile.
     *
     * <p>A null {@code dimensionValue} (Archetype C — single constant dimension) routes to the
     * run_number-scoped {@link #getProfile(String, Frequency, String)} — same underlying data.
     */
    public CalculatorProfile getProfile(String calculatorName, Frequency frequency,
                                        String runNumber, String dimensionValue) {
        if (dimensionValue == null) {
            return getProfile(calculatorName, frequency, runNumber);
        }
        // Scope by run_number only for aware calcs; for an agnostic calc collapse to null so the
        // key becomes …:*:{dim} and Tier-1/Tier-2 match the 'ALL'/raw-null rows respectively.
        String effRn = nameResolver.isRunNumberAware(calculatorName) ? runNumber : null;
        String key = key(calculatorName, frequency, effRn, dimensionValue);

        CalculatorProfile cached = readFromCache(key);
        if (cached != null) {
            meterRegistry.counter("obs.profile.cache", "result", "hit", "dim", "true").increment();
            return cached;
        }
        meterRegistry.counter("obs.profile.cache", "result", "miss", "dim", "true").increment();

        // Tier 1: exact dimension slice from the nightly aggregate.
        CalculatorProfile profile = dailyAggregateRepository.findProfileByRunNumberAndDimension(
                calculatorName, frequency.name(), slaProperties.lookbackDays(frequency),
                effRn, dimensionValue);
        if (profile.totalRuns() > 0) {
            return cacheAndReturn(key, tagAggregateConfidence(profile));
        }

        // Tier 2: last raw runs for the exact dimension slice.
        CalculatorProfile recent = dailyAggregateRepository.findRecentExactByDimension(
                calculatorName, frequency.name(), slaProperties.lookbackDays(frequency), effRn, dimensionValue);
        if (recent.totalRuns() > 0) {
            return cacheAndReturn(key, recent.withConfidence(CalculatorProfile.ProfileConfidence.RECENT_EXACT));
        }

        // Both tiers missed — zero-sample sentinel (cached briefly).
        return cacheAndReturn(key, CalculatorProfile.fromSums(
                calculatorName, frequency.name(), effRn, dimensionValue, 0, 0, 0, 0));
    }

    /** EXACT when the exact slice has enough samples, SPARSE_EXACT when it has 1..(min-1). */
    private CalculatorProfile tagAggregateConfidence(CalculatorProfile profile) {
        return profile.withConfidence(profile.hasSufficientSamples(slaProperties.getMinSampleSize())
                ? CalculatorProfile.ProfileConfidence.EXACT
                : CalculatorProfile.ProfileConfidence.SPARSE_EXACT);
    }

    private CalculatorProfile cacheAndReturn(String key, CalculatorProfile profile) {
        writeToCache(key, profile);
        return profile;
    }

    /**
     * Warm a precomputed profile into the cache (called by the nightly job).
     * Uses {@code profile.runNumber()} and {@code profile.dimensionValue()} to select the key.
     */
    public void warm(CalculatorProfile profile) {
        writeToCache(key(profile.calculatorName(),
                Frequency.from(profile.frequency()), profile.runNumber(), profile.dimensionValue()), profile);
    }

    private CalculatorProfile readFromCache(String key) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json != null) {
                return objectMapper.readValue(json, CalculatorProfile.class);
            }
        } catch (Exception e) {
            log.warn("event=profile.cache.read outcome=failure key={} error={}", key, e.getMessage());
        }
        return null;
    }

    private void writeToCache(String key, CalculatorProfile profile) {
        Duration ttl = ttlFor(profile);
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(profile), ttl);
        } catch (Exception e) {
            log.warn("event=profile.cache.write outcome=failure key={} error={}", key, e.getMessage());
        }
    }

    /**
     * Cache TTL by profile kind: "no history yet" sentinels get a short TTL so newly-active
     * calculators are picked up sooner; lazily-built RECENT_EXACT profiles get a medium TTL so the
     * next nightly recompute supersedes them quickly; aggregate-backed profiles get the daily TTL.
     */
    private Duration ttlFor(CalculatorProfile profile) {
        if (profile.totalRuns() <= 0) {
            return Duration.ofMinutes(aggregationProperties.getEmptyProfileCacheTtlMinutes());
        }
        if (profile.confidence() == CalculatorProfile.ProfileConfidence.RECENT_EXACT) {
            return Duration.ofHours(aggregationProperties.getRecentProfileCacheTtlHours());
        }
        return Duration.ofHours(aggregationProperties.getProfileCacheTtlHours());
    }

    private String key(String calculatorName, Frequency frequency, String runNumber, String dimensionValue) {
        String base = PROFILE_PREFIX + calculatorName + ":" + frequency.name();
        String withRn = runNumber != null ? base + ":" + runNumber : base + ":*";
        return dimensionValue != null ? withRn + ":" + dimensionValue : (runNumber != null ? base + ":" + runNumber : base);
    }
}
