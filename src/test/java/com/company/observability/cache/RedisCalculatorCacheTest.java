package com.company.observability.cache;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.util.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the slimmed {@link RedisCalculatorCache} — running-set tracking only.
 *
 * <p>Strategy: Mockito only — verifies the correct Redis set commands are issued.
 */
@ExtendWith(MockitoExtension.class)
class RedisCalculatorCacheTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private SetOperations<String, String> setOps;

    private RedisCalculatorCache cache;

    @BeforeEach
    void setUp() {
        cache = new RedisCalculatorCache(redisTemplate);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOps);
    }

    // ---------------------------------------------------------------
    // trackRunningState — running set membership
    // ---------------------------------------------------------------

    @Test
    void runningRun_addsToRunningSet() {
        CalculatorRun run = TestFixtures.aRunningRun();

        cache.trackRunningState(run);

        String expectedMember = run.getCalculatorId() + ":DAILY";
        verify(setOps).add(eq("obs:running"), eq(expectedMember));
    }

    @Test
    void completedRun_removesFromRunningSet() {
        CalculatorRun run = TestFixtures.aCompletedRun();

        cache.trackRunningState(run);

        String expectedMember = run.getCalculatorId() + ":DAILY";
        verify(setOps).remove(eq("obs:running"), eq(expectedMember));
    }

    // ---------------------------------------------------------------
    // getRunningCalculators
    // ---------------------------------------------------------------

    @Test
    void getRunningCalculators_returnsMembers() {
        when(setOps.members("obs:running")).thenReturn(Set.of("calc-1:DAILY", "calc-2:MONTHLY"));

        assertThat(cache.getRunningCalculators())
                .containsExactlyInAnyOrder("calc-1:DAILY", "calc-2:MONTHLY");
    }

    @Test
    void getRunningCalculators_whenNull_returnsEmpty() {
        when(setOps.members("obs:running")).thenReturn(null);

        assertThat(cache.getRunningCalculators()).isEmpty();
    }

    @Test
    void getRunningCalculators_onRedisException_returnsEmpty() {
        when(setOps.members(any())).thenThrow(new RuntimeException("timeout"));

        assertThat(cache.getRunningCalculators()).isEmpty();
    }
}
