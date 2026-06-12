package com.company.observability.config;

import com.company.observability.domain.enums.Frequency;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Holds the SLA band and profile-window knobs shared across all {@code slaTime} spec kinds
 * (clock-time {@code T+N@HH:mm}/{@code HH:mm} and ISO-8601 duration). The bands grade a run's
 * actual duration against the frozen {@code slaTime}; {@code lateBandMinutes} is additionally
 * baked into the duration-derived deadline. {@code lookback} is the profile-computation window
 * that feeds baselines.
 */
@Component
@ConfigurationProperties(prefix = "observability.sla")
@Getter
@Setter
public class SlaProperties {

    /**
     * Timezone used when converting an SLA clock time to a UTC instant.
     * All SLA times are UTC by requirement; default {@code UTC}.
     * Must be a valid {@link java.time.ZoneId} string.
     */
    private String slaTimezone = "UTC";

    /**
     * ON_TIME upper edge beyond the frozen {@code slaTime}, in minutes. Used for grading;
     * additionally baked into the duration-derived deadline.
     */
    private int lateBandMinutes = 15;

    /** LATE upper edge beyond the frozen {@code slaTime}, in minutes. Used for grading. */
    private int veryLateBandMinutes = 30;

    /** Runs required in the window before the historical average is trusted. */
    private int minSampleSize = 5;

    /**
     * Percentage buffer applied over a duration-derived baseline (e.g. 20 → baseline * 1.20).
     * Used when {@code slaTime} is an ISO-8601 duration or the blank-slaTime fallback derives
     * a baseline from {@code expectedDurationMs} / profile average.
     */
    private double durationThresholdPercent = 20;

    /** Profile-computation window. Feeds SLA baselines/estimates. */
    private Lookback lookback = new Lookback();

    public long lateBandMs() {
        return lateBandMinutes * 60_000L;
    }

    /** Width of the LATE band: gap between the LATE edge and the VERY_LATE edge. */
    public long bandGapMs() {
        return (long) (veryLateBandMinutes - lateBandMinutes) * 60_000L;
    }

    public int lookbackDays(Frequency frequency) {
        return frequency == Frequency.MONTHLY
                ? lookback.getMonthlyDays()
                : lookback.getDailyDays();
    }

    @Getter
    @Setter
    public static class Lookback {
        private int dailyDays = 30;
        private int monthlyDays = 395;
    }
}
