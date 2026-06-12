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

    /** How many trailing reporting dates the nightly recompute covers (catches late completions). */
    private int recomputeWindowDays = 3;

    /** TTL for cached calculator profiles. Slightly over a day so entries survive to the next nightly warm. */
    private int profileCacheTtlHours = 26;

    /** TTL for the "no history yet" sentinel — shorter so newly-active calculators are picked up sooner. */
    private int emptyProfileCacheTtlMinutes = 60;

    @Getter
    @Setter
    public static class Daily {
        private boolean enabled = true;
        private String cron = "0 30 0 * * *";
    }
}
