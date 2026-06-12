package com.company.observability.repository;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.Frequency;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("local")
class CalculatorRunRepositoryDimensionalTest {

    @Autowired
    CalculatorRunRepository repository;

    @Test
    void findAllRunsByDateAndDimension_returnsEmptyForUnknownCalculator() {
        List<CalculatorRun> runs = repository.findAllRunsByDateAndDimension(
                LocalDate.of(2026, 3, 6),
                Frequency.DAILY, "1",
                List.of("unknown-calc-xyz-does-not-exist"));

        assertThat(runs).isEmpty();
    }

    @Test
    void findAllRunsByDateAndDimension_returnsEmptyForEmptyCalculatorList() {
        List<CalculatorRun> runs = repository.findAllRunsByDateAndDimension(
                LocalDate.of(2026, 3, 6),
                Frequency.DAILY, "1",
                List.of());

        assertThat(runs).isEmpty();
    }
}
