package com.company.observability.repository;

import com.company.observability.domain.SlaBreachEvent;
import com.company.observability.domain.enums.AlertStatus;
import com.company.observability.domain.enums.BreachType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static com.company.observability.util.ObservabilityConstants.*;
import static com.company.observability.util.TimeUtils.fromTimestamp;

@Repository
@RequiredArgsConstructor
@Slf4j
public class SlaBreachEventRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;

    private static final String SELECT_COLUMNS = """
            breach_id, run_id, calculator_id, calculator_name, tenant_id,
            reporting_date,
            breach_type, expected_value, actual_value,
            alerted, alerted_at, alert_status, retry_count, last_error, created_at""";

    private static final String SELECT_FROM = "SELECT " + SELECT_COLUMNS + "\nFROM sla_breach_events\n";

    private static final String BASE_WHERE = """
            WHERE calculator_id = :calculatorId
            AND created_at >= NOW() - CAST(:days AS INTEGER) * INTERVAL '1 day'""";

    private static final String ORDER_DESC = "\nORDER BY created_at DESC, breach_id DESC";

    private static final SlaBreachEventRowMapper ROW_MAPPER = new SlaBreachEventRowMapper();

    /**
     * Idempotent save — throws DuplicateKeyException if run_id already has a breach row.
     * Caller must handle DuplicateKeyException.
     */
    public SlaBreachEvent save(SlaBreachEvent breach) throws DuplicateKeyException {
        if (breach.getCreatedAt() == null) {
            breach.setCreatedAt(Instant.now());
        }

        String sql = """
            INSERT INTO sla_breach_events (
                run_id, calculator_id, calculator_name, tenant_id,
                reporting_date,
                breach_type, expected_value, actual_value,
                alerted, alerted_at, alert_status, retry_count, last_error, created_at
            ) VALUES (
                :runId, :calculatorId, :calculatorName, :tenantId,
                :reportingDate,
                :breachType, :expectedValue, :actualValue,
                :alerted, :alertedAt, :alertStatus, :retryCount, :lastError, :createdAt
            )
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("runId", breach.getRunId())
                .addValue("calculatorId", breach.getCalculatorId())
                .addValue("calculatorName", breach.getCalculatorName())
                .addValue("tenantId", breach.getTenantId())
                .addValue("reportingDate", breach.getReportingDate())
                .addValue("breachType", breach.getBreachType().name())
                .addValue("expectedValue", breach.getExpectedValue())
                .addValue("actualValue", breach.getActualValue())
                .addValue("alerted", breach.getAlerted())
                .addValue("alertedAt", breach.getAlertedAt() != null ? Timestamp.from(breach.getAlertedAt()) : null)
                .addValue("alertStatus", breach.getAlertStatus().name())
                .addValue("retryCount", breach.getRetryCount() != null ? breach.getRetryCount() : 0)
                .addValue("lastError", breach.getLastError())
                .addValue("createdAt", Timestamp.from(breach.getCreatedAt()));

        KeyHolder keyHolder = new GeneratedKeyHolder();

        Timer.Sample sample = Timer.start(meterRegistry);
        jdbcTemplate.update(sql, params, keyHolder);
        sample.stop(Timer.builder(DB_QUERY_DURATION).tag("query", "save_breach").register(meterRegistry));

        breach.setBreachId(keyHolder.getKey().longValue());
        return breach;
    }

    /**
     * Find unalerted breaches for batch processing.
     */
    public List<SlaBreachEvent> findUnalertedBreaches(int limit) {
        String sql = SELECT_FROM + """
            WHERE alerted = false
            AND alert_status IN ('PENDING', 'FAILED')
            ORDER BY created_at ASC
            LIMIT :limit""";

        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit);
        return jdbcTemplate.query(sql, params, ROW_MAPPER);
    }

    /**
     * Update breach status after alert attempt.
     */
    public void update(SlaBreachEvent breach) {
        String sql = """
            UPDATE sla_breach_events
            SET alerted = :alerted,
                alerted_at = :alertedAt,
                alert_status = :alertStatus,
                retry_count = :retryCount,
                last_error = :lastError
            WHERE breach_id = :breachId
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("alerted", breach.getAlerted())
                .addValue("alertedAt", breach.getAlertedAt() != null ? Timestamp.from(breach.getAlertedAt()) : null)
                .addValue("alertStatus", breach.getAlertStatus().name())
                .addValue("retryCount", breach.getRetryCount())
                .addValue("lastError", breach.getLastError())
                .addValue("breachId", breach.getBreachId());

        jdbcTemplate.update(sql, params);
    }

    /**
     * Aggregated breach counts by sla_band (from calculator_runs) for analytics summary/trends.
     * Bands: ON_TIME, LATE, VERY_LATE, plus FAILED for runs with terminal failure status.
     */
    public Map<String, Integer> countByBand(String calculatorId, int days) {
        String sql = """
            SELECT
                CASE
                    WHEN cr.status IN ('FAILED','TIMEOUT') THEN 'FAILED'
                    WHEN cr.sla_band IS NOT NULL THEN cr.sla_band
                    ELSE 'ON_TIME'
                END AS band,
                COUNT(*) AS cnt
            FROM sla_breach_events sbe
            JOIN calculator_runs cr ON cr.run_id = sbe.run_id
            WHERE sbe.calculator_id = :calculatorId
            AND sbe.created_at >= NOW() - CAST(:days AS INTEGER) * INTERVAL '1 day'
            GROUP BY 1""";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("calculatorId", calculatorId)
                .addValue("days", days);

        Map<String, Integer> result = new HashMap<>();
        Timer.Sample sample = Timer.start(meterRegistry);
        jdbcTemplate.query(sql, params, (RowMapper<Void>) (rs, rowNum) -> {
            result.put(rs.getString("band"), ((Number) rs.getObject("cnt")).intValue());
            return null;
        });
        sample.stop(Timer.builder(DB_QUERY_DURATION).tag("query", "count_by_band").register(meterRegistry));
        return result;
    }

    /**
     * Aggregated breach counts by breach_type for analytics summary.
     */
    public Map<String, Integer> countByType(String calculatorId, int days) {
        String sql = """
            SELECT COALESCE(breach_type, 'UNKNOWN') AS breach_type, COUNT(*) AS cnt
            FROM sla_breach_events
            """ + BASE_WHERE + """

            GROUP BY COALESCE(breach_type, 'UNKNOWN')""";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("calculatorId", calculatorId)
                .addValue("days", days);

        Map<String, Integer> result = new HashMap<>();
        jdbcTemplate.query(sql, params, (RowMapper<Void>) (rs, rowNum) -> {
            result.put(rs.getString("breach_type"), ((Number) rs.getObject("cnt")).intValue());
            return null;
        });
        return result;
    }

    /**
     * Worst health rank per CET day.
     * Rank: failure (4) > VERY_LATE (3) > LATE (2) > ON_TIME (1).
     * Returns the band/status name for the worst-ranked day.
     */
    public Map<LocalDate, String> findWorstDayHealthByDay(String calculatorId, int days) {
        String sql = """
            SELECT (sbe.created_at AT TIME ZONE 'Europe/Amsterdam')::DATE AS day_cet,
                   MAX(
                       GREATEST(
                           CASE WHEN cr.status IN ('FAILED','TIMEOUT') THEN 4 ELSE 0 END,
                           CASE cr.sla_band
                               WHEN 'VERY_LATE' THEN 3
                               WHEN 'LATE'      THEN 2
                               WHEN 'ON_TIME'   THEN 1
                               ELSE 1
                           END
                       )
                   ) AS worst_rank
            FROM sla_breach_events sbe
            JOIN calculator_runs cr ON cr.run_id = sbe.run_id
            WHERE sbe.calculator_id = :calculatorId
            AND sbe.created_at >= NOW() - CAST(:days AS INTEGER) * INTERVAL '1 day'
            GROUP BY (sbe.created_at AT TIME ZONE 'Europe/Amsterdam')::DATE""";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("calculatorId", calculatorId)
                .addValue("days", days);

        Map<LocalDate, String> result = new HashMap<>();
        jdbcTemplate.query(sql, params, (RowMapper<Void>) (rs, rowNum) -> {
            LocalDate day = rs.getObject("day_cet", LocalDate.class);
            int rank = ((Number) rs.getObject("worst_rank")).intValue();
            result.put(day, bandFromRank(rank));
            return null;
        });
        return result;
    }

    /**
     * Find breaches with offset pagination and optional band filter.
     * band parameter maps to cr.sla_band (ON_TIME/LATE/VERY_LATE) or 'FAILED' for terminal failures.
     */
    public List<SlaBreachEvent> findByCalculatorIdPaginated(
            String calculatorId, int days,
            String band, int offset, int limit) {

        boolean hasBand = band != null && !band.isBlank();

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("calculatorId", calculatorId)
                .addValue("days", days);

        StringBuilder sql = new StringBuilder(
                "SELECT " + SELECT_COLUMNS + "\nFROM sla_breach_events sbe\n");

        if (hasBand) {
            sql.append("JOIN calculator_runs cr ON cr.run_id = sbe.run_id\n");
        }

        sql.append("WHERE sbe.calculator_id = :calculatorId\n")
           .append("AND sbe.created_at >= NOW() - CAST(:days AS INTEGER) * INTERVAL '1 day'\n");

        if (hasBand) {
            appendBandFilter(sql, params, band);
        }

        sql.append("\nORDER BY sbe.created_at DESC");
        sql.append("\nLIMIT :limit OFFSET :offset");
        params.addValue("limit", limit);
        params.addValue("offset", offset);

        Timer.Sample sample = Timer.start(meterRegistry);
        List<SlaBreachEvent> results = jdbcTemplate.query(sql.toString(), params, ROW_MAPPER);
        sample.stop(Timer.builder(DB_QUERY_DURATION).tag("query", "find_breaches").register(meterRegistry));
        return results;
    }

    /**
     * Keyset pagination for SLA breach history (stable on created_at, breach_id).
     */
    public List<SlaBreachEvent> findByCalculatorIdKeyset(
            String calculatorId, int days, String band,
            Instant cursorCreatedAt, Long cursorBreachId, int limit) {

        boolean hasCursor = cursorCreatedAt != null && cursorBreachId != null;
        boolean hasBand = band != null && !band.isBlank();

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("calculatorId", calculatorId)
                .addValue("days", days);

        StringBuilder sql = new StringBuilder(
                "SELECT " + SELECT_COLUMNS + "\nFROM sla_breach_events sbe\n");

        if (hasBand) {
            sql.append("JOIN calculator_runs cr ON cr.run_id = sbe.run_id\n");
        }

        sql.append("WHERE sbe.calculator_id = :calculatorId\n")
           .append("AND sbe.created_at >= NOW() - CAST(:days AS INTEGER) * INTERVAL '1 day'\n");

        if (hasBand) {
            appendBandFilter(sql, params, band);
        }

        if (hasCursor) {
            sql.append("\nAND (sbe.created_at, sbe.breach_id) < (:cursorCreatedAt, :cursorBreachId)");
            params.addValue("cursorCreatedAt", Timestamp.from(cursorCreatedAt));
            params.addValue("cursorBreachId", cursorBreachId);
        }

        sql.append(ORDER_DESC);
        sql.append("\nLIMIT :limit");
        params.addValue("limit", limit);

        Timer.Sample sample = Timer.start(meterRegistry);
        List<SlaBreachEvent> results = jdbcTemplate.query(sql.toString(), params, ROW_MAPPER);
        sample.stop(Timer.builder(DB_QUERY_DURATION).tag("query", "find_breaches").register(meterRegistry));
        return results;
    }

    /**
     * Count breaches with optional band filter.
     */
    public long countByCalculatorIdAndPeriod(String calculatorId, int days, String band) {
        boolean hasBand = band != null && !band.isBlank();

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("calculatorId", calculatorId)
                .addValue("days", days);

        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM sla_breach_events sbe\n");

        if (hasBand) {
            sql.append("JOIN calculator_runs cr ON cr.run_id = sbe.run_id\n");
        }

        sql.append("WHERE sbe.calculator_id = :calculatorId\n")
           .append("AND sbe.created_at >= NOW() - CAST(:days AS INTEGER) * INTERVAL '1 day'");

        if (hasBand) {
            appendBandFilter(sql, params, band);
        }

        Long count = jdbcTemplate.queryForObject(sql.toString(), params, Long.class);
        return count != null ? count : 0;
    }

    /** Appends a WHERE clause fragment to filter by band (including FAILED for terminal failures). */
    private void appendBandFilter(StringBuilder sql, MapSqlParameterSource params, String band) {
        if ("FAILED".equalsIgnoreCase(band)) {
            sql.append("\nAND cr.status IN ('FAILED','TIMEOUT')");
        } else {
            sql.append("\nAND cr.sla_band = :band");
            params.addValue("band", band.toUpperCase());
        }
    }

    private static String bandFromRank(int rank) {
        return switch (rank) {
            case 4 -> "FAILED";
            case 3 -> "VERY_LATE";
            case 2 -> "LATE";
            default -> "ON_TIME";
        };
    }

    private static class SlaBreachEventRowMapper implements RowMapper<SlaBreachEvent> {
        @Override
        public SlaBreachEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
            return SlaBreachEvent.builder()
                    .breachId(rs.getLong("breach_id"))
                    .runId(rs.getString("run_id"))
                    .calculatorId(rs.getString("calculator_id"))
                    .calculatorName(rs.getString("calculator_name"))
                    .tenantId(rs.getString("tenant_id"))
                    .reportingDate(rs.getObject("reporting_date", LocalDate.class))
                    .breachType(BreachType.fromString(rs.getString("breach_type")))
                    .expectedValue(rs.getObject("expected_value", Long.class))
                    .actualValue(rs.getObject("actual_value", Long.class))
                    .alerted(rs.getBoolean("alerted"))
                    .alertedAt(fromTimestamp(rs.getTimestamp("alerted_at")))
                    .alertStatus(AlertStatus.fromString(rs.getString("alert_status")))
                    .retryCount(rs.getInt("retry_count"))
                    .lastError(rs.getString("last_error"))
                    .createdAt(fromTimestamp(rs.getTimestamp("created_at")))
                    .build();
        }
    }
}
