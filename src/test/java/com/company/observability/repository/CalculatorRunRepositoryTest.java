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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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

    private CalculatorRunRepository repository;

    @BeforeEach
    void setUp() {
        repository = new CalculatorRunRepository(jdbcTemplate, redisCache, jsonbConverter, new SimpleMeterRegistry());
    }

    @Test
    void upsert_tracksRunningStateInRedis() {
        CalculatorRun savedRun = run("calc-1", "run-1");
        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(savedRun));

        repository.upsert(savedRun);

        verify(redisCache).trackRunningState(savedRun);
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
