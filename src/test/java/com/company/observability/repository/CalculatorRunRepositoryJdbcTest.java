package com.company.observability.repository;

import com.company.observability.cache.RedisCalculatorCache;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.RunWithSlaStatus;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.domain.enums.SlaBand;
import com.company.observability.util.JsonbConverter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.company.observability.domain.enums.RunStatus;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Import(CalculatorRunRepository.class)
class CalculatorRunRepositoryJdbcTest extends PostgresJdbcIntegrationTestBase {

    @Autowired
    private CalculatorRunRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonbConverter jsonbConverter;

    @TestConfiguration
    static class TestBeans {
        @Bean
        RedisCalculatorCache redisCalculatorCache() {
            return Mockito.mock(RedisCalculatorCache.class);
        }

        @Bean
        JsonbConverter jsonbConverter() {
            return Mockito.mock(JsonbConverter.class);
        }
    }

    @BeforeEach
    void clean() {
        jdbcTemplate.update("TRUNCATE TABLE sla_breach_events RESTART IDENTITY");
        jdbcTemplate.update("TRUNCATE TABLE calculator_runs CASCADE");
    }

    @AfterEach
    void resetJsonbConverter() {
        // Some tests stub the shared JsonbConverter mock; reset so stubs don't leak across tests.
        Mockito.reset(jsonbConverter);
    }

    // ---------------------------------------------------------------
    // safeFromJsonb — a corrupted/unmappable JSONB value degrades that one
    // field to null instead of failing the whole query result set
    // ---------------------------------------------------------------

    @Test
    void findById_whenJsonbDeserializeThrows_degradesFieldToNull_notWholeRow() {
        LocalDate date = LocalDate.of(2026, 6, 15);
        Instant start = Instant.parse("2026-06-15T05:00:00Z");
        jdbcTemplate.update("""
            INSERT INTO calculator_runs (
                run_id, calculator_id, calculator_name, tenant_id, frequency, reporting_date,
                start_time, status, sla_band, run_parameters, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?::jsonb, ?, ?)
            """,
                "run-corrupt", "calc-1", "Calculator 1", "tenant-1", "DAILY", date,
                Timestamp.from(start), "RUNNING", "{\"k\":\"v\"}",
                Timestamp.from(start), Timestamp.from(start));

        Mockito.when(jsonbConverter.fromJsonb(Mockito.any()))
                .thenThrow(new IllegalArgumentException("deserialize boom"));

        // findById(String) maps JSONB (includeJsonb=true) — must not propagate the exception.
        Optional<CalculatorRun> run = repository.findById("run-corrupt");

        assertThat(run).isPresent();
        assertThat(run.get().getRunId()).isEqualTo("run-corrupt");
        assertThat(run.get().getRunParameters()).isNull(); // degraded, not thrown
    }

    @Test
    void findRunsWithSlaStatus_returnsSlaBandFromCalculatorRuns() {
        LocalDate reportDate = LocalDate.now();
        Instant createdAt = Instant.parse("2026-02-22T12:00:00Z");
        insertRunWithSlaBand("run-banded", "calc-1", reportDate, createdAt, "LATE");

        List<RunWithSlaStatus> result = repository.findRunsWithSlaStatus(
                "calc-1", Frequency.DAILY, 3
        );

        assertEquals(1, result.size());
        assertEquals("run-banded", result.get(0).runId());
        assertEquals(SlaBand.LATE, result.get(0).slaBand());
        assertNotNull(result.get(0).reportingDate());
    }

    // ---------------------------------------------------------------
    // run_number filter — NULL (single-bucket) rows included with a bucket
    // ---------------------------------------------------------------

    @Test
    void findRunsByName_withRunNumber_includesNullRunNumberRows() {
        LocalDate reportDate = LocalDate.now();
        Instant base = Instant.parse("2026-02-22T10:00:00Z");
        insertRunWithRunNumber("run-rn1",    "Calculator 1", reportDate, base.plusSeconds(100), "1");
        insertRunWithRunNumber("run-rn2",    "Calculator 1", reportDate, base.plusSeconds(200), "2");
        insertRunWithRunNumber("run-rnnull", "Calculator 1", reportDate, base.plusSeconds(300), null);

        List<RunWithSlaStatus> result = repository.findRunsByName(
                "Calculator 1", Frequency.DAILY, 3, "1", reportDate);

        assertThat(result).extracting(RunWithSlaStatus::runId)
                .containsExactlyInAnyOrder("run-rn1", "run-rnnull");
    }

    @Test
    void findAllRunsByDateAndDimension_withRunNumber_includesNullRunNumberRows() {
        LocalDate reportDate = LocalDate.now();
        Instant base = Instant.parse("2026-02-22T10:00:00Z");
        insertRunWithRunNumber("run-rn1",    "Calculator 1", reportDate, base.plusSeconds(100), "1");
        insertRunWithRunNumber("run-rn2",    "Calculator 1", reportDate, base.plusSeconds(200), "2");
        insertRunWithRunNumber("run-rnnull", "Calculator 1", reportDate, base.plusSeconds(300), null);

        List<CalculatorRun> result = repository.findAllRunsByDateAndDimension(
                reportDate, Frequency.DAILY, "1", List.of("Calculator 1"));

        assertThat(result).extracting(CalculatorRun::getRunId)
                .containsExactlyInAnyOrder("run-rn1", "run-rnnull");
    }

    // ---------------------------------------------------------------
    // upsert — null run_number must not throw (pins the Types.VARCHAR fix)
    // ---------------------------------------------------------------

    @Test
    void upsert_nullRunNumber_succeeds() {
        LocalDate date = LocalDate.of(2026, 5, 20);
        Instant start = Instant.parse("2026-05-20T05:00:00Z");

        CalculatorRun run = CalculatorRun.builder()
                .runId("run-null-rn")
                .calculatorId("calc-agnostic")
                .calculatorName("modelled-exposure")
                .tenantId("t1")
                .frequency(com.company.observability.domain.enums.Frequency.DAILY)
                .reportingDate(date)
                .startTime(start)
                .status(RunStatus.RUNNING)
                .createdAt(start)
                .build();  // runNumber is null — would crash before the Types.VARCHAR fix

        CalculatorRun saved = repository.upsert(run);

        assertThat(saved.getRunId()).isEqualTo("run-null-rn");
        assertThat(saved.getRunNumber()).isNull();
    }

    // ---------------------------------------------------------------
    // upsert — immutable-column protection
    // ---------------------------------------------------------------

    @Test
    void upsert_onConflict_immutableColumnsNotOverwritten() {
        LocalDate date = LocalDate.of(2026, 4, 10);
        Instant originalStart = Instant.parse("2026-04-10T05:00:00Z");

        CalculatorRun first = CalculatorRun.builder()
                .runId("run-immutable")
                .calculatorId("calc-1")
                .calculatorName("Original Name")
                .tenantId("tenant-1")
                .frequency(com.company.observability.domain.enums.Frequency.DAILY)
                .reportingDate(date)
                .startTime(originalStart)
                .status(RunStatus.RUNNING)
                .slaBand(null)
                .createdAt(originalStart)
                .build();
        repository.upsert(first);

        // Second upsert on same PK with attempted mutations to immutable columns
        CalculatorRun second = CalculatorRun.builder()
                .runId("run-immutable")
                .calculatorId("calc-1")
                .calculatorName("CHANGED NAME")        // ON CONFLICT DO UPDATE does NOT include this column
                .tenantId("tenant-1")
                .frequency(com.company.observability.domain.enums.Frequency.DAILY)
                .reportingDate(date)
                .startTime(originalStart.plusSeconds(9999)) // ON CONFLICT DO UPDATE does NOT include this column
                .status(RunStatus.SUCCESS)              // mutable — should be updated
                .slaBand(null)
                .createdAt(originalStart)
                .build();
        repository.upsert(second);

        Optional<CalculatorRun> saved = repository.findById("run-immutable", date);
        assertThat(saved).isPresent();
        assertThat(saved.get().getCalculatorName()).isEqualTo("Original Name");
        assertThat(saved.get().getStartTime()).isEqualTo(originalStart);
        assertThat(saved.get().getStatus()).isEqualTo(RunStatus.SUCCESS); // mutable, updated
    }

    // ---------------------------------------------------------------
    // markSlaBreach — idempotency and status guard
    // ---------------------------------------------------------------

    @Test
    void markSlaBreach_idempotent_secondCallReturnsZero() {
        LocalDate date = LocalDate.of(2026, 4, 10);
        Instant start = Instant.parse("2026-04-10T05:00:00Z");
        insertRunWithStatus("run-breach", "calc-1", date, start, "RUNNING");

        int firstCall  = repository.markSlaBreach("run-breach", date, SlaBand.LATE, "Exceeded SLA deadline");
        int secondCall = repository.markSlaBreach("run-breach", date, SlaBand.VERY_LATE, "Exceeded SLA deadline again");

        assertThat(firstCall).isEqualTo(1);
        assertThat(secondCall).isZero(); // sla_band IS NULL guard → second call skipped

        Optional<CalculatorRun> run = repository.findById("run-breach", date);
        assertThat(run).isPresent();
        assertThat(run.get().getSlaBand()).isEqualTo(SlaBand.LATE); // first call wins
    }

    @Test
    void markSlaBreach_completedRun_notUpdated() {
        LocalDate date = LocalDate.of(2026, 4, 10);
        Instant start = Instant.parse("2026-04-10T05:00:00Z");
        insertRunWithStatus("run-complete", "calc-1", date, start, "SUCCESS");

        int updated = repository.markSlaBreach("run-complete", date, SlaBand.LATE, "Breach reason");

        assertThat(updated).isZero(); // WHERE status='RUNNING' excludes completed runs

        Optional<CalculatorRun> run = repository.findById("run-complete", date);
        assertThat(run).isPresent();
        assertThat(run.get().getSlaBand()).isNull();
    }

    private void insertRunWithRunNumber(String runId, String calculatorName, LocalDate reportingDate,
                                        Instant createdAt, String runNumber) {
        jdbcTemplate.update("""
            INSERT INTO calculator_runs (
                run_id, calculator_id, calculator_name, tenant_id, frequency, reporting_date,
                start_time, end_time, duration_ms, status, sla_band, run_number, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?)
            """,
                runId, "calc-1", calculatorName, "tenant-1", "DAILY", reportingDate,
                Timestamp.from(createdAt.minusSeconds(60)),
                Timestamp.from(createdAt),
                60000L,
                "SUCCESS",
                runNumber,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt)
        );
    }

    private void insertRunWithStatus(String runId, String calculatorId,
                                     LocalDate reportingDate, Instant start, String status) {
        jdbcTemplate.update("""
            INSERT INTO calculator_runs (
                run_id, calculator_id, calculator_name, tenant_id, frequency, reporting_date,
                start_time, status, sla_band, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)
            """,
                runId, calculatorId, "Calculator 1", "tenant-1", "DAILY", reportingDate,
                Timestamp.from(start), status,
                Timestamp.from(start), Timestamp.from(start)
        );
    }

    private void insertRunWithSlaBand(String runId, String calculatorId, LocalDate reportingDate,
                                      Instant createdAt, String slaBand) {
        jdbcTemplate.update("""
            INSERT INTO calculator_runs (
                run_id, calculator_id, calculator_name, tenant_id, frequency, reporting_date,
                start_time, end_time, duration_ms, status, sla_band, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
                runId, calculatorId, "Calculator 1", "tenant-1", "DAILY", reportingDate,
                Timestamp.from(createdAt.minusSeconds(60)),
                Timestamp.from(createdAt),
                60000L,
                "SUCCESS",
                slaBand,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt)
        );
    }
}
