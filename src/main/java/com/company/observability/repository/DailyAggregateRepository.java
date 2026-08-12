package com.company.observability.repository;

import com.company.observability.domain.CalculatorProfile;
import com.company.observability.domain.DailyAggregate;
import com.company.observability.domain.enums.Frequency;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.*;
import java.time.LocalDate;
import java.util.*;

import static com.company.observability.util.ObservabilityConstants.*;
import static com.company.observability.util.TimeUtils.fromTimestamp;

/**
 * Daily aggregate repository with reporting_date alignment
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class DailyAggregateRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;

    /**
     * Recompute the aggregate for a trailing reporting-date range from the source of truth
     * ({@code calculator_runs}), grouped by (calculator_name, frequency, reporting_date).
     * Idempotent: deletes the range then rebuilds it. Only completed runs
     * ({@code end_time IS NOT NULL}) are aggregated; start/end minutes are UTC.
     *
     * <p>Called by the nightly {@code DailyAggregationJob}.
     */
    @Transactional
    public int recomputeForDateRange(LocalDate fromInclusive, LocalDate toInclusive, Frequency frequency) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", fromInclusive)
                .addValue("to", toInclusive)
                .addValue("frequency", frequency.name());

        jdbcTemplate.update(
                "DELETE FROM calculator_sli_daily WHERE reporting_date BETWEEN :from AND :to"
                        + " AND frequency = :frequency", params);

        // Single-pass, single-bucket rule: run_number is a real dimension only for
        // run-number-aware calculators. Every run lands in EXACTLY ONE bucket — numbered runs
        // keep their cycle ('1','2',…); un-numbered runs collapse to the canonical 'ALL' bucket
        // (mirroring the dimension_value = 'ALL' sentinel). Each run is counted once — no fan-out,
        // no double-count. Reads route by classification (aware → cycle slice, agnostic → 'ALL').
        String insert = """
            INSERT INTO calculator_sli_daily (
                calculator_name, frequency, reporting_date, run_number, dimension_value,
                total_runs, success_runs, sla_breaches,
                sum_duration_ms, sum_start_min_utc, sum_end_min_utc,
                sum_start_sin, sum_start_cos, sum_end_sin, sum_end_cos, computed_at
            )
            SELECT
                calculator_name, frequency, reporting_date,
                COALESCE(run_number, 'ALL') AS run_number,
                COALESCE(region, run_type, 'ALL') AS dimension_value,
                COUNT(*),
                COUNT(*) FILTER (WHERE status = 'SUCCESS'),
                COUNT(*) FILTER (WHERE sla_breached),
                COALESCE(SUM(duration_ms), 0),
                COALESCE(SUM(
                    EXTRACT(HOUR   FROM start_time AT TIME ZONE 'UTC') * 60 +
                    EXTRACT(MINUTE FROM start_time AT TIME ZONE 'UTC')
                ), 0),
                COALESCE(SUM(
                    CASE WHEN end_time IS NOT NULL THEN
                        EXTRACT(HOUR   FROM end_time AT TIME ZONE 'UTC') * 60 +
                        EXTRACT(MINUTE FROM end_time AT TIME ZONE 'UTC')
                    ELSE 0 END
                ), 0),
                COALESCE(SUM(
                    SIN(2 * PI() * (
                        EXTRACT(HOUR   FROM start_time AT TIME ZONE 'UTC') * 60 +
                        EXTRACT(MINUTE FROM start_time AT TIME ZONE 'UTC')
                    ) / 1440.0)
                ), 0),
                COALESCE(SUM(
                    COS(2 * PI() * (
                        EXTRACT(HOUR   FROM start_time AT TIME ZONE 'UTC') * 60 +
                        EXTRACT(MINUTE FROM start_time AT TIME ZONE 'UTC')
                    ) / 1440.0)
                ), 0),
                COALESCE(SUM(
                    CASE WHEN end_time IS NOT NULL THEN
                        SIN(2 * PI() * (
                            EXTRACT(HOUR   FROM end_time AT TIME ZONE 'UTC') * 60 +
                            EXTRACT(MINUTE FROM end_time AT TIME ZONE 'UTC')
                        ) / 1440.0)
                    ELSE 0 END
                ), 0),
                COALESCE(SUM(
                    CASE WHEN end_time IS NOT NULL THEN
                        COS(2 * PI() * (
                            EXTRACT(HOUR   FROM end_time AT TIME ZONE 'UTC') * 60 +
                            EXTRACT(MINUTE FROM end_time AT TIME ZONE 'UTC')
                        ) / 1440.0)
                    ELSE 0 END
                ), 0),
                NOW()
            FROM calculator_runs
            WHERE end_time IS NOT NULL
              AND frequency = :frequency
              AND reporting_date BETWEEN :from AND :to
            GROUP BY calculator_name, frequency, reporting_date,
                     COALESCE(run_number, 'ALL'),
                     COALESCE(region, run_type, 'ALL')
            """;

        try {
            Timer.Sample sample = Timer.start(meterRegistry);
            int inserted = jdbcTemplate.update(insert, params);
            sample.stop(Timer.builder(DB_QUERY_DURATION).tag("query", "recompute_daily").register(meterRegistry));
            return inserted;
        } catch (Exception e) {
            log.error("event=daily_aggregate.recompute outcome=failure frequency={} from={} to={}",
                    frequency, fromInclusive, toInclusive, e);
            throw new RuntimeException("Failed to recompute daily aggregates", e);
        }
    }

    /**
     * Fetch recent aggregates for trending. Collapses across frequency so callers that
     * are not frequency-scoped (trends, sla-summary, runtime) see one row per reporting
     * date — preserving behavior from before the frequency dimension was added.
     */
    public List<DailyAggregate> findRecentAggregates(String calculatorName, int days) {

        String sql = """
            SELECT calculator_name, reporting_date,
                   SUM(total_runs)        AS total_runs,
                   SUM(success_runs)      AS success_runs,
                   SUM(sla_breaches)      AS sla_breaches,
                   SUM(sum_duration_ms)   AS sum_duration_ms,
                   SUM(sum_start_min_utc) AS sum_start_min_utc,
                   SUM(sum_end_min_utc)   AS sum_end_min_utc,
                   MAX(computed_at)       AS computed_at
            FROM calculator_sli_daily
            WHERE calculator_name = :calculatorName
            AND reporting_date >= CURRENT_DATE - CAST(:days AS INTEGER) * INTERVAL '1 day'
            GROUP BY calculator_name, reporting_date
            ORDER BY reporting_date DESC
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("calculatorName", calculatorName)
                .addValue("days", days);

        try {
            Timer.Sample sample = Timer.start(meterRegistry);
            List<DailyAggregate> results = jdbcTemplate.query(sql, params, new DailyAggregateRowMapper());
            sample.stop(Timer.builder(DB_QUERY_DURATION).tag("query", "find_recent_agg").register(meterRegistry));
            return results;
        } catch (Exception e) {
            log.error("event=daily_aggregate.find_recent outcome=failure calculator_name={}", calculatorName, e);
            throw new RuntimeException("Failed to fetch daily aggregates", e);
        }
    }

    /**
     * Get aggregates for specific reporting dates (for MONTHLY calculators).
     * NPJT expands :reportingDates list into the IN clause automatically.
     */
    public List<DailyAggregate> findByReportingDates(
            String calculatorName, List<LocalDate> reportingDates) {

        if (reportingDates == null || reportingDates.isEmpty()) {
            return Collections.emptyList();
        }

        String sql = """
            SELECT calculator_name, reporting_date,
                   SUM(total_runs)        AS total_runs,
                   SUM(success_runs)      AS success_runs,
                   SUM(sla_breaches)      AS sla_breaches,
                   SUM(sum_duration_ms)   AS sum_duration_ms,
                   SUM(sum_start_min_utc) AS sum_start_min_utc,
                   SUM(sum_end_min_utc)   AS sum_end_min_utc,
                   MAX(computed_at)       AS computed_at
            FROM calculator_sli_daily
            WHERE calculator_name = :calculatorName
            AND reporting_date IN (:reportingDates)
            GROUP BY calculator_name, reporting_date
            ORDER BY reporting_date DESC
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("calculatorName", calculatorName)
                .addValue("reportingDates", reportingDates);

        try {
            return jdbcTemplate.query(sql, params, new DailyAggregateRowMapper());
        } catch (Exception e) {
            log.error("event=daily_aggregate.find_by_dates outcome=failure calculator_name={}", calculatorName, e);
            throw new RuntimeException("Failed to fetch aggregates by date", e);
        }
    }

    /**
     * Frequency-scoped rolling profile for one calculator over a trailing-day window
     * (avg duration + avg start/end minute). Cache-aside source for
     * {@code CalculatorProfileService}. Returns a zero-sample profile when no history exists.
     */
    public CalculatorProfile findProfile(String calculatorName, String frequency, int days) {

        String sql = """
            SELECT COALESCE(SUM(sum_duration_ms), 0)   AS sum_duration_ms,
                   COALESCE(SUM(sum_start_min_utc), 0) AS sum_start_min_utc,
                   COALESCE(SUM(sum_end_min_utc), 0)   AS sum_end_min_utc,
                   COALESCE(SUM(sum_start_sin), 0)     AS sum_start_sin,
                   COALESCE(SUM(sum_start_cos), 0)     AS sum_start_cos,
                   COALESCE(SUM(sum_end_sin), 0)       AS sum_end_sin,
                   COALESCE(SUM(sum_end_cos), 0)       AS sum_end_cos,
                   COALESCE(SUM(total_runs), 0)        AS total_runs
            FROM calculator_sli_daily
            WHERE calculator_name = :calculatorName
            AND frequency = :frequency
            AND reporting_date >= CURRENT_DATE - CAST(:days AS INTEGER) * INTERVAL '1 day'
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("calculatorName", calculatorName)
                .addValue("frequency", frequency)
                .addValue("days", days);

        try {
            return jdbcTemplate.queryForObject(sql, params, (rs, rowNum) -> CalculatorProfile.fromSums(
                    calculatorName, frequency, null, null,
                    rs.getLong("sum_duration_ms"), rs.getLong("sum_start_min_utc"), rs.getLong("sum_end_min_utc"),
                    rs.getDouble("sum_start_sin"), rs.getDouble("sum_start_cos"),
                    rs.getDouble("sum_end_sin"), rs.getDouble("sum_end_cos"),
                    rs.getInt("total_runs")));
        } catch (Exception e) {
            log.error("event=daily_aggregate.find_profile outcome=failure calculator_name={} frequency={}",
                    calculatorName, frequency, e);
            return CalculatorProfile.empty(calculatorName, frequency, null, null);
        }
    }

    /**
     * Compute profiles for all active calculators of one frequency over a trailing-day window
     * in a single query. Collapses across run_number — used by the nightly job to warm
     * the blended (non-run_number-scoped) profile cache keys.
     */
    public List<CalculatorProfile> findAllProfiles(String frequency, int days) {
        String sql = """
            SELECT calculator_name,
                   SUM(sum_duration_ms)   AS sum_duration_ms,
                   SUM(sum_start_min_utc) AS sum_start_min_utc,
                   SUM(sum_end_min_utc)   AS sum_end_min_utc,
                   SUM(sum_start_sin)     AS sum_start_sin,
                   SUM(sum_start_cos)     AS sum_start_cos,
                   SUM(sum_end_sin)       AS sum_end_sin,
                   SUM(sum_end_cos)       AS sum_end_cos,
                   SUM(total_runs)        AS total_runs
            FROM calculator_sli_daily
            WHERE frequency = :frequency
            AND reporting_date >= CURRENT_DATE - CAST(:days AS INTEGER) * INTERVAL '1 day'
            GROUP BY calculator_name
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("frequency", frequency)
                .addValue("days", days);

        try {
            return jdbcTemplate.query(sql, params, (rs, rowNum) -> CalculatorProfile.fromSums(
                    rs.getString("calculator_name"), frequency, null, null,
                    rs.getLong("sum_duration_ms"), rs.getLong("sum_start_min_utc"), rs.getLong("sum_end_min_utc"),
                    rs.getDouble("sum_start_sin"), rs.getDouble("sum_start_cos"),
                    rs.getDouble("sum_end_sin"), rs.getDouble("sum_end_cos"),
                    rs.getInt("total_runs")));
        } catch (Exception e) {
            log.error("event=daily_aggregate.find_all_profiles outcome=failure frequency={}", frequency, e);
            return Collections.emptyList();
        }
    }

    /**
     * Per-run_number profiles for all active calculators. Used by the nightly job to warm
     * run_number-scoped cache keys ({@code obs:profile:{name}:{freq}:{runNumber}}).
     * Excludes the 'ALL' bucket — un-numbered runs are already served by the blended key
     * that {@link #findAllProfiles} warms (mirrors the {@code dimension_value <> 'ALL'} filter).
     */
    public List<CalculatorProfile> findAllProfilesByRunNumber(String frequency, int days) {
        String sql = """
            SELECT calculator_name, run_number,
                   SUM(sum_duration_ms)   AS sum_duration_ms,
                   SUM(sum_start_min_utc) AS sum_start_min_utc,
                   SUM(sum_end_min_utc)   AS sum_end_min_utc,
                   SUM(sum_start_sin)     AS sum_start_sin,
                   SUM(sum_start_cos)     AS sum_start_cos,
                   SUM(sum_end_sin)       AS sum_end_sin,
                   SUM(sum_end_cos)       AS sum_end_cos,
                   SUM(total_runs)        AS total_runs
            FROM calculator_sli_daily
            WHERE frequency = :frequency
            AND run_number <> 'ALL'
            AND reporting_date >= CURRENT_DATE - CAST(:days AS INTEGER) * INTERVAL '1 day'
            GROUP BY calculator_name, run_number
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("frequency", frequency)
                .addValue("days", days);

        try {
            return jdbcTemplate.query(sql, params, (rs, rowNum) -> CalculatorProfile.fromSums(
                    rs.getString("calculator_name"), frequency, rs.getString("run_number"), null,
                    rs.getLong("sum_duration_ms"), rs.getLong("sum_start_min_utc"), rs.getLong("sum_end_min_utc"),
                    rs.getDouble("sum_start_sin"), rs.getDouble("sum_start_cos"),
                    rs.getDouble("sum_end_sin"), rs.getDouble("sum_end_cos"),
                    rs.getInt("total_runs")));
        } catch (Exception e) {
            log.error("event=daily_aggregate.find_all_profiles_by_run_number outcome=failure frequency={}", frequency, e);
            return Collections.emptyList();
        }
    }

    /**
     * Run_number-scoped profile for one calculator. Cache-aside source for the
     * {@link com.company.observability.service.CalculatorProfileService#getProfile(String,
     * com.company.observability.domain.enums.Frequency, String)} overload.
     */
    public CalculatorProfile findProfileByRunNumber(String calculatorName, String frequency,
                                                    int days, String runNumber) {
        String sql = """
            SELECT COALESCE(SUM(sum_duration_ms), 0)   AS sum_duration_ms,
                   COALESCE(SUM(sum_start_min_utc), 0) AS sum_start_min_utc,
                   COALESCE(SUM(sum_end_min_utc), 0)   AS sum_end_min_utc,
                   COALESCE(SUM(sum_start_sin), 0)     AS sum_start_sin,
                   COALESCE(SUM(sum_start_cos), 0)     AS sum_start_cos,
                   COALESCE(SUM(sum_end_sin), 0)       AS sum_end_sin,
                   COALESCE(SUM(sum_end_cos), 0)       AS sum_end_cos,
                   COALESCE(SUM(total_runs), 0)        AS total_runs
            FROM calculator_sli_daily
            WHERE calculator_name = :calculatorName
            AND frequency = :frequency
            AND run_number = :runNumber
            AND reporting_date >= CURRENT_DATE - CAST(:days AS INTEGER) * INTERVAL '1 day'
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("calculatorName", calculatorName)
                .addValue("frequency", frequency)
                .addValue("runNumber", runNumber)
                .addValue("days", days);

        try {
            return jdbcTemplate.queryForObject(sql, params, (rs, rowNum) -> CalculatorProfile.fromSums(
                    calculatorName, frequency, runNumber, null,
                    rs.getLong("sum_duration_ms"), rs.getLong("sum_start_min_utc"), rs.getLong("sum_end_min_utc"),
                    rs.getDouble("sum_start_sin"), rs.getDouble("sum_start_cos"),
                    rs.getDouble("sum_end_sin"), rs.getDouble("sum_end_cos"),
                    rs.getInt("total_runs")));
        } catch (Exception e) {
            log.error("event=daily_aggregate.find_profile_by_run_number outcome=failure calculator_name={} frequency={} runNumber={}",
                    calculatorName, frequency, runNumber, e);
            return CalculatorProfile.empty(calculatorName, frequency, runNumber, null);
        }
    }

    /**
     * Dimension-scoped profile for one calculator. Primary source for per-region/run-type
     * estimates in Case B (partial batch). A null {@code runNumber} matches all run_number values.
     */
    public CalculatorProfile findProfileByRunNumberAndDimension(String calculatorName, String frequency,
                                                                int days, String runNumber,
                                                                String dimensionValue) {
        String sql = """
            SELECT COALESCE(SUM(sum_duration_ms), 0)   AS sum_duration_ms,
                   COALESCE(SUM(sum_start_min_utc), 0) AS sum_start_min_utc,
                   COALESCE(SUM(sum_end_min_utc), 0)   AS sum_end_min_utc,
                   COALESCE(SUM(sum_start_sin), 0)     AS sum_start_sin,
                   COALESCE(SUM(sum_start_cos), 0)     AS sum_start_cos,
                   COALESCE(SUM(sum_end_sin), 0)       AS sum_end_sin,
                   COALESCE(SUM(sum_end_cos), 0)       AS sum_end_cos,
                   COALESCE(SUM(total_runs), 0)        AS total_runs
            FROM calculator_sli_daily
            WHERE calculator_name = :calculatorName
            AND frequency = :frequency
            AND (:runNumber IS NULL OR run_number = :runNumber)
            AND dimension_value = :dimensionValue
            AND reporting_date >= CURRENT_DATE - CAST(:days AS INTEGER) * INTERVAL '1 day'
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("calculatorName", calculatorName)
                .addValue("frequency", frequency)
                .addValue("runNumber", runNumber, Types.VARCHAR)
                .addValue("dimensionValue", dimensionValue)
                .addValue("days", days);

        try {
            return jdbcTemplate.queryForObject(sql, params, (rs, rowNum) -> CalculatorProfile.fromSums(
                    calculatorName, frequency, runNumber, dimensionValue,
                    rs.getLong("sum_duration_ms"), rs.getLong("sum_start_min_utc"), rs.getLong("sum_end_min_utc"),
                    rs.getDouble("sum_start_sin"), rs.getDouble("sum_start_cos"),
                    rs.getDouble("sum_end_sin"), rs.getDouble("sum_end_cos"),
                    rs.getInt("total_runs")));
        } catch (Exception e) {
            log.error("event=daily_aggregate.find_profile_by_dim outcome=failure calculator_name={} frequency={} runNumber={} dim={}",
                    calculatorName, frequency, runNumber, dimensionValue, e);
            return CalculatorProfile.empty(calculatorName, frequency, runNumber, dimensionValue);
        }
    }

    /**
     * Per-run_number + per-dimension profiles for all active calculators. Used by the nightly
     * job to warm the third-tier cache keys ({@code obs:profile:{name}:{freq}:{runNumber|*}:{dim}}).
     * Excludes 'ALL' rows — those are already covered by blended and scoped keys.
     */
    public List<CalculatorProfile> findAllProfilesByRunNumberAndDimension(String frequency, int days) {
        String sql = """
            SELECT calculator_name, run_number, dimension_value,
                   SUM(sum_duration_ms)   AS sum_duration_ms,
                   SUM(sum_start_min_utc) AS sum_start_min_utc,
                   SUM(sum_end_min_utc)   AS sum_end_min_utc,
                   SUM(sum_start_sin)     AS sum_start_sin,
                   SUM(sum_start_cos)     AS sum_start_cos,
                   SUM(sum_end_sin)       AS sum_end_sin,
                   SUM(sum_end_cos)       AS sum_end_cos,
                   SUM(total_runs)        AS total_runs
            FROM calculator_sli_daily
            WHERE frequency = :frequency
            AND dimension_value <> 'ALL'
            AND reporting_date >= CURRENT_DATE - CAST(:days AS INTEGER) * INTERVAL '1 day'
            GROUP BY calculator_name, run_number, dimension_value
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("frequency", frequency)
                .addValue("days", days);

        try {
            return jdbcTemplate.query(sql, params, (rs, rowNum) -> {
                // Translate the 'ALL' un-numbered bucket back to null so an agnostic dim slice
                // warms under the …:*:{dim} key the routed read looks up. Aware calcs keep '1'/'2'.
                String rn = rs.getString("run_number");
                rn = "ALL".equals(rn) ? null : rn;
                return CalculatorProfile.fromSums(
                        rs.getString("calculator_name"), frequency, rn,
                        rs.getString("dimension_value"),
                        rs.getLong("sum_duration_ms"), rs.getLong("sum_start_min_utc"), rs.getLong("sum_end_min_utc"),
                        rs.getDouble("sum_start_sin"), rs.getDouble("sum_start_cos"),
                        rs.getDouble("sum_end_sin"), rs.getDouble("sum_end_cos"),
                        rs.getInt("total_runs"));
            });
        } catch (Exception e) {
            log.error("event=daily_aggregate.find_all_profiles_by_dim outcome=failure frequency={}", frequency, e);
            return Collections.emptyList();
        }
    }

    /**
     * Tier-2 fallback for the run_number-scoped (3-arg) profile: aggregates the last 5 completed
     * {@code SUCCESS} runs for the calculator+frequency+run_number straight from
     * {@code calculator_runs}, ignoring dimension. Used only when {@code calculator_sli_daily} has
     * no row for the exact slice (e.g. a freshly-onboarded calculator before the first nightly
     * recompute). Returns a zero-sample sentinel when no recent run exists.
     *
     * <p>{@code runNumber} may be null (Archetype B) — the explicit
     * {@code (:runNumber IS NULL AND run_number IS NULL)} form avoids the JDBC untyped-null pitfall.
     */
    public CalculatorProfile findRecentExactByRunNumber(String calculatorName, String frequency,
                                                        int days, String runNumber) {
        return findRecentExact(calculatorName, frequency, days, runNumber, null);
    }

    /**
     * Tier-2 fallback for the dimension-scoped (4-arg) profile: same as
     * {@link #findRecentExactByRunNumber} but additionally filters on the dimension value
     * ({@code COALESCE(region, run_type, 'ALL')}).
     */
    public CalculatorProfile findRecentExactByDimension(String calculatorName, String frequency,
                                                        int days, String runNumber, String dimensionValue) {
        return findRecentExact(calculatorName, frequency, days, runNumber, dimensionValue);
    }

    /**
     * Tier-2 fallback for the blended (2-arg, doubly-agnostic) profile: aggregates the last 5
     * completed {@code SUCCESS} runs for the calculator+frequency straight from
     * {@code calculator_runs}, ignoring run_number and dimension. Used only when
     * {@code calculator_sli_daily} has no matching row (e.g. a freshly-onboarded calculator before
     * the first nightly recompute). Returns a zero-sample sentinel when no recent run exists.
     */
    public CalculatorProfile findRecentExactBlended(String calculatorName, String frequency, int days) {
        return findRecentExact(calculatorName, frequency, days, null, null);
    }

    private CalculatorProfile findRecentExact(String calculatorName, String frequency,
                                              int days, String runNumber, String dimensionValue) {
        String dimFilter = dimensionValue != null
                ? "AND COALESCE(region, run_type, 'ALL') = :dimensionValue\n"
                : "";
        String sql = """
            SELECT COALESCE(AVG(duration_ms), 0)                                       AS avg_duration_ms,
                   COALESCE(SUM(start_min), 0)                                          AS sum_start_min_utc,
                   COALESCE(SUM(SIN(2 * PI() * start_min / 1440.0)), 0)                 AS sum_start_sin,
                   COALESCE(SUM(COS(2 * PI() * start_min / 1440.0)), 0)                 AS sum_start_cos,
                   COALESCE(SUM(end_min), 0)                                            AS sum_end_min_utc,
                   COALESCE(SUM(SIN(2 * PI() * end_min / 1440.0)), 0)                   AS sum_end_sin,
                   COALESCE(SUM(COS(2 * PI() * end_min / 1440.0)), 0)                   AS sum_end_cos,
                   COUNT(*)                                                             AS total_runs
            FROM (
                SELECT duration_ms,
                       EXTRACT(HOUR   FROM start_time AT TIME ZONE 'UTC') * 60 +
                       EXTRACT(MINUTE FROM start_time AT TIME ZONE 'UTC') AS start_min,
                       EXTRACT(HOUR   FROM end_time   AT TIME ZONE 'UTC') * 60 +
                       EXTRACT(MINUTE FROM end_time   AT TIME ZONE 'UTC') AS end_min
                FROM calculator_runs
                WHERE calculator_name = :calculatorName
                  AND frequency        = :frequency
                  %s  AND ((:runNumber IS NULL AND run_number IS NULL)
                       OR run_number = :runNumber)
                  AND status = 'SUCCESS'
                  AND end_time IS NOT NULL
                  AND reporting_date >= CURRENT_DATE - CAST(:days AS INTEGER) * INTERVAL '1 day'
                ORDER BY created_at DESC
                LIMIT 5
            ) recent
            """.formatted(dimFilter);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("calculatorName", calculatorName)
                .addValue("frequency", frequency)
                .addValue("days", days)
                .addValue("runNumber", runNumber, Types.VARCHAR);
        if (dimensionValue != null) {
            params.addValue("dimensionValue", dimensionValue);
        }

        try {
            Timer.Sample sample = Timer.start(meterRegistry);
            CalculatorProfile profile = jdbcTemplate.queryForObject(sql, params, (rs, rowNum) -> {
                int total = rs.getInt("total_runs");
                if (total <= 0) {
                    return CalculatorProfile.empty(calculatorName, frequency, runNumber, dimensionValue);
                }
                return new CalculatorProfile(calculatorName, frequency, runNumber, dimensionValue,
                        Math.round(rs.getDouble("avg_duration_ms")),
                        CalculatorProfile.circularMeanMinute(rs.getDouble("sum_start_sin"), rs.getDouble("sum_start_cos"),
                                rs.getLong("sum_start_min_utc"), total),
                        CalculatorProfile.circularMeanMinute(rs.getDouble("sum_end_sin"), rs.getDouble("sum_end_cos"),
                                rs.getLong("sum_end_min_utc"), total),
                        total);
            });
            sample.stop(Timer.builder(DB_QUERY_DURATION).tag("query", "find_recent_exact").register(meterRegistry));
            return profile;
        } catch (Exception e) {
            log.error("event=daily_aggregate.find_recent_exact outcome=failure calculator_name={} frequency={} runNumber={} dim={}",
                    calculatorName, frequency, runNumber, dimensionValue, e);
            return CalculatorProfile.empty(calculatorName, frequency, runNumber, dimensionValue);
        }
    }

    private static class DailyAggregateRowMapper implements RowMapper<DailyAggregate> {
        @Override
        public DailyAggregate mapRow(ResultSet rs, int rowNum) {
            try {
                return new DailyAggregate(
                        rs.getString("calculator_name"),
                        rs.getObject("reporting_date", LocalDate.class),
                        rs.getInt("total_runs"),
                        rs.getInt("success_runs"),
                        rs.getInt("sla_breaches"),
                        rs.getLong("sum_duration_ms"),
                        rs.getLong("sum_start_min_utc"),
                        rs.getLong("sum_end_min_utc"),
                        fromTimestamp(rs.getTimestamp("computed_at"))
                );
            } catch (SQLException e) {
                throw new RuntimeException("Failed to map daily aggregate", e);
            }
        }
    }
}
