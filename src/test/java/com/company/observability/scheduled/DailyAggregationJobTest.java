package com.company.observability.scheduled;

import com.company.observability.config.AggregationProperties;
import com.company.observability.config.SlaProperties;
import com.company.observability.domain.CalculatorProfile;
import com.company.observability.repository.DailyAggregateRepository;
import com.company.observability.service.CalculatorProfileService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyAggregationJobTest {

    @Mock private DailyAggregateRepository dailyAggregateRepository;
    @Mock private CalculatorProfileService calculatorProfileService;

    private DailyAggregationJob job;

    @BeforeEach
    void setUp() {
        job = new DailyAggregationJob(
                dailyAggregateRepository, calculatorProfileService,
                new AggregationProperties(), new SlaProperties(), new SimpleMeterRegistry());
        job.registerGauges();
    }

    @Test
    void runDailyAggregation_recomputesTrailingWindow_andWarmsAllThreeTiers() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = today.minusDays(3); // default recompute-window-days

        when(dailyAggregateRepository.recomputeForDateRange(from, today)).thenReturn(5);

        CalculatorProfile dailyBlended  = new CalculatorProfile("calc-1", "DAILY",   null, null, 100L, 0, 0, 10);
        CalculatorProfile monthlyBlended = new CalculatorProfile("calc-1", "MONTHLY", null, null, 200L, 0, 0, 3);
        CalculatorProfile dailyScoped   = new CalculatorProfile("calc-1", "DAILY",   "1",  null, 120L, 0, 0, 5);
        CalculatorProfile dailyDim      = new CalculatorProfile("calc-1", "DAILY",   "1",  "WMAP", 130L, 0, 0, 4);

        when(dailyAggregateRepository.findAllProfiles("DAILY", 30)).thenReturn(List.of(dailyBlended));
        when(dailyAggregateRepository.findAllProfiles("MONTHLY", 395)).thenReturn(List.of(monthlyBlended));
        when(dailyAggregateRepository.findAllProfilesByRunNumber(eq("DAILY"), anyInt()))
                .thenReturn(List.of(dailyScoped));
        when(dailyAggregateRepository.findAllProfilesByRunNumber(eq("MONTHLY"), anyInt()))
                .thenReturn(List.of());
        when(dailyAggregateRepository.findAllProfilesByRunNumberAndDimension(eq("DAILY"), anyInt()))
                .thenReturn(List.of(dailyDim));
        when(dailyAggregateRepository.findAllProfilesByRunNumberAndDimension(eq("MONTHLY"), anyInt()))
                .thenReturn(List.of());

        job.runDailyAggregation();

        verify(dailyAggregateRepository).recomputeForDateRange(eq(from), eq(today));
        // Third warming tier must be invoked for each frequency
        verify(dailyAggregateRepository, times(2)).findAllProfilesByRunNumberAndDimension(anyString(), anyInt());
        // Four profiles total warmed: blended daily, blended monthly, scoped daily, dim-scoped daily
        verify(calculatorProfileService, times(4)).warm(org.mockito.ArgumentMatchers.any());
        verify(calculatorProfileService).warm(dailyDim);
    }
}
