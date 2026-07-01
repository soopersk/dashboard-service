package com.company.observability.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the end-of-day aggregation job and the calculator-profile cache.
 *
 * <p>{@code calculator_sli_daily} is populated by a nightly batch (not per run completion),
 * and slowly-changing per-calculator profiles are cached in Redis with a daily TTL.
 */
@Component
@ConfigurationProperties(prefix = "observability.aggregation")
@Getter
@Setter
public class AggregationProperties {

    private Daily daily = new Daily();

    /**
     * Frequency-aware trailing recompute window (write/settling time): "until when can a
     * reporting_date's runs still change?" Sized to the completion cadence, which differs by an
     * order of magnitude between frequencies — DAILY runs complete T+N business days after the
     * reporting date, MONTHLY (EOM) runs execute in the first ~15 days of the following month.
     * A single generic window cannot serve both. Distinct from {@code observability.sla.lookback}
     * (read/relevance horizon); see the daily-aggregation spec "two-window model".
     */
    private RecomputeWindow recomputeWindow = new RecomputeWindow();

    /** TTL for cached calculator profiles. Slightly over a day so entries survive to the next nightly warm. */
    private int profileCacheTtlHours = 26;

    /** TTL for the "no history yet" sentinel — shorter so newly-active calculators are picked up sooner. */
    private int emptyProfileCacheTtlMinutes = 60;

    /**
     * TTL for a Tier-2 RECENT_EXACT profile (built lazily from raw {@code calculator_runs} when the
     * nightly aggregate has no row for the slice). Shorter than the aggregate TTL so the next nightly
     * recompute supersedes it quickly.
     */
    private int recentProfileCacheTtlHours = 4;

    @Getter
    @Setter
    public static class Daily {
        private boolean enabled = true;
        private String cron = "0 30 0 * * *";
    }

    @Getter
    @Setter
    public static class RecomputeWindow {
        /** DAILY window: >= max T+N completion lag (T+2 over a weekend ~= 4 calendar days). */
        private int dailyDays = 7;
        /** MONTHLY window: sized to a next-month completion (~D+15 from EOM) with margin. */
        private int monthlyDays = 20;
    }
}
