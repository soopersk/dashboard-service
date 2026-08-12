package com.company.observability.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CalculatorProfileTest {

    // ---------------------------------------------------------------
    // circularMeanMinute — midnight wraparound (H5)
    // ---------------------------------------------------------------

    @Test
    void circularMeanMinute_midnightWraparound_averagesToMidnight_notNoon() {
        // Two runs 20 minutes apart on the clock (23:50 and 00:10), straddling UTC midnight.
        double sumSin = Math.sin(2 * Math.PI * 1430 / 1440.0) + Math.sin(2 * Math.PI * 10 / 1440.0);
        double sumCos = Math.cos(2 * Math.PI * 1430 / 1440.0) + Math.cos(2 * Math.PI * 10 / 1440.0);

        int mean = CalculatorProfile.circularMeanMinute(sumSin, sumCos, 1430L + 10L, 2);

        assertThat(mean).isEqualTo(0); // not 720 (noon), which the old linear mean produced
    }

    @Test
    void circularMeanMinute_middayCluster_matchesLinearMean() {
        // No wraparound involved — circular and linear means agree.
        double sumSin = Math.sin(2 * Math.PI * 300 / 1440.0) + Math.sin(2 * Math.PI * 360 / 1440.0);
        double sumCos = Math.cos(2 * Math.PI * 300 / 1440.0) + Math.cos(2 * Math.PI * 360 / 1440.0);

        int mean = CalculatorProfile.circularMeanMinute(sumSin, sumCos, 300L + 360L, 2);

        assertThat(mean).isEqualTo(330);
    }

    @Test
    void circularMeanMinute_zeroVector_fallsBackToLinearMean_noException() {
        // sumSin == 0 && sumCos == 0 exactly — legacy row, or a genuine 12h-apart cancellation.
        int mean = CalculatorProfile.circularMeanMinute(0.0, 0.0, 900L, 3);

        assertThat(mean).isEqualTo(300); // 900 / 3, not atan2(0,0)-derived garbage
    }

    @Test
    void circularMeanMinute_zeroVectorAndZeroRuns_returnsZero_noDivideByZero() {
        int mean = CalculatorProfile.circularMeanMinute(0.0, 0.0, 0L, 0);

        assertThat(mean).isZero();
    }

    // ---------------------------------------------------------------
    // fromSums / empty
    // ---------------------------------------------------------------

    @Test
    void fromSums_zeroTotalRuns_returnsEmptySentinel() {
        CalculatorProfile profile = CalculatorProfile.fromSums(
                "calc-1", "DAILY", null, null, 0, 0, 0, 0, 0, 0, 0, 0);

        assertThat(profile).isEqualTo(CalculatorProfile.empty("calc-1", "DAILY", null, null));
        assertThat(profile.totalRuns()).isZero();
        assertThat(profile.avgStartMinUtc()).isZero();
        assertThat(profile.avgEndMinUtc()).isZero();
    }

    @Test
    void fromSums_computesCircularStartAndEndMeans() {
        double startSin = Math.sin(2 * Math.PI * 1430 / 1440.0) + Math.sin(2 * Math.PI * 10 / 1440.0);
        double startCos = Math.cos(2 * Math.PI * 1430 / 1440.0) + Math.cos(2 * Math.PI * 10 / 1440.0);

        CalculatorProfile profile = CalculatorProfile.fromSums(
                "calc-1", "DAILY", null, null,
                200_000L, 1430L + 10L, 300L + 360L,
                startSin, startCos,
                Math.sin(2 * Math.PI * 300 / 1440.0) + Math.sin(2 * Math.PI * 360 / 1440.0),
                Math.cos(2 * Math.PI * 300 / 1440.0) + Math.cos(2 * Math.PI * 360 / 1440.0),
                2);

        assertThat(profile.avgDurationMs()).isEqualTo(100_000L);
        assertThat(profile.avgStartMinUtc()).isEqualTo(0);
        assertThat(profile.avgEndMinUtc()).isEqualTo(330);
    }
}
