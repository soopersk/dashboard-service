package com.company.observability.repository;

import com.company.observability.domain.SlaBreachEvent;
import com.company.observability.domain.enums.AlertStatus;
import com.company.observability.domain.enums.BreachType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Import(SlaBreachEventRepository.class)
class SlaBreachEventRepositoryJdbcTest extends PostgresJdbcIntegrationTestBase {

    @Autowired
    private SlaBreachEventRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("TRUNCATE TABLE sla_breach_events RESTART IDENTITY");
    }

    @Test
    void keysetPagination_returnsStableNextPage_withoutOverlap() {
        Instant t1 = Instant.parse("2026-02-22T12:00:00Z");
        Instant t2 = Instant.parse("2026-02-22T11:00:00Z");
        Instant t3 = Instant.parse("2026-02-22T10:00:00Z");

        save("run-1", t1);
        save("run-2", t2);
        save("run-3", t3);

        // no band filter — retrieve all
        List<SlaBreachEvent> firstPage = repository.findByCalculatorIdKeyset(
                "calc-1", 30, null, null, null, 2
        );
        assertEquals(2, firstPage.size());
        assertEquals("run-1", firstPage.get(0).getRunId());
        assertEquals("run-2", firstPage.get(1).getRunId());

        SlaBreachEvent cursor = firstPage.get(1);
        List<SlaBreachEvent> secondPage = repository.findByCalculatorIdKeyset(
                "calc-1", 30, null, cursor.getCreatedAt(), cursor.getBreachId(), 2
        );
        assertEquals(1, secondPage.size());
        assertEquals("run-3", secondPage.get(0).getRunId());
    }

    @Test
    void paginatedAndCount_noFilter_returnsAllRows() {
        save("run-a", Instant.parse("2026-02-22T12:00:00Z"));
        save("run-b", Instant.parse("2026-02-22T11:00:00Z"));
        save("run-c", Instant.parse("2026-02-22T10:00:00Z"));

        List<SlaBreachEvent> page = repository.findByCalculatorIdPaginated(
                "calc-1", 30, null, 0, 10
        );
        long count = repository.countByCalculatorIdAndPeriod(
                "calc-1", 30, null
        );

        assertEquals(3, page.size());
        assertEquals(3L, count);
    }

    private void save(String runId, Instant createdAt) {
        repository.save(SlaBreachEvent.builder()
                .runId(runId)
                .calculatorId("calc-1")
                .calculatorName("Calculator 1")
                .tenantId("tenant-1")
                .breachType(BreachType.TIME_EXCEEDED)
                .expectedValue(100L)
                .actualValue(200L)
                .alerted(false)
                .alertStatus(AlertStatus.PENDING)
                .retryCount(0)
                .createdAt(createdAt)
                .build());
    }
}
