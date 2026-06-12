package com.company.observability.repository;

import com.company.observability.domain.CalculatorProfile;
import com.company.observability.domain.DailyAggregate;
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
    public int recomputeForDateRange(LocalDate fromInclusive, LocalDate toInclusive) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", fromInclusive)
                .addValue("to", toInclusive);

        jdbcTemplate.update(
                "DELETE FROM calculator_sli_daily WHERE reporting_date BETWEEN :from AND :to", params);

        // Two-pass UNION:
        // Pass 1 — explicit run_number rows (capital and other cycle-specific calcs): one row per (name,freq,date,rn,dim)
        // Pass 2 — null-run_number rows (modelled-exposure, gemini-hedge): fanned into BOTH '1' AND '2' buckets
        String insert = """
            INSERT INTO calculator_sli_daily (
                calculator_name, frequency, reporting_date, run_number, dimension_value,
                total_runs, success_runs, sla_breaches,
                sum_duration_ms, sum_start_min_utc, sum_end_min_utc, computed_at
            )
            -- Pass 1: explicit run_number rows
            SELECT
                calculator_name, frequency, reporting_date, run_number,
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
                NOW()
            FROM calculator_runs
            WHERE end_time IS NOT NULL
              AND run_number IS NOT NULL
              AND reporting_date BETWEEN :from AND :to
            GROUP BY calculator_name, frequency, reporting_date, run_number,
                     COALESCE(region, run_type, 'ALL')

            UNION ALL

            -- Pass 2: null-run_number rows — fan out into both '1' and '2' buckets
            SELECT
                cr.calculator_name, cr.frequency, cr.reporting_date, rn.run_number,
                COALESCE(cr.region, cr.run_type, 'ALL') AS dimension_value,
                COUNT(*),
                COUNT(*) FILTER (WHERE cr.status = 'SUCCESS'),
                COUNT(*) FILTER (WHERE cr.sla_breached),
                COALESCE(SUM(cr.duration_ms), 0),
                COALESCE(SUM(
                    EXTRACT(HOUR   FROM cr.start_time AT TIME ZONE 'UTC') * 60 +
                    EXTRACT(MINUTE FROM cr.start_time AT TIME ZONE 'UTC')
                ), 0),
                COALESCE(SUM(
                    CASE WHEN cr.end_time IS NOT NULL THEN
                        EXTRACT(HOUR   FROM cr.end_time AT TIME ZONE 'UTC') * 60 +
                        EXTRACT(MINUTE FROM cr.end_time AT TIME ZONE 'UTC')
                    ELSE 0 END
                ), 0),
                NOW()
            FROM calculator_runs cr
            CROSS JOIN (VALUES ('1'), ('2')) AS rn(run_number)
            WHERE cr.end_time IS NOT NULL
              AND cr.run_number IS NULL
              AND cr.reporting_date BETWEEN :from AND :to
            GROUP BY cr.calculator_name, cr.frequency, cr.reporting_date, rn.run_number,
                     COALESCE(cr.region, cr.run_type, 'ALL')
            """;

        try {
            Timer.Sample sample = Timer.start(meterRegistry);
            int inserted = jdbcTemplate.update(insert, params);
            sample.stop(Timer.builder(DB_QUERY_DURATION).tag("query", "recompute_daily").register(meterRegistry));
            return inserted;
        } catch (Exception e) {
            log.error("event=daily_aggregate.recompute outcome=failure from={} to={}", fromInclusive, toInclusive, e);
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
                    rs.getLong("sum_duration_ms"), rs.getLong("sum_start_min_utc"),
                    rs.getLong("sum_end_min_utc"), rs.getInt("total_runs")));
        } catch (Exception e) {
            log.error("event=daily_aggregate.find_profile outcome=failure calculator_name={} frequency={}",
                    calculatorName, frequency, e);
            return CalculatorProfile.fromSums(calculatorName, frequency, null, null, 0, 0, 0, 0);
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
                    rs.getLong("sum_duration_ms"), rs.getLong("sum_start_min_utc"),
                    rs.getLong("sum_end_min_utc"), rs.getInt("total_runs")));
        } catch (Exception e) {
            log.error("event=daily_aggregate.find_all_profiles outcome=failure frequency={}", frequency, e);
            return Collections.emptyList();
        }
    }

    /**
     * Per-run_number profiles for all active calculators. Used by the nightly job to warm
     * run_number-scoped cache keys ({@code obs:profile:{name}:{freq}:{runNumber}}).
     */
    public List<CalculatorProfile> findAllProfilesByRunNumber(String frequency, int days) {
        String sql = """
            SELECT calculator_name, run_number,
                   SUM(sum_duration_ms)   AS sum_duration_ms,
                   SUM(sum_start_min_utc) AS sum_start_min_utc,
                   SUM(sum_end_min_utc)   AS sum_end_min_utc,
                   SUM(total_runs)        AS total_runs
            FROM calculator_sli_daily
            WHERE frequency = :frequency
            AND reporting_date >= CURRENT_DATE - CAST(:days AS INTEGER) * INTERVAL '1 day'
            GROUP BY calculator_name, run_number
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("frequency", frequency)
                .addValue("days", days);

        try {
            return jdbcTemplate.query(sql, params, (rs, rowNum) -> CalculatorProfile.fromSums(
                    rs.getString("calculator_name"), frequency, rs.getString("run_number"), null,
                    rs.getLong("sum_duration_ms"), rs.getLong("sum_start_min_utc"),
                    rs.getLong("sum_end_min_utc"), rs.getInt("total_runs")));
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
                    rs.getLong("sum_duration_ms"), rs.getLong("sum_start_min_utc"),
                    rs.getLong("sum_end_min_utc"), rs.getInt("total_runs")));
        } catch (Exception e) {
            log.error("event=daily_aggregate.find_profile_by_run_number outcome=failure calculator_name={} frequency={} runNumber={}",
                    calculatorName, frequency, runNumber, e);
            return CalculatorProfile.fromSums(calculatorName, frequency, runNumber, null, 0, 0, 0, 0);
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
                .addValue("runNumber", runNumber)
                .addValue("dimensionValue", dimensionValue)
                .addValue("days", days);

        try {
            return jdbcTemplate.queryForObject(sql, params, (rs, rowNum) -> CalculatorProfile.fromSums(
                    calculatorName, frequency, runNumber, dimensionValue,
                    rs.getLong("sum_duration_ms"), rs.getLong("sum_start_min_utc"),
                    rs.getLong("sum_end_min_utc"), rs.getInt("total_runs")));
        } catch (Exception e) {
            log.error("event=daily_aggregate.find_profile_by_dim outcome=failure calculator_name={} frequency={} runNumber={} dim={}",
                    calculatorName, frequency, runNumber, dimensionValue, e);
            return CalculatorProfile.fromSums(calculatorName, frequency, runNumber, dimensionValue, 0, 0, 0, 0);
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
            return jdbcTemplate.query(sql, params, (rs, rowNum) -> CalculatorProfile.fromSums(
                    rs.getString("calculator_name"), frequency, rs.getString("run_number"),
                    rs.getString("dimension_value"),
                    rs.getLong("sum_duration_ms"), rs.getLong("sum_start_min_utc"),
                    rs.getLong("sum_end_min_utc"), rs.getInt("total_runs")));
        } catch (Exception e) {
            log.error("event=daily_aggregate.find_all_profiles_by_dim outcome=failure frequency={}", frequency, e);
            return Collections.emptyList();
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
