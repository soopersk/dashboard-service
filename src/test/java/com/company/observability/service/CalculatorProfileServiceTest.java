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
    void scopedOverload_insufficientSamples_fallsBackToBlended() {
        CalculatorProfile thinScoped = new CalculatorProfile("calc-1", "DAILY", "1", null, 0, 0, 0, 0);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(SCOPED_KEY)).thenReturn(null);
        when(dailyAggregateRepository.findProfileByRunNumber("calc-1", "DAILY", 30, "1")).thenReturn(thinScoped);
        when(valueOps.get(BLENDED_KEY)).thenReturn(json(blended));

        CalculatorProfile result = service.getProfile("calc-1", Frequency.DAILY, "1");

        assertThat(result.avgDurationMs()).isEqualTo(600_000L);
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
        verify(valueOps).set(eq(DIM_KEY), eq(json(dimProfile)), any(Duration.class));
    }

    @Test
    void dimScopedOverload_insufficientSamples_fallsBackToScoped() {
        CalculatorProfile thinDim = new CalculatorProfile("calc-1", "DAILY", "1", "WMAP", 0, 0, 0, 0);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(DIM_KEY)).thenReturn(null);
        when(dailyAggregateRepository.findProfileByRunNumberAndDimension("calc-1", "DAILY", 30, "1", "WMAP"))
                .thenReturn(thinDim);
        // Falls back to scoped key
        when(valueOps.get(SCOPED_KEY)).thenReturn(json(scoped));

        CalculatorProfile result = service.getProfile("calc-1", Frequency.DAILY, "1", "WMAP");

        assertThat(result.avgDurationMs()).isEqualTo(500_000L);
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
