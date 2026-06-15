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

    /**
     * Build from summed aggregate columns, computing averages (0 when no runs).
     *
     * @param runNumber null for blended (cross-run_number) profiles; "1" or "2" for cycle-scoped.
     * @param dimensionValue null for blended/non-dimension profiles; region/run_type value otherwise.
     */
    public static CalculatorProfile fromSums(String calculatorName, String frequency, String runNumber,
                                             String dimensionValue,
                                             long sumDurationMs, long sumStartMinUtc, long sumEndMinUtc,
                                             int totalRuns) {
        if (totalRuns <= 0) {
            return new CalculatorProfile(calculatorName, frequency, runNumber, dimensionValue, 0, 0, 0, 0);
        }
        return new CalculatorProfile(
                calculatorName, frequency, runNumber, dimensionValue,
                sumDurationMs / totalRuns,
                (int) (sumStartMinUtc / totalRuns),
                (int) (sumEndMinUtc / totalRuns),
                totalRuns);
    }

    public boolean hasSufficientSamples(int minSampleSize) {
        return totalRuns >= minSampleSize && avgDurationMs > 0;
    }
}
