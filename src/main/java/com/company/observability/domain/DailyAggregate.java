package com.company.observability.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Daily aggregated metrics per calculator. Pre-computed for fast dashboard queries.
 * Stores sums to avoid the running-average race condition under concurrent upserts (TD-3 fix).
 * Averages are computed at read time via the helper methods below.
 */
public record DailyAggregate(
        String calculatorName,
        LocalDate reportingDate,
        int totalRuns,
        int successRuns,
        int slaBreaches,
        long sumDurationMs,
        long sumStartMinUtc,
        long sumEndMinUtc,
        Instant computedAt
) {
    /** Average run duration in ms. Returns 0 when no runs recorded. */
    public long avgDurationMs() {
        return totalRuns > 0 ? sumDurationMs / totalRuns : 0;
    }

    /** Average start minute (UTC) across all runs. Returns 0 when no runs recorded. */
    public int avgStartMinUtc() {
        return totalRuns > 0 ? (int) (sumStartMinUtc / totalRuns) : 0;
    }

    /** Average end minute (UTC) across all runs. Returns 0 when no runs recorded. */
    public int avgEndMinUtc() {
        return totalRuns > 0 ? (int) (sumEndMinUtc / totalRuns) : 0;
    }
}
