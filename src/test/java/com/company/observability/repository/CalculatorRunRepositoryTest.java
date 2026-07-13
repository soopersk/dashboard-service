package com.company.observability.repository;

import com.company.observability.cache.RedisCalculatorCache;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.util.JsonbConverter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.postgresql.util.PGobject;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.company.observability.util.ObservabilityConstants.INGESTION_JSONB_WRITE_FAILURE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalculatorRunRepositoryTest {

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;
    @Mock
    private RedisCalculatorCache redisCache;
    @Mock
    private JsonbConverter jsonbConverter;

    private SimpleMeterRegistry meterRegistry;
    private CalculatorRunRepository repository;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        repository = new CalculatorRunRepository(jdbcTemplate, redisCache, jsonbConverter, meterRegistry);
    }

    @Test
    void upsert_tracksRunningStateInRedis() {
        CalculatorRun savedRun = run("calc-1", "run-1");
        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(savedRun));

        repository.upsert(savedRun);

        verify(redisCache).trackRunningState(savedRun);
    }

    @Test
    void upsert_whenJsonbSerializeThrows_persistsNullForField_andIncrementsMetric() {
        CalculatorRun toSave = run("calc-1", "run-1");
        toSave.setRunParameters(Map.of("k", "v"));
        when(jsonbConverter.toJsonb(any())).thenThrow(new IllegalArgumentException("serialize boom"));
        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(toSave));

        repository.upsert(toSave);

        // The upsert still ran; the offending field was bound as a typed jsonb NULL, not aborted.
        ArgumentCaptor<SqlParameterSource> captor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbcTemplate).query(anyString(), captor.capture(), any(RowMapper.class));
        Object bound = captor.getValue().getValue("runParameters");
        assertThat(bound).isInstanceOf(PGobject.class);
        assertThat(((PGobject) bound).getValue()).isNull();
        assertThat(((PGobject) bound).getType()).isEqualTo("jsonb");

        assertThat(meterRegistry.counter(INGESTION_JSONB_WRITE_FAILURE, "field", "run_parameters").count())
                .isEqualTo(1.0);
    }

    @Test
    void upsert_whenJdbcThrowsDataAccessException_propagatesUnwrapped() {
        CalculatorRun toSave = run("calc-1", "run-1");
        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenThrow(new DataIntegrityViolationException("value too long for column calculator_name"));

        assertThatThrownBy(() -> repository.upsert(toSave))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("value too long");
    }

    private CalculatorRun run(String calculatorId, String runId) {
        return CalculatorRun.builder()
                .runId(runId)
                .calculatorId(calculatorId)
                .calculatorName("Calculator " + calculatorId)
                .tenantId("tenant-1")
                .frequency(Frequency.DAILY)
                .reportingDate(LocalDate.of(2026, 2, 22))
                .startTime(Instant.parse("2026-02-22T08:00:00Z"))
                .endTime(Instant.parse("2026-02-22T08:05:00Z"))
                .durationMs(300000L)
                .status(RunStatus.SUCCESS)
                .slaBand(null)
                .createdAt(Instant.parse("2026-02-22T08:05:00Z"))
                .updatedAt(Instant.parse("2026-02-22T08:05:00Z"))
                .build();
    }
}
