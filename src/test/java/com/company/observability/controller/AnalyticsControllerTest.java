package com.company.observability.controller;

import com.company.observability.config.TestMetricsConfig;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.dto.response.RunPerformanceData;
import com.company.observability.service.AnalyticsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AnalyticsController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMetricsConfig.class)
class AnalyticsControllerTest {

    private static final String TENANT_HEADER = "X-Tenant-Id";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnalyticsService analyticsService;

    @Test
    void getRunExecutions_returns200WithRawRunRows() throws Exception {
        RunPerformanceData response = new RunPerformanceData(
                "calc-1", "Calculator One", "DAILY", 30, 720000L,
                2, 0, 1, 0, 1,
                List.of(
                        new RunPerformanceData.RunDataPoint(
                                "run-split-1", LocalDate.parse("2026-05-11"),
                                Instant.parse("2026-05-11T03:59:50Z"),
                                Instant.parse("2026-05-11T04:08:10Z"),
                                500000L, "SUCCESS", null, "ON_TIME", null,
                                Instant.parse("2026-05-11T04:00:00Z"),
                                Instant.parse("2026-05-11T06:30:00Z"), "1", 300000L),
                        new RunPerformanceData.RunDataPoint(
                                "run-split-2", LocalDate.parse("2026-05-11"),
                                Instant.parse("2026-05-11T04:00:05Z"),
                                Instant.parse("2026-05-11T04:15:45Z"),
                                940000L, "SUCCESS", "VERY_LATE", "VERY_LATE", null,
                                Instant.parse("2026-05-11T04:00:00Z"),
                                Instant.parse("2026-05-11T06:30:00Z"), "1", 300000L)
                ),
                Instant.parse("2026-05-11T04:00:00Z"),
                Instant.parse("2026-05-11T06:30:00Z"));

        when(analyticsService.getRunExecutionsByName(eq("capitalcalc"), eq(30),
                eq(Frequency.DAILY), isNull(), any(LocalDate.class)))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/analytics/calculators/capitalcalc/executions")
                        .header(TENANT_HEADER, "tenant-a")
                        .param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=60")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")))
                .andExpect(jsonPath("$.runs").isArray())
                .andExpect(jsonPath("$.runs.length()").value(2))
                .andExpect(jsonPath("$.runs[0].runId").value("run-split-1"))
                .andExpect(jsonPath("$.runs[1].runId").value("run-split-2"))
                .andExpect(jsonPath("$.runs[0].subRunIds").doesNotExist());

        verify(analyticsService).getRunExecutionsByName(eq("capitalcalc"), eq(30),
                eq(Frequency.DAILY), isNull(), any(LocalDate.class));
    }

    @Test
    void getRunExecutions_withRunNumber_passesRunNumberToService() throws Exception {
        when(analyticsService.getRunExecutionsByName(eq("capitalcalc"), eq(30),
                eq(Frequency.DAILY), eq("1"), any(LocalDate.class)))
                .thenReturn(new RunPerformanceData("capitalcalc", "capitalcalc", "DAILY", 30, 0L,
                        0, 0, 0, 0, 0, List.of(), null, null));

        mockMvc.perform(get("/api/v1/analytics/calculators/capitalcalc/executions")
                        .header(TENANT_HEADER, "tenant-a")
                        .param("days", "30")
                        .param("run_number", "1"))
                .andExpect(status().isOk());

        verify(analyticsService).getRunExecutionsByName(eq("capitalcalc"), eq(30),
                eq(Frequency.DAILY), eq("1"), any(LocalDate.class));
    }

    @Test
    void getRunExecutions_withDataAsOfDate_passesDateToService() throws Exception {
        LocalDate pastDate = LocalDate.of(2026, 5, 1);
        RunPerformanceData response = new RunPerformanceData("capitalcalc", "capitalcalc", "DAILY", 30, 0L,
                0, 0, 0, 0, 0, List.of(), null, null);
        when(analyticsService.getRunExecutionsByName(eq("capitalcalc"), eq(30),
                eq(Frequency.DAILY), isNull(), eq(pastDate)))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/analytics/calculators/capitalcalc/executions")
                        .header(TENANT_HEADER, "tenant-a")
                        .param("days", "30")
                        .param("data_as_of_date", "2026-05-01"))
                .andExpect(status().isOk());

        verify(analyticsService).getRunExecutionsByName(eq("capitalcalc"), eq(30),
                eq(Frequency.DAILY), isNull(), eq(pastDate));
    }

    @Test
    void getRunExecutions_missingTenantId_succeeds() throws Exception {
        when(analyticsService.getRunExecutionsByName(eq("capitalcalc"), eq(30),
                eq(Frequency.DAILY), isNull(), any(LocalDate.class)))
                .thenReturn(new RunPerformanceData("capitalcalc", "capitalcalc", "DAILY", 30, 0L,
                        0, 0, 0, 0, 0, List.of(), null, null));

        mockMvc.perform(get("/api/v1/analytics/calculators/capitalcalc/executions")
                        .param("days", "30"))
                .andExpect(status().isOk());
    }
}
