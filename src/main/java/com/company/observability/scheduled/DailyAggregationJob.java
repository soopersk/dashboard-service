package com.company.observability.scheduled;

import com.company.observability.config.AggregationProperties;
import com.company.observability.config.SlaProperties;
import com.company.observability.domain.CalculatorProfile;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.repository.DailyAggregateRepository;
import com.company.observability.service.CalculatorProfileService;
import com.company.observability.util.MdcContextUtil;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Nightly end-of-day aggregation. Replaces the former per-completion aggregate write:
 * <ol>
 *   <li>Recompute {@code calculator_sli_daily} for a trailing window from {@code calculator_runs}
 *       (idempotent — catches late-arriving completions).</li>
 *   <li>Warm the {@link CalculatorProfileService} cache for all active calculators so run-start
 *       baselines and estimated start/end are served from Redis without a DB query.</li>
 * </ol>
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(
        value = "observability.aggregation.daily.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class DailyAggregationJob {

    private final DailyAggregateRepository dailyAggregateRepository;
    private final CalculatorProfileService calculatorProfileService;
    private final AggregationProperties aggregationProperties;
    private final SlaProperties slaProperties;
    private final MeterRegistry meterRegistry;

    private final AtomicLong lastRecomputedRows = new AtomicLong(0L);
    private final AtomicLong lastProfilesWarmed = new AtomicLong(0L);

    @PostConstruct
    void registerGauges() {
        meterRegistry.gauge("obs.aggregation.recomputed.rows", lastRecomputedRows);
        meterRegistry.gauge("obs.aggregation.profiles.warmed", lastProfilesWarmed);
    }

    @Scheduled(cron = "${observability.aggregation.daily.cron:0 30 0 * * *}")
    public void runDailyAggregation() {
        Map<String, String> snapshot = MdcContextUtil.setJobContext("daily-aggregation");
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDate from = today.minusDays(aggregationProperties.getRecomputeWindowDays());

            int rows = dailyAggregateRepository.recomputeForDateRange(from, today);
            lastRecomputedRows.set(rows);

            long warmed = warmProfiles();
            lastProfilesWarmed.set(warmed);

            log.info("event=aggregation.daily outcome=success from={} to={} rowsRecomputed={} profilesWarmed={}",
                    from, today, rows, warmed);
            meterRegistry.counter("obs.aggregation.execution", "result", "success").increment();
        } catch (Exception e) {
            log.error("event=aggregation.daily outcome=failure", e);
            meterRegistry.counter("obs.aggregation.execution", "result", "failure").increment();
        } finally {
            sample.stop(meterRegistry.timer("obs.aggregation.duration"));
            MdcContextUtil.restoreContext(snapshot);
        }
    }

    private long warmProfiles() {
        long count = 0;
        for (Frequency frequency : Frequency.values()) {
            int lookback = slaProperties.lookbackDays(frequency);

            // Warm blended (cross-run_number) profiles — backward-compat key obs:profile:{name}:{freq}
            List<CalculatorProfile> blended = dailyAggregateRepository.findAllProfiles(
                    frequency.name(), lookback);
            for (CalculatorProfile profile : blended) {
                calculatorProfileService.warm(profile);
                count++;
            }

            // Warm run_number-scoped profiles — key obs:profile:{name}:{freq}:{runNumber}
            List<CalculatorProfile> scoped = dailyAggregateRepository.findAllProfilesByRunNumber(
                    frequency.name(), lookback);
            for (CalculatorProfile profile : scoped) {
                calculatorProfileService.warm(profile);
                count++;
            }

            // Warm dimension-scoped profiles — key obs:profile:{name}:{freq}:{runNumber|*}:{dim}
            List<CalculatorProfile> dimScoped = dailyAggregateRepository.findAllProfilesByRunNumberAndDimension(
                    frequency.name(), lookback);
            for (CalculatorProfile profile : dimScoped) {
                calculatorProfileService.warm(profile);
                count++;
            }
        }
        return count;
    }
}
