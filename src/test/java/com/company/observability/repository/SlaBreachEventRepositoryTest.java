package com.company.observability.repository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlaBreachEventRepositoryTest {

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    private SlaBreachEventRepository repository;

    @BeforeEach
    void setUp() {
        repository = new SlaBreachEventRepository(jdbcTemplate, new SimpleMeterRegistry());
    }

    @Test
    void findByCalculatorIdPaginated_withBandFilter_usesBandJoinAndLimitOffset() {
        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());

        repository.findByCalculatorIdPaginated("calc-1", 30, "VERY_LATE", 40, 20);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("AND cr.sla_band = :band"), "expected band filter, got: " + sql);
        assertTrue(sql.contains("ORDER BY sbe.created_at DESC"));
        assertTrue(sql.contains("LIMIT :limit OFFSET :offset"));
    }

    @Test
    void findByCalculatorIdKeyset_withCursorAndBandFilter_usesStableKeysetPredicateAndOrder() {
        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());

        repository.findByCalculatorIdKeyset(
                "calc-1", 30, "LATE",
                Instant.parse("2026-02-22T10:00:00Z"), 123L, 25
        );

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("AND cr.sla_band = :band"), "expected band filter, got: " + sql);
        assertTrue(sql.contains("AND (sbe.created_at, sbe.breach_id) < (:cursorCreatedAt, :cursorBreachId)"));
        assertTrue(sql.contains("ORDER BY created_at DESC, breach_id DESC"));
        assertTrue(sql.contains("LIMIT :limit"));
    }

    @Test
    void findByCalculatorIdPaginated_withFailedBandFilter_usesStatusInFilter() {
        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());

        repository.findByCalculatorIdPaginated("calc-1", 30, "FAILED", 0, 10);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("AND cr.status IN ('FAILED','TIMEOUT')"), "expected status filter, got: " + sql);
    }
}
