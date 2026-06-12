package com.company.observability.service;

import com.company.observability.config.SlaProperties;
import com.company.observability.domain.CalculatorProfile;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.dto.request.StartRunRequest;
import com.company.observability.exception.DomainValidationException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SlaBaselineResolverTest {

    private SlaProperties slaProperties;
    private SlaBaselineResolver resolver;

    private static final Instant START = Instant.parse("2026-02-22T04:00:00Z");
    private static final long LATE_BAND_MS = 15L * 60 * 1000;

    @BeforeEach
    void setUp() {
        slaProperties = new SlaProperties(); // slaTimezone=UTC
        resolver = new SlaBaselineResolver(slaProperties, new SimpleMeterRegistry());
    }

    private StartRunRequest.StartRunRequestBuilder baseRequest() {
        return StartRunRequest.builder()
                .runId("run-1")
                .calculatorId("calc-1")
                .calculatorName("Calculator 1")
                .frequency(Frequency.DAILY)
                .reportingDate(LocalDate.of(2026, 2, 22))  // Sunday
                .startTime(START);
    }

    private CalculatorProfile profile(long avgDurationMs, int totalRuns) {
        return new CalculatorProfile("calc-1", "DAILY", null, null, avgDurationMs, 0, 0, totalRuns);
    }

    private static final CalculatorProfile EMPTY = new CalculatorProfile("calc-1", "DAILY", null, null, 0, 0, 0, 0);

    // -----------------------------------------------------------------------
    // T+N@HH:mm — explicit offset + cutoff (DAILY only)
    // -----------------------------------------------------------------------

    @Nested
    class TPlusForm {

        @Test
        void tPlus1_fridayReporting_givesMonday() {
            // reportingDate=Fri 2026-02-20, T+1 → Mon 2026-02-23 at 09:30
            StartRunRequest request = baseRequest()
                    .reportingDate(LocalDate.of(2026, 2, 20))  // Friday
                    .slaTime("T+1@09:30").build();

            SlaBaselineResolver.SlaResolution result = resolver.resolve(request, Frequency.DAILY, EMPTY);

            assertThat(result.deadline()).isEqualTo(Instant.parse("2026-02-23T09:30:00Z"));
            assertThat(result.baselineDurationMs()).isNull();
        }

        @Test
        void tPlus2_wednesdayReporting_skipsWeekend_givesFriday() {
            // reportingDate=Wed 2026-02-18, T+2 → Fri 2026-02-20 at 21:30
            StartRunRequest request = baseRequest()
                    .reportingDate(LocalDate.of(2026, 2, 18))  // Wednesday
                    .slaTime("T+2@21:30").build();

            SlaBaselineResolver.SlaResolution result = resolver.resolve(request, Frequency.DAILY, EMPTY);

            assertThat(result.deadline()).isEqualTo(Instant.parse("2026-02-20T21:30:00Z"));
            assertThat(result.baselineDurationMs()).isNull();
        }

        @Test
        void tPlusOffsetIgnoresRunNumber() {
            // T+1 explicit, but runNumber=2 — offset comes from the spec, not run_number.
            StartRunRequest request = baseRequest()
                    .reportingDate(LocalDate.of(2026, 2, 20))  // Friday
                    .runNumber("2")
                    .slaTime("T+1@09:30").build();

            SlaBaselineResolver.SlaResolution result = resolver.resolve(request, Frequency.DAILY, EMPTY);

            assertThat(result.deadline()).isEqualTo(Instant.parse("2026-02-23T09:30:00Z"));
        }

        @Test
        void tPlusRejectedForMonthly() {
            StartRunRequest request = baseRequest()
                    .frequency(Frequency.MONTHLY)
                    .slaTime("T+1@09:30").build();

            assertThrows(DomainValidationException.class,
                    () -> resolver.resolve(request, Frequency.MONTHLY, EMPTY));
        }
    }

    // -----------------------------------------------------------------------
    // Bare HH:mm — DAILY (offset from run_number) / MONTHLY (start-anchored)
    // -----------------------------------------------------------------------

    @Nested
    class BareClockForm {

        @Test
        void daily_defaultRunNumber_givesT2() {
            // reportingDate=Sun 2026-02-22, n=2 (default) → T+2 = Tue 2026-02-24
            StartRunRequest request = baseRequest().slaTime("22:00").build();

            SlaBaselineResolver.SlaResolution result = resolver.resolve(request, Frequency.DAILY, EMPTY);

            assertThat(result.deadline()).isEqualTo(Instant.parse("2026-02-24T22:00:00Z"));
            assertThat(result.baselineDurationMs()).isNull();
        }

        @Test
        void daily_runNumber1_givesT1() {
            StartRunRequest request = baseRequest().slaTime("22:00").runNumber("1").build();

            SlaBaselineResolver.SlaResolution result = resolver.resolve(request, Frequency.DAILY, EMPTY);

            assertThat(result.deadline()).isEqualTo(Instant.parse("2026-02-23T22:00:00Z"));
        }

        @Test
        void daily_runNumber2_givesT2() {
            // reportingDate=Mon 2026-02-23, n=2 → T+2 = Wed 2026-02-25
            StartRunRequest request = baseRequest()
                    .reportingDate(LocalDate.of(2026, 2, 23))  // Monday
                    .slaTime("09:30").runNumber("2").build();

            SlaBaselineResolver.SlaResolution result = resolver.resolve(request, Frequency.DAILY, EMPTY);

            assertThat(result.deadline()).isEqualTo(Instant.parse("2026-02-25T09:30:00Z"));
        }

        @Test
        void daily_fridayReporting_runNumber1_skipsWeekend() {
            StartRunRequest request = baseRequest()
                    .reportingDate(LocalDate.of(2026, 2, 20))  // Friday
                    .slaTime("09:30").runNumber("1").build();

            SlaBaselineResolver.SlaResolution result = resolver.resolve(request, Frequency.DAILY, EMPTY);

            assertThat(result.deadline()).isEqualTo(Instant.parse("2026-02-23T09:30:00Z"));
        }

        @Test
        void monthly_clockBeforeStart_rollsToNextDay() {
            // start 23:00Z, cutoff 02:00 → next-day 02:00
            StartRunRequest request = baseRequest()
                    .frequency(Frequency.MONTHLY)
                    .startTime(Instant.parse("2026-02-22T23:00:00Z"))
                    .slaTime("02:00").build();

            SlaBaselineResolver.SlaResolution result = resolver.resolve(request, Frequency.MONTHLY, EMPTY);

            assertThat(result.deadline()).isEqualTo(Instant.parse("2026-02-23T02:00:00Z"));
            assertThat(result.baselineDurationMs()).isNull();
        }

        @Test
        void monthly_clockAfterStart_sameDay() {
            // start 01:00Z, cutoff 02:00 → same-day 02:00
            StartRunRequest request = baseRequest()
                    .frequency(Frequency.MONTHLY)
                    .startTime(Instant.parse("2026-02-22T01:00:00Z"))
                    .slaTime("02:00").build();

            SlaBaselineResolver.SlaResolution result = resolver.resolve(request, Frequency.MONTHLY, EMPTY);

            assertThat(result.deadline()).isEqualTo(Instant.parse("2026-02-22T02:00:00Z"));
        }

        @Test
        void clockTimeWithSeconds_accepted() {
            StartRunRequest request = baseRequest().slaTime("22:00:30").build();

            SlaBaselineResolver.SlaResolution result = resolver.resolve(request, Frequency.DAILY, EMPTY);

            assertThat(result.deadline()).isEqualTo(Instant.parse("2026-02-24T22:00:30Z"));
        }
    }

    // -----------------------------------------------------------------------
    // ISO-8601 duration — any frequency
    // -----------------------------------------------------------------------

    @Nested
    class DurationForm {

        @Test
        void duration_derivesBaselineAndBufferedDeadline() {
            StartRunRequest request = baseRequest().slaTime("PT2H30M").build();

            SlaBaselineResolver.SlaResolution result = resolver.resolve(request, Frequency.DAILY, EMPTY);

            assertThat(result.baselineDurationMs()).isEqualTo(9_000_000L);
            assertThat(result.deadline()).isEqualTo(START.plusMillis(10_800_000L + LATE_BAND_MS));
        }

        @Test
        void duration_worksForMonthlyToo() {
            StartRunRequest request = baseRequest().frequency(Frequency.MONTHLY).slaTime("PT1H").build();

            SlaBaselineResolver.SlaResolution result = resolver.resolve(request, Frequency.MONTHLY, EMPTY);

            assertThat(result.baselineDurationMs()).isEqualTo(3_600_000L);
        }

        @Test
        void zeroOrNegativeDurationFailsValidation() {
            StartRunRequest zero = baseRequest().slaTime("PT0S").build();
            StartRunRequest negative = baseRequest().slaTime("PT-5M").build();

            assertThrows(DomainValidationException.class,
                    () -> resolver.resolve(zero, Frequency.DAILY, EMPTY));
            assertThrows(DomainValidationException.class,
                    () -> resolver.resolve(negative, Frequency.DAILY, EMPTY));
        }
    }

    // -----------------------------------------------------------------------
    // Blank/null → always-on duration fallback chain (no enabled gate)
    // -----------------------------------------------------------------------

    @Nested
    class BlankFallback {

        @Test
        void expectedDurationMsUsedWhenSlaTimeBlank() {
            StartRunRequest request = baseRequest().slaTime("").expectedDurationMs(300_000L).build();

            SlaBaselineResolver.SlaResolution result = resolver.resolve(request, Frequency.DAILY, EMPTY);

            assertThat(result.baselineDurationMs()).isEqualTo(300_000L);
            assertThat(result.deadline()).isEqualTo(START.plusMillis(360_000L + LATE_BAND_MS));
        }

        @Test
        void profileAverageUsedWhenSlaTimeAndExpectedAbsent() {
            SlaBaselineResolver.SlaResolution result = resolver.resolve(
                    baseRequest().build(), Frequency.DAILY, profile(600_000L, 10));

            assertThat(result.baselineDurationMs()).isEqualTo(600_000L);
            assertThat(result.deadline()).isEqualTo(START.plusMillis(720_000L + LATE_BAND_MS));
        }

        @Test
        void noBaselineAtAll_returnsUngraded() {
            SlaBaselineResolver.SlaResolution result = resolver.resolve(baseRequest().build(), Frequency.DAILY, EMPTY);

            assertThat(result.deadline()).isNull();
            assertThat(result.baselineDurationMs()).isNull();
        }

        @Test
        void nullStartTime_returnsUngraded() {
            StartRunRequest request = baseRequest().startTime(null).slaTime("22:00").build();

            SlaBaselineResolver.SlaResolution result = resolver.resolve(request, Frequency.DAILY, EMPTY);

            assertThat(result.deadline()).isNull();
            assertThat(result.baselineDurationMs()).isNull();
        }
    }

    // -----------------------------------------------------------------------
    // Invalid specs
    // -----------------------------------------------------------------------

    @Nested
    class InvalidSpecs {

        @Test
        void tPlusZeroRejected() {
            assertThrows(DomainValidationException.class,
                    () -> resolver.resolve(baseRequest().slaTime("T+0@09:30").build(), Frequency.DAILY, EMPTY));
        }

        @Test
        void unpaddedHourRejected() {
            assertThrows(DomainValidationException.class,
                    () -> resolver.resolve(baseRequest().slaTime("9:30").build(), Frequency.DAILY, EMPTY));
        }

        @Test
        void tPlusUnpaddedCutoffRejected() {
            assertThrows(DomainValidationException.class,
                    () -> resolver.resolve(baseRequest().slaTime("T+1@9:30").build(), Frequency.DAILY, EMPTY));
        }

        @Test
        void garbageRejected() {
            assertThrows(DomainValidationException.class,
                    () -> resolver.resolve(baseRequest().slaTime("banana").build(), Frequency.DAILY, EMPTY));
        }

        @Test
        void legacyInstantRejected() {
            Instant legacyDeadline = START.plusSeconds(30 * 60);
            assertThrows(DomainValidationException.class,
                    () -> resolver.resolve(baseRequest().slaTime(legacyDeadline.toString()).build(), Frequency.DAILY, EMPTY));
        }
    }
}
