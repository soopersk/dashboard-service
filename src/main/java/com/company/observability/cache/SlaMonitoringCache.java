package com.company.observability.cache;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.RunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static com.company.observability.util.ObservabilityConstants.CACHE_REDIS_DURATION;

/**
 * LIVE SLA MONITORING using Redis Sorted Set
 * Tracks all running calculators with their SLA deadlines
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlaMonitoringCache {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Value("${observability.sla.live-tracking.enabled:true}")
    private boolean liveTrackingEnabled;

    // Sorted set: score = SLA deadline timestamp (epoch millis)
    private static final String SLA_DEADLINES_ZSET = "obs:sla:deadlines";

    // Hash: runId -> minimal run info
    private static final String SLA_RUN_INFO_HASH = "obs:sla:run_info";

    /**
     * Register a calculator run for SLA monitoring
     * Called when run starts
     */
    public void registerForSlaMonitoring(CalculatorRun run) {
        if (!liveTrackingEnabled) {
            log.debug("event=sla.monitor.register outcome=rejected reason=tracking_disabled runId={}", run.getRunId());
            return;
        }

        if (run.getSlaTime() == null) {
            log.debug("event=sla.monitor.register outcome=rejected reason=no_sla_time runId={}", run.getRunId());
            return;
        }

        if (run.getStatus() != RunStatus.RUNNING) {
            log.debug("event=sla.monitor.register outcome=rejected reason=not_running runId={}", run.getRunId());
            return;
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            String runKey = buildRunKey(run.getTenantId(), run.getRunId(), run.getReportingDate());

            long slaDeadlineScore = run.getSlaTime().toEpochMilli();

            Map<String, Object> runInfo = new HashMap<>();
            runInfo.put("runKey", runKey);
            runInfo.put("runId", run.getRunId());
            runInfo.put("calculatorId", run.getCalculatorId());
            runInfo.put("calculatorName", run.getCalculatorName());
            runInfo.put("tenantId", run.getTenantId());
            runInfo.put("reportingDate", run.getReportingDate() != null ? run.getReportingDate().toString() : null);
            runInfo.put("startTime", run.getStartTime().toEpochMilli());
            runInfo.put("slaTime", run.getSlaTime().toEpochMilli());

            String runInfoJson = objectMapper.writeValueAsString(runInfo);

            // No structure-level TTL: an expiry on the whole zset/hash silently drops every
            // monitored run (e.g. a long run registered before a quiet weekend). Members are
            // garbage-collected by deregistration (completion + live-job terminal checks) and
            // backstopped by the DB sweep in LiveSlaBreachDetectionJob.
            redisTemplate.opsForZSet().add(SLA_DEADLINES_ZSET, runKey, slaDeadlineScore);
            redisTemplate.opsForHash().put(SLA_RUN_INFO_HASH, runKey, runInfoJson);

            log.debug("event=sla.monitor.register outcome=success runId={} deadline={}",
                    run.getRunId(), run.getSlaTime());

        } catch (Exception e) {
            log.error("event=sla.monitor.register outcome=failure runId={}", run.getRunId(), e);
        } finally {
            sample.stop(meterRegistry.timer(CACHE_REDIS_DURATION, "operation", "register", "tier", "sla"));
        }
    }

    /**
     * Deregister a run (called when run completes)
     */
    public void deregisterFromSlaMonitoring(String runId, String tenantId, LocalDate reportingDate) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            String runKey = buildRunKey(tenantId, runId, reportingDate);
            redisTemplate.opsForZSet().remove(SLA_DEADLINES_ZSET, runKey);
            redisTemplate.opsForHash().delete(SLA_RUN_INFO_HASH, runKey);

            log.debug("event=sla.monitor.deregister outcome=success runId={}", runId);

        } catch (Exception e) {
            log.error("event=sla.monitor.deregister outcome=failure runId={}", runId, e);
        } finally {
            sample.stop(meterRegistry.timer(CACHE_REDIS_DURATION, "operation", "deregister", "tier", "sla"));
        }
    }

    /**
     * Get all runs that have exceeded their SLA deadline
     * Score range: -∞ to NOW
     */
    public List<Map<String, Object>> getBreachedRuns() {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            long now = Instant.now().toEpochMilli();

            Set<String> breachedRunKeys = redisTemplate.opsForZSet()
                    .rangeByScore(SLA_DEADLINES_ZSET, 0, now);

            if (breachedRunKeys == null || breachedRunKeys.isEmpty()) {
                return Collections.emptyList();
            }

            List<Map<String, Object>> breachedRuns = new ArrayList<>();

            for (String runKey : breachedRunKeys) {
                String runInfoJson = (String) redisTemplate.opsForHash().get(SLA_RUN_INFO_HASH, runKey);

                if (runInfoJson != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> runInfo = objectMapper.readValue(runInfoJson, Map.class);
                    breachedRuns.add(runInfo);
                }
            }

            log.debug("event=sla.monitor.read_breached outcome=success count={}", breachedRuns.size());

            return breachedRuns;

        } catch (Exception e) {
            log.error("event=sla.monitor.read_breached outcome=failure", e);
            return Collections.emptyList();
        } finally {
            sample.stop(meterRegistry.timer(CACHE_REDIS_DURATION, "operation", "read_breached", "tier", "sla"));
        }
    }

    /**
     * Get runs approaching SLA deadline (within next N minutes)
     */
    public List<Map<String, Object>> getApproachingSlaRuns(int minutesAhead) {
        try {
            long now = Instant.now().toEpochMilli();
            long threshold = Instant.now().plus(Duration.ofMinutes(minutesAhead)).toEpochMilli();

            // Get runs with SLA deadline between NOW and NOW+N minutes
            Set<String> approachingRunKeys = redisTemplate.opsForZSet()
                    .rangeByScore(SLA_DEADLINES_ZSET, now, threshold);

            if (approachingRunKeys == null || approachingRunKeys.isEmpty()) {
                return Collections.emptyList();
            }

            List<Map<String, Object>> approachingRuns = new ArrayList<>();

            for (String runKey : approachingRunKeys) {
                String runInfoJson = (String) redisTemplate.opsForHash().get(SLA_RUN_INFO_HASH, runKey);

                if (runInfoJson != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> runInfo = objectMapper.readValue(runInfoJson, Map.class);
                    approachingRuns.add(runInfo);
                }
            }

            log.debug("event=sla.monitor.read_approaching outcome=success count={} minutesAhead={}",
                    approachingRuns.size(), minutesAhead);

            return approachingRuns;

        } catch (Exception e) {
            log.error("event=sla.monitor.read_approaching outcome=failure", e);
            return Collections.emptyList();
        }
    }

    /**
     * Get count of currently monitored runs
     */
    public long getMonitoredRunCount() {
        try {
            Long count = redisTemplate.opsForZSet().size(SLA_DEADLINES_ZSET);
            return count != null ? count : 0;
        } catch (Exception e) {
            log.error("event=sla.monitor.count outcome=failure", e);
            return 0;
        }
    }

    private String buildRunKey(String tenantId, String runId, LocalDate reportingDate) {
        String tenant = tenantId != null ? tenantId : "unknown-tenant";
        String date = reportingDate != null ? reportingDate.toString() : "unknown-date";
        String id = runId != null ? runId : "unknown-run";
        return tenant + ":" + id + ":" + date;
    }
}
