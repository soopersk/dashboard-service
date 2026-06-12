package com.company.observability.repository;

import com.company.observability.domain.CalculatorProfile;
import com.company.observability.domain.DailyAggregate;
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

    // ---------------------------------------------------------------
    // recomputeForDateRange — build aggregate from source runs
    // ---------------------------------------------------------------

    @Test
    void recompute_buildsAggregateFromRuns() {
        insertRun("r1", "calc-1", "tenant-1", "DAILY", DATE, 300, 100L, "SUCCESS", false);
        insertRun("r2", "calc-1", "tenant-1", "DAILY", DATE, 360, 200L, "SUCCESS", false);

        repository.recomputeForDateRange(DATE.minusDays(1), DATE);

        List<DailyAggregate> results = repository.findRecentAggregates("calc-1", 3);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).totalRuns()).isEqualTo(2);
        assertThat(results.get(0).sumDurationMs()).isEqualTo(300L);
        assertThat(results.get(0).avgDurationMs()).isEqualTo(150L);
    }

    @Test
    void recompute_isIdempotent() {
        insertRun("r1", "calc-1", "tenant-1", "DAILY", DATE, 300, 100L, "SUCCESS", false);

        repository.recomputeForDateRange(DATE.minusDays(1), DATE);
        repository.recomputeForDateRange(DATE.minusDays(1), DATE);

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
        repository.recomputeForDateRange(DATE.minusDays(1), DATE);

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
        repository.recomputeForDateRange(DATE.minusDays(1), DATE);

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
        repository.recomputeForDateRange(DATE.minusDays(1), DATE);

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

        repository.recomputeForDateRange(DATE.minusDays(1), DATE);

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
        repository.recomputeForDateRange(DATE.minusDays(1), DATE);
        CalculatorProfile preSplit = repository.findProfile("calc-1", "DAILY", 3);

        clean();

        // Post-split: same two durations, now across distinct regions
        insertRunDim("s1", "calc-1", DATE, "WMAP", "1", 100L);
        insertRunDim("s2", "calc-1", DATE, "EMEA", "1", 300L);
        repository.recomputeForDateRange(DATE.minusDays(1), DATE);
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
        repository.recomputeForDateRange(DATE.minusDays(1), DATE);

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
        repository.recomputeForDateRange(DATE.minusDays(1), DATE);

        List<CalculatorProfile> profiles = repository.findAllProfilesByRunNumberAndDimension("DAILY", 3);

        assertThat(profiles).extracting(CalculatorProfile::dimensionValue)
                .contains("WMAP")
                .doesNotContain("ALL");
    }
}
