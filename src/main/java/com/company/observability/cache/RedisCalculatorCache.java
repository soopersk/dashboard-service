package com.company.observability.cache;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.domain.enums.RunStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Tracks currently-running calculators in a Redis set.
 * Feeds the {@code INGESTION_RUN_ACTIVE} gauge via {@code countRunning()}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedisCalculatorCache {

    private final StringRedisTemplate redisTemplate;

    private static final String RUNNING_SET = "obs:running";

    /**
     * Maintain the running-set membership for a run on every write.
     * RUNNING → added; any terminal status → removed.
     */
    public void trackRunningState(CalculatorRun run) {
        try {
            Frequency frequency = run.getFrequency();
            String runningKey = run.getCalculatorId() + ":" + frequency.name();
            if (run.getStatus() == RunStatus.RUNNING) {
                redisTemplate.opsForSet().add(RUNNING_SET, runningKey);
                redisTemplate.expire(RUNNING_SET, Duration.ofHours(2));
            } else {
                redisTemplate.opsForSet().remove(RUNNING_SET, runningKey);
            }
            log.debug("event=cache.running.track outcome=success runId={} status={}", run.getRunId(), run.getStatus());
        } catch (Exception e) {
            log.warn("event=cache.running.track outcome=failure runId={} error={}", run.getRunId(), e.getMessage());
        }
    }

    public Set<String> getRunningCalculators() {
        try {
            Set<String> members = redisTemplate.opsForSet().members(RUNNING_SET);
            if (members == null) {
                return Collections.emptySet();
            }
            return new HashSet<>(members);
        } catch (Exception e) {
            log.warn("event=cache.read outcome=failure tier=running_set error={}", e.getMessage());
            return Collections.emptySet();
        }
    }

}
