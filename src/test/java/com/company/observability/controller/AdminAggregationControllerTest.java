package com.company.observability.controller;

import com.company.observability.config.TestMetricsConfig;
import com.company.observability.exception.GlobalExceptionHandler;
import com.company.observability.scheduled.DailyAggregationJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminAggregationController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, TestMetricsConfig.class})
class AdminAggregationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DailyAggregationJob dailyAggregationJob;

    @Test
    void recompute_happyPath_delegatesAndReturnsCounts() throws Exception {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 7, 1);

        when(dailyAggregationJob.recomputeRange(eq(from), eq(to)))
                .thenReturn(new DailyAggregationJob.RecomputeOutcome(5, 3L));

        mockMvc.perform(post("/api/v1/admin/aggregation/recompute")
                        .param("from", "2026-01-01")
                        .param("to", "2026-07-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-01-01"))
                .andExpect(jsonPath("$.to").value("2026-07-01"))
                .andExpect(jsonPath("$.rowsRecomputed").value(5))
                .andExpect(jsonPath("$.profilesWarmed").value(3));

        verify(dailyAggregationJob).recomputeRange(eq(from), eq(to));
    }

    @Test
    void recompute_fromAfterTo_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/admin/aggregation/recompute")
                        .param("from", "2026-07-01")
                        .param("to", "2026-01-01"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(dailyAggregationJob);
    }

    @Test
    void recompute_missingFrom_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/admin/aggregation/recompute"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(dailyAggregationJob);
    }

    @Test
    void recompute_missingTo_defaultsToToday() throws Exception {
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);

        when(dailyAggregationJob.recomputeRange(eq(LocalDate.of(2026, 1, 1)), eq(today)))
                .thenReturn(new DailyAggregationJob.RecomputeOutcome(1, 1L));

        mockMvc.perform(post("/api/v1/admin/aggregation/recompute")
                        .param("from", "2026-01-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.to").value(today.toString()));

        verify(dailyAggregationJob).recomputeRange(eq(LocalDate.of(2026, 1, 1)), eq(today));
    }

    @Test
    void recompute_spanTooLarge_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/admin/aggregation/recompute")
                        .param("from", "2020-01-01")
                        .param("to", "2026-01-01"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(dailyAggregationJob);
    }
}
