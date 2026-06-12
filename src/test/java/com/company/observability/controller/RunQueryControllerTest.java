package com.company.observability.controller;

import com.company.observability.config.TestMetricsConfig;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.dto.response.CalculatorBatchRunsResponse;
import com.company.observability.service.ExpectedRunsService;
import com.company.observability.service.CalculatorNameResolver;
import com.company.observability.service.CalculatorStateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import org.junit.jupiter.api.BeforeEach;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RunQueryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMetricsConfig.class)
class RunQueryControllerTest {

    private static final String TENANT_HEADER = "X-Tenant-Id";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CalculatorStateService calculatorStateService;

    @MockitoBean
    private CalculatorNameResolver nameResolver;

    @MockitoBean
    private ExpectedRunsService expectedRunsService;

    @BeforeEach
    void configurePassthroughResolver() {
        // Default: each alias resolves to itself (no alias config in controller tests)
        lenient().when(nameResolver.resolveAll(any())).thenAnswer(inv -> {
            List<String> aliases = inv.getArgument(0);
            Map<String, List<String>> result = new LinkedHashMap<>();
            for (String a : aliases) result.put(a, List.of(a));
            return result;
        });
        // Default: padToExpected is a no-op pass-through (no dimension config in controller tests)
        lenient().when(expectedRunsService.padToExpected(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void batchRuns_returns200WithMapKeyedByCalculatorName() throws Exception {
        var entry = new CalculatorBatchRunsResponse.CalculatorEntry("capitalcalc", null, List.of());
        when(calculatorStateService.getState(eq(LocalDate.of(2026, 3, 6)),
                eq(Frequency.DAILY), eq("1"), eq(List.of("capitalcalc"))))
                .thenReturn(Map.of("capitalcalc", entry));

        mockMvc.perform(get("/api/v1/calculators/batch/runs")
                        .param("reporting_date", "2026-03-06")
                        .param("frequency", "DAILY")
                        .param("run_number", "1")
                        .param("keys", "capitalcalc")
                        .header(TENANT_HEADER, "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportingDate").value("2026-03-06"))
                .andExpect(jsonPath("$.runNumber").value("1"))
                .andExpect(jsonPath("$.calculators.capitalcalc.calculatorName").value("capitalcalc"))
                .andExpect(jsonPath("$.calculators.capitalcalc.calculatorId").doesNotExist())
                .andExpect(jsonPath("$.calculators.capitalcalc.runs").isArray());
    }

    @Test
    void batchRuns_returns400WhenReportingDateMissing() throws Exception {
        mockMvc.perform(get("/api/v1/calculators/batch/runs")
                        .param("keys", "capital")
                        .header(TENANT_HEADER, "t1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void batchRuns_pipeSeparatedKeysParsedToList() throws Exception {
        when(calculatorStateService.getState(any(), any(), isNull(),
                eq(List.of("capital", "modelled-exposure", "portfolio"))))
                .thenReturn(Map.of());

        mockMvc.perform(get("/api/v1/calculators/batch/runs")
                        .param("reporting_date", "2026-03-06")
                        .param("keys", "capital|modelled-exposure|portfolio")
                        .header(TENANT_HEADER, "t1"))
                .andExpect(status().isOk());

        verify(calculatorStateService).getState(any(), any(), isNull(),
                eq(List.of("capital", "modelled-exposure", "portfolio")));
    }

    @Test
    void batchRuns_omittedRunNumberPassesNullToService() throws Exception {
        var entry = new CalculatorBatchRunsResponse.CalculatorEntry("capital", null, List.of());
        when(calculatorStateService.getState(eq(LocalDate.of(2026, 3, 6)),
                eq(Frequency.DAILY), isNull(), eq(List.of("capital"))))
                .thenReturn(Map.of("capital", entry));

        mockMvc.perform(get("/api/v1/calculators/batch/runs")
                        .param("reporting_date", "2026-03-06")
                        .param("keys", "capital")
                        .header(TENANT_HEADER, "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runNumber").doesNotExist());

        verify(calculatorStateService).getState(any(), any(), isNull(), any());
    }

    @Test
    void batchRuns_runningEntry_setsShortCacheControl() throws Exception {
        var runEntry = CalculatorBatchRunsResponse.RunEntry.builder()
                .runId("r-1").status("RUNNING").isRerun(false).build();
        var entry = new CalculatorBatchRunsResponse.CalculatorEntry("capital", null, List.of(runEntry));
        when(calculatorStateService.getState(any(), any(), isNull(), eq(List.of("capital"))))
                .thenReturn(Map.of("capital", entry));

        mockMvc.perform(get("/api/v1/calculators/batch/runs")
                        .param("reporting_date", "2026-03-06")
                        .param("keys", "capital")
                        .header(TENANT_HEADER, "t1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=5, private"));
    }

    @Test
    void batchRuns_allTerminal_setsLongCacheControl() throws Exception {
        var runEntry = CalculatorBatchRunsResponse.RunEntry.builder()
                .runId("r-1").status("SUCCESS").isRerun(false).build();
        var entry = new CalculatorBatchRunsResponse.CalculatorEntry("capital", null, List.of(runEntry));
        when(calculatorStateService.getState(any(), any(), isNull(), eq(List.of("capital"))))
                .thenReturn(Map.of("capital", entry));

        mockMvc.perform(get("/api/v1/calculators/batch/runs")
                        .param("reporting_date", "2026-03-06")
                        .param("keys", "capital")
                        .header(TENANT_HEADER, "t1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=30, private"));
    }

    @Test
    void batchRuns_acceptsArbitraryRunNumber() throws Exception {
        when(calculatorStateService.getState(any(), any(), eq("3"), any()))
                .thenReturn(Map.of());
        mockMvc.perform(get("/api/v1/calculators/batch/runs")
                        .param("reporting_date", "2026-03-06")
                        .param("run_number", "3")
                        .param("keys", "capital")
                        .header(TENANT_HEADER, "t1"))
                .andExpect(status().isOk());
    }
}
