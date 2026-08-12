package com.company.observability.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Slowly-changing per-calculator rolling profile, computed once per day from
 * {@code calculator_sli_daily} and cached in Redis. Serves both the SLA baseline
 * (avgDurationMs) and the estimated start/end fallback (avgStartMinUtc/avgEndMinUtc).
 *
 * <p>{@code totalRuns == 0} is a valid "no history" sentinel.
 */
public record CalculatorProfile(
        String calculatorName,
        String frequency,
        String runNumber,
        String dimensionValue,
        long avgDurationMs,
        int avgStartMinUtc,
        int avgEndMinUtc,
        int totalRuns,
        ProfileConfidence confidence
) {
    /**
     * How precise the profile is for the slice that was requested. {@code null} on the
     * zero-sample sentinel and on profiles built before confidence was tracked.
     */
    public enum ProfileConfidence {
        /** ≥ minSampleSize samples from the exact aggregate slice. */
        EXACT,
        /** 1..(minSampleSize-1) samples from the exact aggregate slice — precise, low count. */
        SPARSE_EXACT,
        /** Built from the last N raw runs in {@code calculator_runs} (aggregate had no rows). */
        RECENT_EXACT
    }

    @JsonCreator
    public CalculatorProfile(
            @JsonProperty("calculatorName") String calculatorName,
            @JsonProperty("frequency") String frequency,
            @JsonProperty("runNumber") String runNumber,
            @JsonProperty("dimensionValue") String dimensionValue,
            @JsonProperty("avgDurationMs") long avgDurationMs,
            @JsonProperty("avgStartMinUtc") int avgStartMinUtc,
            @JsonProperty("avgEndMinUtc") int avgEndMinUtc,
            @JsonProperty("totalRuns") int totalRuns,
            @JsonProperty("confidence") ProfileConfidence confidence) {
        this.calculatorName = calculatorName;
        this.frequency = frequency;
        this.runNumber = runNumber;
        this.dimensionValue = dimensionValue;
        this.avgDurationMs = avgDurationMs;
        this.avgStartMinUtc = avgStartMinUtc;
        this.avgEndMinUtc = avgEndMinUtc;
        this.totalRuns = totalRuns;
        this.confidence = confidence;
    }

    /** Convenience constructor for callers that have no confidence yet (sentinels, pre-averaged rows) */
    public CalculatorProfile(String calculatorName, String frequency, String runNumber, String dimensionValue,
                             long avgDurationMs, int avgStartMinUtc, int avgEndMinUtc, int totalRuns) {
        this(calculatorName, frequency, runNumber, dimensionValue,
                avgDurationMs, avgStartMinUtc, avgEndMinUtc, totalRuns, null);
    }

    /** Returns a copy of this profile tagged with the given confidence. */
    public CalculatorProfile withConfidence(ProfileConfidence confidence) {
        return new CalculatorProfile(calculatorName, frequency, runNumber, dimensionValue,
                avgDurationMs, avgStartMinUtc, avgEndMinUtc, totalRuns, confidence);
    }

    /** Zero-sample sentinel for a slice with no history yet. */
    public static CalculatorProfile empty(String calculatorName, String frequency, String runNumber,
                                          String dimensionValue) {
        return new CalculatorProfile(calculatorName, frequency, runNumber, dimensionValue, 0, 0, 0, 0);
    }

    /**
     * Build from summed aggregate columns, computing averages (0 when no runs).
     * Start/end minute-of-day is circular (wraps at midnight): the SIN/COS component sums
     * give the true mean via {@link #circularMeanMinute}; the linear sums remain only as
     * that method's legacy-row/zero-vector fallback.
     *
     * @param runNumber null for blended (cross-run_number) profiles; "1" or "2" for cycle-scoped.
     * @param dimensionValue null for blended/non-dimension profiles; region/run_type value otherwise.
     */
    public static CalculatorProfile fromSums(String calculatorName, String frequency, String runNumber,
                                             String dimensionValue,
                                             long sumDurationMs, long sumStartMinUtc, long sumEndMinUtc,
                                             double sumStartSin, double sumStartCos,
                                             double sumEndSin, double sumEndCos,
                                             int totalRuns) {
        if (totalRuns <= 0) {
            return empty(calculatorName, frequency, runNumber, dimensionValue);
        }
        return new CalculatorProfile(
                calculatorName, frequency, runNumber, dimensionValue,
                sumDurationMs / totalRuns,
                circularMeanMinute(sumStartSin, sumStartCos, sumStartMinUtc, totalRuns),
                circularMeanMinute(sumEndSin, sumEndCos, sumEndMinUtc, totalRuns),
                totalRuns);
    }

    /**
     * Circular mean of a minute-of-day (0–1439) angle from summed unit-circle components
     * ({@code sumSin}/{@code sumCos} = Σ sin/cos of {@code 2π·minute/1440} across the group).
     * Falls back to the linear mean when the resultant vector is exactly zero — either a
     * legacy pre-migration row (sin/cos sums default to 0) or the genuine edge case of runs
     * that cancel exactly (e.g. two runs 12h apart), which has no well-defined circular mean.
     */
    public static int circularMeanMinute(double sumSin, double sumCos, long linearSum, int totalRuns) {
        if (sumSin == 0.0 && sumCos == 0.0) {
            return totalRuns > 0 ? (int) (linearSum / totalRuns) : 0;
        }
        double angle = Math.atan2(sumSin, sumCos);
        if (angle < 0) angle += 2 * Math.PI;
        return (int) Math.round(angle / (2 * Math.PI) * 1440) % 1440;
    }

    public boolean hasSufficientSamples(int minSampleSize) {
        return totalRuns >= minSampleSize && avgDurationMs > 0;
    }

    /** Any real history at all — the bar for *displaying* an estimate, not for SLA grading. */
    public boolean hasAnySample() {
        return totalRuns > 0 && avgDurationMs > 0;
    }
}
