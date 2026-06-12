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
     * <p>Falls back to {@link #getProfile(String, Frequency)} for brand-new calculators
     * with no run_number-scoped history.
     */
    public CalculatorProfile getProfile(String calculatorName, Frequency frequency, String runNumber) {
        if (runNumber == null) {
            return getProfile(calculatorName, frequency);
        }
        String key = key(calculatorName, frequency, runNumber, null);

        CalculatorProfile cached = readFromCache(key);
        if (cached != null) {
            meterRegistry.counter("obs.profile.cache", "result", "hit", "scoped", "true").increment();
            return cached;
        }
        meterRegistry.counter("obs.profile.cache", "result", "miss", "scoped", "true").increment();

        CalculatorProfile profile = dailyAggregateRepository.findProfileByRunNumber(
                calculatorName, frequency.name(), slaProperties.lookbackDays(frequency), runNumber);

        if (profile.hasSufficientSamples(slaProperties.getMinSampleSize())) {
            writeToCache(key, profile);
            return profile;
        }
        // Fall back to blended profile for brand-new calcs
        return getProfile(calculatorName, frequency);
    }

    /**
     * Dimension-scoped cache-aside read. Returns a profile aggregated only from rows matching
     * the given {@code dimensionValue} (e.g. "WMAP") and {@code runNumber}.
     *
     * <p>Falls back to {@link #getProfile(String, Frequency, String)} (scoped → blended chain)
     * for calculators with no dimension-specific history yet.
     */
    public CalculatorProfile getProfile(String calculatorName, Frequency frequency,
                                        String runNumber, String dimensionValue) {
        if (dimensionValue == null) {
            return getProfile(calculatorName, frequency, runNumber);
        }
        String key = key(calculatorName, frequency, runNumber, dimensionValue);

        CalculatorProfile cached = readFromCache(key);
        if (cached != null) {
            meterRegistry.counter("obs.profile.cache", "result", "hit", "dim", "true").increment();
            return cached;
        }
        meterRegistry.counter("obs.profile.cache", "result", "miss", "dim", "true").increment();

        CalculatorProfile profile = dailyAggregateRepository.findProfileByRunNumberAndDimension(
                calculatorName, frequency.name(), slaProperties.lookbackDays(frequency),
                runNumber, dimensionValue);

        if (profile.hasSufficientSamples(slaProperties.getMinSampleSize())) {
            writeToCache(key, profile);
            return profile;
        }
        // Fall back to scoped/blended chain for brand-new calcs or new dimension values
        return getProfile(calculatorName, frequency, runNumber);
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
        // Short TTL for "no history yet" so newly-active calculators are picked up sooner.
        Duration ttl = profile.totalRuns() > 0
                ? Duration.ofHours(aggregationProperties.getProfileCacheTtlHours())
                : Duration.ofMinutes(aggregationProperties.getEmptyProfileCacheTtlMinutes());
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(profile), ttl);
        } catch (Exception e) {
            log.warn("event=profile.cache.write outcome=failure key={} error={}", key, e.getMessage());
        }
    }

    private String key(String calculatorName, Frequency frequency, String runNumber, String dimensionValue) {
        String base = PROFILE_PREFIX + calculatorName + ":" + frequency.name();
        String withRn = runNumber != null ? base + ":" + runNumber : base + ":*";
        return dimensionValue != null ? withRn + ":" + dimensionValue : (runNumber != null ? base + ":" + runNumber : base);
    }
}
