package com.company.observability.service;

import com.company.observability.config.AggregationProperties;
import com.company.observability.config.SlaProperties;
import com.company.observability.domain.CalculatorProfile;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.repository.DailyAggregateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalculatorProfileServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private DailyAggregateRepository dailyAggregateRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CalculatorProfileService service;

    private static final String BLENDED_KEY = "obs:profile:calc-1:DAILY";
    private static final String SCOPED_KEY   = "obs:profile:calc-1:DAILY:1";
    private static final String DIM_KEY      = "obs:profile:calc-1:DAILY:1:WMAP";
    private static final String DIM_NULL_RN_KEY = "obs:profile:calc-1:DAILY:*:WMAP";

    private final CalculatorProfile blended =
            new CalculatorProfile("calc-1", "DAILY", null, null, 600_000L, 300, 360, 10);
    private final CalculatorProfile scoped =
            new CalculatorProfile("calc-1", "DAILY", "1", null, 500_000L, 290, 350, 8);
    private final CalculatorProfile dimProfile =
            new CalculatorProfile("calc-1", "DAILY", "1", "WMAP", 480_000L, 285, 345, 6);

    @BeforeEach
    void setUp() {
        service = new CalculatorProfileService(
                redisTemplate, objectMapper, dailyAggregateRepository,
                new SlaProperties(), new AggregationProperties(), new SimpleMeterRegistry());
    }

    private String json(CalculatorProfile p) {
        try {
            return objectMapper.writeValueAsString(p);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── Blended (2-arg) overload ───────────────────────────────────────────

    @Test
    void cacheHit_returnsCachedProfile_withoutDbCall() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(BLENDED_KEY)).thenReturn(json(blended));

        CalculatorProfile result = service.getProfile("calc-1", Frequency.DAILY);

        assertThat(result.avgDurationMs()).isEqualTo(600_000L);
        verify(dailyAggregateRepository, never()).findProfile(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void cacheMiss_readsFromDb_andCaches() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(BLENDED_KEY)).thenReturn(null);
        when(dailyAggregateRepository.findProfile("calc-1", "DAILY", 30)).thenReturn(blended);

        CalculatorProfile result = service.getProfile("calc-1", Frequency.DAILY);

        assertThat(result.avgDurationMs()).isEqualTo(600_000L);
        verify(valueOps).set(eq(BLENDED_KEY), eq(json(blended)), eq(Duration.ofHours(26)));
    }

    @Test
    void emptyProfile_cachedWithShortTtl() {
        CalculatorProfile empty = new CalculatorProfile("calc-1", "DAILY", null, null, 0, 0, 0, 0);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(BLENDED_KEY)).thenReturn(null);
        when(dailyAggregateRepository.findProfile("calc-1", "DAILY", 30)).thenReturn(empty);

        service.getProfile("calc-1", Frequency.DAILY);

        verify(valueOps).set(eq(BLENDED_KEY), eq(json(empty)), eq(Duration.ofMinutes(60)));
    }

    @Test
    void redisReadFailure_fallsBackToDb() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(BLENDED_KEY)).thenThrow(new RuntimeException("redis down"));
        when(dailyAggregateRepository.findProfile("calc-1", "DAILY", 30)).thenReturn(blended);

        CalculatorProfile result = service.getProfile("calc-1", Frequency.DAILY);

        assertThat(result.avgDurationMs()).isEqualTo(600_000L);
    }

    @Test
    void warm_writesProfileToCache_blendedKey() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service.warm(blended);

        verify(valueOps).set(eq(BLENDED_KEY), eq(json(blended)), any(Duration.class));
    }

    // ── Scoped (3-arg) overload ────────────────────────────────────────────

    @Test
    void scopedOverload_cacheHit_returnsCachedProfile() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(SCOPED_KEY)).thenReturn(json(scoped));

        CalculatorProfile result = service.getProfile("calc-1", Frequency.DAILY, "1");

        assertThat(result.avgDurationMs()).isEqualTo(500_000L);
    }

    @Test
    void scopedOverload_tier1Sufficient_returnsExact() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(SCOPED_KEY)).thenReturn(null);
        when(dailyAggregateRepository.findProfileByRunNumber("calc-1", "DAILY", 30, "1")).thenReturn(scoped);

        CalculatorProfile result = service.getProfile("calc-1", Frequency.DAILY, "1");

        assertThat(result.avgDurationMs()).isEqualTo(500_000L);
        assertThat(result.confidence()).isEqualTo(CalculatorProfile.ProfileConfidence.EXACT);
        verify(valueOps).set(eq(SCOPED_KEY),
                eq(json(scoped.withConfidence(CalculatorProfile.ProfileConfidence.EXACT))),
                eq(Duration.ofHours(26)));
        // Never blends down to the 2-arg profile.
        verify(valueOps, never()).get(BLENDED_KEY);
    }

    @Test
    void scopedOverload_sparseExact_isKept_notDilutedByTier2() {
        CalculatorProfile sparse = new CalculatorProfile("calc-1", "DAILY", "1", null, 450_000L, 280, 340, 2);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(SCOPED_KEY)).thenReturn(null);
        when(dailyAggregateRepository.findProfileByRunNumber("calc-1", "DAILY", 30, "1")).thenReturn(sparse);

        CalculatorProfile result = service.getProfile("calc-1", Frequency.DAILY, "1");

        assertThat(result.avgDurationMs()).isEqualTo(450_000L);
        assertThat(result.confidence()).isEqualTo(CalculatorProfile.ProfileConfidence.SPARSE_EXACT);
        // Sparse-but-exact data short-circuits — Tier 2 is not consulted.
        verify(dailyAggregateRepository, never()).findRecentExactByRunNumber(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void scopedOverload_tier1Empty_fallsToTier2RecentExact() {
        CalculatorProfile empty = new CalculatorProfile("calc-1", "DAILY", "1", null, 0, 0, 0, 0);
        CalculatorProfile recent = new CalculatorProfile("calc-1", "DAILY", "1", null, 510_000L, 295, 355, 3);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(SCOPED_KEY)).thenReturn(null);
        when(dailyAggregateRepository.findProfileByRunNumber("calc-1", "DAILY", 30, "1")).thenReturn(empty);
        when(dailyAggregateRepository.findRecentExactByRunNumber("calc-1", "DAILY", 30, "1")).thenReturn(recent);

        CalculatorProfile result = service.getProfile("calc-1", Frequency.DAILY, "1");

        assertThat(result.avgDurationMs()).isEqualTo(510_000L);
        assertThat(result.confidence()).isEqualTo(CalculatorProfile.ProfileConfidence.RECENT_EXACT);
        verify(valueOps).set(eq(SCOPED_KEY),
                eq(json(recent.withConfidence(CalculatorProfile.ProfileConfidence.RECENT_EXACT))),
                eq(Duration.ofHours(4)));
    }

    @Test
    void scopedOverload_bothTiersMiss_returnsSentinel_notNull() {
        CalculatorProfile empty = new CalculatorProfile("calc-1", "DAILY", "1", null, 0, 0, 0, 0);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(SCOPED_KEY)).thenReturn(null);
        when(dailyAggregateRepository.findProfileByRunNumber("calc-1", "DAILY", 30, "1")).thenReturn(empty);
        when(dailyAggregateRepository.findRecentExactByRunNumber("calc-1", "DAILY", 30, "1")).thenReturn(empty);

        CalculatorProfile result = service.getProfile("calc-1", Frequency.DAILY, "1");

        assertThat(result).isNotNull();
        assertThat(result.totalRuns()).isZero();
        assertThat(result.hasSufficientSamples(5)).isFalse();
        assertThat(result.confidence()).isNull();
        verify(valueOps).set(eq(SCOPED_KEY), anyString(), eq(Duration.ofMinutes(60)));
    }

    // ── Dimension-scoped (4-arg) overload ─────────────────────────────────

    @Test
    void dimScopedOverload_cacheHit_returnsDimProfile() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(DIM_KEY)).thenReturn(json(dimProfile));

        CalculatorProfile result = service.getProfile("calc-1", Frequency.DAILY, "1", "WMAP");

        assertThat(result.avgDurationMs()).isEqualTo(480_000L);
        assertThat(result.dimensionValue()).isEqualTo("WMAP");
    }

    @Test
    void dimScopedOverload_cacheMiss_readsFromDb_andCaches() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(DIM_KEY)).thenReturn(null);
        when(dailyAggregateRepository.findProfileByRunNumberAndDimension("calc-1", "DAILY", 30, "1", "WMAP"))
                .thenReturn(dimProfile);

        CalculatorProfile result = service.getProfile("calc-1", Frequency.DAILY, "1", "WMAP");

        assertThat(result.avgDurationMs()).isEqualTo(480_000L);
        assertThat(result.confidence()).isEqualTo(CalculatorProfile.ProfileConfidence.EXACT);
        verify(valueOps).set(eq(DIM_KEY),
                eq(json(dimProfile.withConfidence(CalculatorProfile.ProfileConfidence.EXACT))),
                eq(Duration.ofHours(26)));
    }

    @Test
    void dimScopedOverload_tier1Empty_fallsToTier2RecentExact_notScoped() {
        CalculatorProfile emptyDim = new CalculatorProfile("calc-1", "DAILY", "1", "WMAP", 0, 0, 0, 0);
        CalculatorProfile recentDim = new CalculatorProfile("calc-1", "DAILY", "1", "WMAP", 490_000L, 286, 346, 2);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(DIM_KEY)).thenReturn(null);
        when(dailyAggregateRepository.findProfileByRunNumberAndDimension("calc-1", "DAILY", 30, "1", "WMAP"))
                .thenReturn(emptyDim);
        when(dailyAggregateRepository.findRecentExactByDimension("calc-1", "DAILY", 30, "1", "WMAP"))
                .thenReturn(recentDim);

        CalculatorProfile result = service.getProfile("calc-1", Frequency.DAILY, "1", "WMAP");

        assertThat(result.avgDurationMs()).isEqualTo(490_000L);
        assertThat(result.confidence()).isEqualTo(CalculatorProfile.ProfileConfidence.RECENT_EXACT);
        // Never collapses to the dimension-agnostic scoped profile.
        verify(valueOps, never()).get(SCOPED_KEY);
        verify(valueOps).set(eq(DIM_KEY),
                eq(json(recentDim.withConfidence(CalculatorProfile.ProfileConfidence.RECENT_EXACT))),
                eq(Duration.ofHours(4)));
    }

    @Test
    void dimScopedOverload_archetypeB_nullRunNumber_usesTier2NullRunNumber() {
        // Archetype B: null run_number, dimension present. Aggregate empty for the slice → Tier 2.
        CalculatorProfile emptyDim = new CalculatorProfile("calc-1", "DAILY", null, "WMAP", 0, 0, 0, 0);
        CalculatorProfile recentDim = new CalculatorProfile("calc-1", "DAILY", null, "WMAP", 470_000L, 280, 340, 4);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(DIM_NULL_RN_KEY)).thenReturn(null);
        when(dailyAggregateRepository.findProfileByRunNumberAndDimension("calc-1", "DAILY", 30, null, "WMAP"))
                .thenReturn(emptyDim);
        when(dailyAggregateRepository.findRecentExactByDimension("calc-1", "DAILY", 30, null, "WMAP"))
                .thenReturn(recentDim);

        CalculatorProfile result = service.getProfile("calc-1", Frequency.DAILY, null, "WMAP");

        assertThat(result.avgDurationMs()).isEqualTo(470_000L);
        assertThat(result.confidence()).isEqualTo(CalculatorProfile.ProfileConfidence.RECENT_EXACT);
    }

    @Test
    void dimScopedOverload_nullRunNumber_usesStarInKey() {
        CalculatorProfile dimNoRn = new CalculatorProfile("calc-1", "DAILY", null, "WMAP", 470_000L, 280, 340, 7);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(DIM_NULL_RN_KEY)).thenReturn(json(dimNoRn));

        CalculatorProfile result = service.getProfile("calc-1", Frequency.DAILY, null, "WMAP");

        assertThat(result.avgDurationMs()).isEqualTo(470_000L);
    }

    @Test
    void dimScopedOverload_nullDimensionValue_delegatesToScopedOverload() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(SCOPED_KEY)).thenReturn(json(scoped));

        CalculatorProfile result = service.getProfile("calc-1", Frequency.DAILY, "1", null);

        assertThat(result.avgDurationMs()).isEqualTo(500_000L);
    }

    @Test
    void warm_writesProfileToCache_dimScopedKey() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service.warm(dimProfile);

        verify(valueOps).set(eq(DIM_KEY), eq(json(dimProfile)), any(Duration.class));
    }
}
