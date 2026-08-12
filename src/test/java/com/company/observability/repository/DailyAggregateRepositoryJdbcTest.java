package com.company.observability.repository;

import com.company.observability.domain.CalculatorProfile;
import com.company.observability.domain.DailyAggregate;
import com.company.observability.domain.enums.Frequency;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Import(DailyAggregateRepository.class)
class DailyAggregateRepositoryJdbcTest extends PostgresJdbcIntegrationTestBase {

    @TestConfiguration
    static class TestBeans {
        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    private DailyAggregateRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Reporting dates must fall inside the partition range created by V2 (yesterday .. +60d).
    private static final LocalDate DATE = LocalDate.now();

    @BeforeEach
    void clean() {
        jdbcTemplate.update("TRUNCATE TABLE calculator_sli_daily");
        jdbcTemplate.update("TRUNCATE TABLE calculator_runs");
    }

    /** Insert one completed run into the partitioned source table. */
    private void insertRun(String runId, String calcId, String tenant, String frequency,
                           LocalDate reportingDate, int startMinUtc, long durationMs,
                           String status, boolean ignored) {
        OffsetDateTime start = reportingDate.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime().plusMinutes(startMinUtc);
        OffsetDateTime end = start.plusNanos(durationMs * 1_000_000L);
        jdbcTemplate.update("""
                INSERT INTO calculator_runs (
                    run_id, calculator_id, calculator_name, tenant_id, frequency, reporting_date,
                    start_time, end_time, duration_ms, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                runId, calcId, calcId, tenant, frequency, reportingDate,
                start, end, durationMs, status);
    }

    /** Insert one completed run carrying a dimension (region) and explicit run_number. */
    private void insertRunDim(String runId, String calcId, LocalDate reportingDate,
                             String region, String runNumber, long durationMs) {
        OffsetDateTime start = reportingDate.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime().plusMinutes(300);
        OffsetDateTime end = start.plusNanos(durationMs * 1_000_000L);
        jdbcTemplate.update("""
                INSERT INTO calculator_runs (
                    run_id, calculator_id, calculator_name, tenant_id, frequency, reporting_date,
                    region, run_number, start_time, end_time, duration_ms, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                runId, calcId, calcId, "tenant-1", "DAILY", reportingDate,
                region, runNumber, start, end, durationMs, "SUCCESS");
    }

    /** Recompute both frequencies over the same range (mirrors the nightly job's two calls). */
    private int recomputeBoth(LocalDate from, LocalDate to) {
        return repository.recomputeForDateRange(from, to, Frequency.DAILY)
                + repository.recomputeForDateRange(from, to, Frequency.MONTHLY);
    }

    /**
     * V2 pre-creates partitions only for [yesterday, +60d]. Tests that need an older reporting_date
     * (recompute-window sizing, Tier-2 lookback) create the daily partition on demand.
     */
    private void ensurePartition(LocalDate date) {
        String name = "calculator_runs_" + date.toString().replace('-', '_');
        jdbcTemplate.update(String.format(
                "CREATE TABLE IF NOT EXISTS %s PARTITION OF calculator_runs FOR VALUES FROM ('%s') TO ('%s')",
                name, date, date.plusDays(1)));
    }

    /** Insert one completed run at an arbitrary reporting_date, creating its partition first. */
    private void insertRunAt(String runId, String calcId, String frequency, LocalDate reportingDate, long durationMs) {
        ensurePartition(reportingDate);
        insertRun(runId, calcId, "tenant-1", frequency, reportingDate, 300, durationMs, "SUCCESS", false);
    }

    // ---------------------------------------------------------------
    // recomputeForDateRange — build aggregate from source runs
    // ---------------------------------------------------------------

    @Test
    void recompute_buildsAggregateFromRuns() {
        insertRun("r1", "calc-1", "tenant-1", "DAILY", DATE, 300, 100L, "SUCCESS", false);
        insertRun("r2", "calc-1", "tenant-1", "DAILY", DATE, 360, 200L, "SUCCESS", false);

        recomputeBoth(DATE.minusDays(1), DATE);

        List<DailyAggregate> results = repository.findRecentAggregates("calc-1", 3);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).totalRuns()).isEqualTo(2);
        assertThat(results.get(0).sumDurationMs()).isEqualTo(300L);
        assertThat(results.get(0).avgDurationMs()).isEqualTo(150L);
    }

    @Test
    void recompute_isIdempotent() {
        insertRun("r1", "calc-1", "tenant-1", "DAILY", DATE, 300, 100L, "SUCCESS", false);

        recomputeBoth(DATE.minusDays(1), DATE);
        recomputeBoth(DATE.minusDays(1), DATE);

        List<DailyAggregate> results = repository.findRecentAggregates("calc-1", 3);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).totalRuns()).isEqualTo(1);
    }

    // ---------------------------------------------------------------
    // Frequency dimension — DAILY and MONTHLY stay separate
    // ---------------------------------------------------------------

    @Test
    void findProfile_separatesByFrequency_evenOnSharedDate() {
        insertRun("d1", "calc-1", "tenant-1", "DAILY", DATE, 300, 100L, "SUCCESS", false);
        insertRun("m1", "calc-1", "tenant-1", "MONTHLY", DATE, 300, 500L, "SUCCESS", false);
        recomputeBoth(DATE.minusDays(1), DATE);

        CalculatorProfile daily = repository.findProfile("calc-1", "DAILY", 3);
        CalculatorProfile monthly = repository.findProfile("calc-1", "MONTHLY", 3);

        assertThat(daily.totalRuns()).isEqualTo(1);
        assertThat(daily.avgDurationMs()).isEqualTo(100L);
        assertThat(monthly.totalRuns()).isEqualTo(1);
        assertThat(monthly.avgDurationMs()).isEqualTo(500L);
    }

    @Test
    void findRecentAggregates_collapsesAcrossFrequency() {
        insertRun("d1", "calc-1", "tenant-1", "DAILY", DATE, 300, 100L, "SUCCESS", false);
        insertRun("m1", "calc-1", "tenant-1", "MONTHLY", DATE, 300, 500L, "SUCCESS", false);
        recomputeBoth(DATE.minusDays(1), DATE);

        List<DailyAggregate> results = repository.findRecentAggregates("calc-1", 3);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).totalRuns()).isEqualTo(2);
        assertThat(results.get(0).sumDurationMs()).isEqualTo(600L);
    }

    @Test
    void findAllProfiles_returnsOneProfilePerCalculatorForFrequency() {
        insertRun("a1", "calc-A", "tenant-1", "DAILY", DATE, 300, 100L, "SUCCESS", false);
        insertRun("a2", "calc-A", "tenant-1", "DAILY", DATE, 360, 300L, "SUCCESS", false);
        insertRun("b1", "calc-B", "tenant-1", "DAILY", DATE, 300, 50L, "SUCCESS", false);
        recomputeBoth(DATE.minusDays(1), DATE);

        List<CalculatorProfile> profiles = repository.findAllProfiles("DAILY", 3);

        assertThat(profiles).hasSize(2);
        assertThat(profiles).extracting(CalculatorProfile::calculatorName)
                .containsExactlyInAnyOrder("calc-A", "calc-B");
    }

    @Test
    void findProfile_noRows_returnsZeroSampleProfile() {
        CalculatorProfile result = repository.findProfile("missing", "DAILY", 30);

        assertThat(result.totalRuns()).isZero();
        assertThat(result.avgDurationMs()).isZero();
    }

    // ---------------------------------------------------------------
    // Dimension split (V9) — per-region rows, blended invariance, null-rn bind
    // ---------------------------------------------------------------

    /** recompute writes one row per dimension value; runs with no region/run_type collapse to 'ALL'. */
    @Test
    void recompute_producesPerDimensionRows_andAllBucketForNonDimensional() {
        insertRunDim("w1", "calc-R", DATE,           "WMAP", "1", 100L);
        insertRunDim("e1", "calc-R", DATE,           "EMEA", "1", 200L);
        insertRunDim("w2", "calc-R", DATE.minusDays(1), "WMAP", "1", 100L);
        insertRunDim("n1", "calc-N", DATE,           null,   "1", 50L);

        recomputeBoth(DATE.minusDays(1), DATE);

        List<String> dimsR = jdbcTemplate.queryForList(
                "SELECT DISTINCT dimension_value FROM calculator_sli_daily WHERE calculator_name = 'calc-R'",
                String.class);
        assertThat(dimsR).containsExactlyInAnyOrder("WMAP", "EMEA");

        List<String> dimsN = jdbcTemplate.queryForList(
                "SELECT DISTINCT dimension_value FROM calculator_sli_daily WHERE calculator_name = 'calc-N'",
                String.class);
        assertThat(dimsN).containsExactly("ALL");
    }

    /** Blended findProfile collapses across dimension: same averages whether or not runs carry a region. */
    @Test
    void findProfile_blendedAveragesIdenticalBeforeAndAfterDimensionSplit() {
        // Pre-split: two runs, no region → both land in 'ALL'
        insertRun("p1", "calc-1", "tenant-1", "DAILY", DATE, 300, 100L, "SUCCESS", false);
        insertRun("p2", "calc-1", "tenant-1", "DAILY", DATE, 300, 300L, "SUCCESS", false);
        recomputeBoth(DATE.minusDays(1), DATE);
        CalculatorProfile preSplit = repository.findProfile("calc-1", "DAILY", 3);

        clean();

        // Post-split: same two durations, now across distinct regions
        insertRunDim("s1", "calc-1", DATE, "WMAP", "1", 100L);
        insertRunDim("s2", "calc-1", DATE, "EMEA", "1", 300L);
        recomputeBoth(DATE.minusDays(1), DATE);
        CalculatorProfile postSplit = repository.findProfile("calc-1", "DAILY", 3);

        assertThat(postSplit.totalRuns()).isEqualTo(preSplit.totalRuns()).isEqualTo(2);
        assertThat(postSplit.avgDurationMs()).isEqualTo(preSplit.avgDurationMs()).isEqualTo(200L);
    }

    /**
     * findProfileByRunNumberAndDimension with a null runNumber exercises the
     * {@code (:runNumber IS NULL OR run_number = :runNumber)} bind against real Postgres
     * (the classic pgjdbc untyped-null pitfall). Must not error and must sum across run_numbers.
     */
    @Test
    void findProfileByRunNumberAndDimension_nullRunNumber_sumsAcrossRunNumbers() {
        insertRunDim("w1", "calc-R", DATE, "WMAP", "1", 100L);
        insertRunDim("w2", "calc-R", DATE, "WMAP", "2", 300L);
        recomputeBoth(DATE.minusDays(1), DATE);

        CalculatorProfile allRns = repository.findProfileByRunNumberAndDimension(
                "calc-R", "DAILY", 3, null, "WMAP");
        assertThat(allRns.totalRuns()).isEqualTo(2);
        assertThat(allRns.avgDurationMs()).isEqualTo(200L);

        CalculatorProfile rn1 = repository.findProfileByRunNumberAndDimension(
                "calc-R", "DAILY", 3, "1", "WMAP");
        assertThat(rn1.totalRuns()).isEqualTo(1);
        assertThat(rn1.avgDurationMs()).isEqualTo(100L);
    }

    /** The nightly third-tier warm query excludes the 'ALL' bucket (covered by blended/scoped keys). */
    @Test
    void findAllProfilesByRunNumberAndDimension_excludesAllBucket() {
        insertRunDim("w1", "calc-R", DATE, "WMAP", "1", 100L);
        insertRunDim("n1", "calc-N", DATE, null,   "1", 50L);
        recomputeBoth(DATE.minusDays(1), DATE);

        List<CalculatorProfile> profiles = repository.findAllProfilesByRunNumberAndDimension("DAILY", 3);

        assertThat(profiles).extracting(CalculatorProfile::dimensionValue)
                .contains("WMAP")
                .doesNotContain("ALL");
    }

    // ---------------------------------------------------------------
    // Single-bucket rule — un-numbered runs collapse to 'ALL' (no fan-out)
    // ---------------------------------------------------------------

    /** Un-numbered runs land in exactly one 'ALL' bucket, counted once — not fanned into '1' AND '2'. */
    @Test
    void recompute_unnumberedRuns_collapseToSingleAllBucket_countedOnce() {
        insertRun("u1", "calc-U", "tenant-1", "DAILY", DATE, 300, 100L, "SUCCESS", false);
        insertRun("u2", "calc-U", "tenant-1", "DAILY", DATE, 360, 200L, "SUCCESS", false);
        insertRun("u3", "calc-U", "tenant-1", "DAILY", DATE, 420, 300L, "SUCCESS", false);

        recomputeBoth(DATE.minusDays(1), DATE);

        List<String> rns = jdbcTemplate.queryForList(
                "SELECT run_number FROM calculator_sli_daily WHERE calculator_name = 'calc-U'", String.class);
        assertThat(rns).containsExactly("ALL");

        Integer total = jdbcTemplate.queryForObject(
                "SELECT total_runs FROM calculator_sli_daily WHERE calculator_name = 'calc-U'", Integer.class);
        assertThat(total).isEqualTo(3);

        // Regression: blended totalRuns equals the real count. Under the old CROSS JOIN fan-out this
        // was doubled to 6 (summed across the fabricated '1' and '2' buckets).
        CalculatorProfile blended = repository.findProfile("calc-U", "DAILY", 3);
        assertThat(blended.totalRuns()).isEqualTo(3);
    }

    /** Numbered runs keep their own per-cycle bucket. */
    @Test
    void recompute_numberedRuns_keepDistinctCycleBuckets() {
        insertRunDim("c1", "calc-C", DATE, null, "1", 100L);
        insertRunDim("c2", "calc-C", DATE, null, "2", 200L);

        recomputeBoth(DATE.minusDays(1), DATE);

        List<String> rns = jdbcTemplate.queryForList(
                "SELECT run_number FROM calculator_sli_daily WHERE calculator_name = 'calc-C' ORDER BY run_number",
                String.class);
        assertThat(rns).containsExactly("1", "2");
    }

    /** The run_number-scoped warm query excludes the 'ALL' bucket (served by the blended key). */
    @Test
    void findAllProfilesByRunNumber_excludesAllBucket() {
        insertRun("u1", "calc-U", "tenant-1", "DAILY", DATE, 300, 100L, "SUCCESS", false); // null rn → 'ALL'
        insertRunDim("c1", "calc-C", DATE, null, "1", 200L);                               // rn '1'

        recomputeBoth(DATE.minusDays(1), DATE);

        List<CalculatorProfile> profiles = repository.findAllProfilesByRunNumber("DAILY", 3);

        assertThat(profiles).extracting(CalculatorProfile::runNumber)
                .contains("1")
                .doesNotContain("ALL");
    }

    /** The third-tier warm query translates the 'ALL' run_number bucket back to null for the read key. */
    @Test
    void findAllProfilesByRunNumberAndDimension_translatesAllRunNumberToNull() {
        insertRunDim("w1", "calc-R", DATE, "WMAP", null, 100L); // region WMAP, un-numbered → 'ALL'

        recomputeBoth(DATE.minusDays(1), DATE);

        List<CalculatorProfile> profiles = repository.findAllProfilesByRunNumberAndDimension("DAILY", 3);

        assertThat(profiles).hasSize(1);
        assertThat(profiles.get(0).dimensionValue()).isEqualTo("WMAP");
        assertThat(profiles.get(0).runNumber()).isNull();
    }

    // ---------------------------------------------------------------
    // Frequency-aware recompute window (write/settling time)
    // ---------------------------------------------------------------

    /**
     * A MONTHLY reporting_date whose runs complete deep into the following month is dropped by a
     * DAILY-sized window but captured by the 20-day MONTHLY window. Regression for the structural
     * gap where {@code calculator_sli_daily} was permanently empty for MONTHLY.
     */
    @Test
    void recompute_monthlyWindow_capturesDateOutsideDailyWindow() {
        LocalDate monthlyDate = DATE.minusDays(10);   // beyond a 7-day daily window, inside a 20-day monthly one
        insertRunAt("m1", "calc-M", "MONTHLY", monthlyDate, 500L);

        // DAILY-sized window (7d) misses it entirely.
        repository.recomputeForDateRange(DATE.minusDays(7), DATE, Frequency.MONTHLY);
        assertThat(repository.findByReportingDates("calc-M", List.of(monthlyDate))).isEmpty();

        // MONTHLY window (20d) captures it.
        repository.recomputeForDateRange(DATE.minusDays(20), DATE, Frequency.MONTHLY);
        List<DailyAggregate> captured = repository.findByReportingDates("calc-M", List.of(monthlyDate));
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).totalRuns()).isEqualTo(1);
    }

    /** A DAILY reporting_date completing T+2 over a weekend (~4 calendar days) is captured under a 7-day window. */
    @Test
    void recompute_dailyWindow_capturesTPlus2FridayCompletion() {
        LocalDate dailyDate = DATE.minusDays(4);
        insertRunAt("d1", "calc-D", "DAILY", dailyDate, 120L);

        // Old generic 3-day window misses it.
        repository.recomputeForDateRange(DATE.minusDays(3), DATE, Frequency.DAILY);
        assertThat(repository.findByReportingDates("calc-D", List.of(dailyDate))).isEmpty();

        // 7-day daily window captures it.
        repository.recomputeForDateRange(DATE.minusDays(7), DATE, Frequency.DAILY);
        assertThat(repository.findByReportingDates("calc-D", List.of(dailyDate))).hasSize(1);
    }

    // ---------------------------------------------------------------
    // Tier-2 cold-start fallback — window is `lookback`, not a hardcoded 90 days
    // ---------------------------------------------------------------

    /**
     * With an empty aggregate, ~6 MONTHLY SUCCESS runs spread across the last year yield only ~3
     * samples under the old hardcoded 90-day window but reach the {@code LIMIT 5} under the MONTHLY
     * lookback (395). Regression for the third, incoherent window collapse.
     */
    @Test
    void findRecentExactByRunNumber_monthlyLookback_reachesFiveSamples() {
        // EOM-ish dates roughly one per month back through the year.
        int[] daysAgo = {20, 50, 80, 110, 140, 170};
        for (int i = 0; i < daysAgo.length; i++) {
            insertRunAt("m" + i, "calc-M", "MONTHLY", DATE.minusDays(daysAgo[i]), 500L + i);
        }

        // Old 90-day window: only the first three dates (20/50/80) qualify.
        CalculatorProfile narrow = repository.findRecentExactByRunNumber("calc-M", "MONTHLY", 90, null);
        assertThat(narrow.totalRuns()).isEqualTo(3);

        // MONTHLY lookback (395): reaches the LIMIT 5 cap.
        CalculatorProfile wide = repository.findRecentExactByRunNumber("calc-M", "MONTHLY", 395, null);
        assertThat(wide.totalRuns()).isEqualTo(5);
    }

    // ---------------------------------------------------------------
    // Circular mean for start/end minute-of-day (H5 — midnight wraparound)
    // ---------------------------------------------------------------

    @Test
    void recompute_populatesSinCosColumns() {
        insertRun("r1", "calc-1", "tenant-1", "DAILY", DATE, 300, 100L, "SUCCESS", false);

        recomputeBoth(DATE.minusDays(1), DATE);

        Double sin = jdbcTemplate.queryForObject(
                "SELECT sum_start_sin FROM calculator_sli_daily WHERE calculator_name = 'calc-1'", Double.class);
        Double cos = jdbcTemplate.queryForObject(
                "SELECT sum_start_cos FROM calculator_sli_daily WHERE calculator_name = 'calc-1'", Double.class);
        assertThat(sin).isNotZero();
        assertThat(cos).isNotZero();
    }

    /** Circular mean of 23:50 and 00:10 is 00:00 (midnight), not 12:00 (noon) from the old linear mean. */
    @Test
    void findProfile_circularMean_handlesMidnightWraparound() {
        insertRun("r1", "calc-1", "tenant-1", "DAILY", DATE, 1430, 100L, "SUCCESS", false); // 23:50
        insertRun("r2", "calc-1", "tenant-1", "DAILY", DATE, 1450, 100L, "SUCCESS", false); // rolls to next day 00:10

        recomputeBoth(DATE.minusDays(1), DATE);

        CalculatorProfile profile = repository.findProfile("calc-1", "DAILY", 3);

        assertThat(profile.totalRuns()).isEqualTo(2);
        assertThat(profile.avgStartMinUtc()).isEqualTo(0);
    }

    /** Runs clustered away from midnight: circular and linear means agree — no regression. */
    @Test
    void findProfile_circularMean_middayCluster_unaffectedByFix() {
        insertRun("r1", "calc-1", "tenant-1", "DAILY", DATE, 300, 100L, "SUCCESS", false); // 05:00
        insertRun("r2", "calc-1", "tenant-1", "DAILY", DATE, 360, 100L, "SUCCESS", false); // 06:00

        recomputeBoth(DATE.minusDays(1), DATE);

        CalculatorProfile profile = repository.findProfile("calc-1", "DAILY", 3);

        assertThat(profile.avgStartMinUtc()).isEqualTo(330); // (300 + 360) / 2, same as the old linear mean
    }

    /** A row written before V11 (sin/cos still at their DEFAULT 0) reads via the legacy linear fallback. */
    @Test
    void findProfile_legacyRow_sinCosDefaultZero_fallsBackToLinearMean() {
        jdbcTemplate.update("""
                INSERT INTO calculator_sli_daily (
                    calculator_name, frequency, reporting_date, run_number, dimension_value,
                    total_runs, success_runs, sla_breaches,
                    sum_duration_ms, sum_start_min_utc, sum_end_min_utc, computed_at)
                VALUES ('calc-legacy', 'DAILY', ?, 'ALL', 'ALL', 2, 2, 0, 200000, 900, 960, NOW())
                """, DATE);

        CalculatorProfile profile = repository.findProfile("calc-legacy", "DAILY", 3);

        assertThat(profile.totalRuns()).isEqualTo(2);
        assertThat(profile.avgStartMinUtc()).isEqualTo(450); // 900 / 2, via the sin==0 && cos==0 fallback
        assertThat(profile.avgEndMinUtc()).isEqualTo(480);   // 960 / 2
    }

    /** Tier-2 (findRecentExact) got a surgical circular-mean fix too — verify it independently of Tier 1. */
    @Test
    void findRecentExactBlended_circularMean_handlesMidnightWraparound() {
        insertRun("r1", "calc-2", "tenant-1", "DAILY", DATE, 1430, 100L, "SUCCESS", false); // 23:50
        insertRun("r2", "calc-2", "tenant-1", "DAILY", DATE, 1450, 100L, "SUCCESS", false); // 00:10 next day

        CalculatorProfile profile = repository.findRecentExactBlended("calc-2", "DAILY", 3);

        assertThat(profile.totalRuns()).isEqualTo(2);
        assertThat(profile.avgStartMinUtc()).isEqualTo(0);
        assertThat(profile.avgDurationMs()).isEqualTo(100L); // unchanged rounding, only the minute columns changed
    }

    /** Spot-check the nightly-warm query (distinct GROUP BY shape) also carries the circular mean through. */
    @Test
    void findAllProfiles_circularMean_handlesMidnightWraparound() {
        insertRun("r1", "calc-1", "tenant-1", "DAILY", DATE, 1430, 100L, "SUCCESS", false); // 23:50
        insertRun("r2", "calc-1", "tenant-1", "DAILY", DATE, 1450, 100L, "SUCCESS", false); // 00:10 next day

        recomputeBoth(DATE.minusDays(1), DATE);

        List<CalculatorProfile> profiles = repository.findAllProfiles("DAILY", 3);

        assertThat(profiles).hasSize(1);
        assertThat(profiles.get(0).avgStartMinUtc()).isEqualTo(0);
    }
}
