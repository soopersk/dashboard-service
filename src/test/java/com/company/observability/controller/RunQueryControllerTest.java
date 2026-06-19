package com.company.observability.controller;

import com.company.observability.config.TestMetricsConfig;
import com.company.observability.domain.enums.Dimension;
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

import java.time.Instant;
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
        // Default: each alias resolves to itself (no alias config in controller tests).
        // Uses doAnswer form to avoid the stub firing when Mockito calls resolveAll(null)
        // during when().thenReturn() stub registration (which would NPE on null iteration).
        lenient().doAnswer(inv -> {
            List<String> aliases = inv.getArgument(0);
            // Guard: Mockito calls resolveAll(null) when registering when().thenReturn() stubs
            if (aliases == null) return new LinkedHashMap<>();
            Map<String, List<String>> result = new LinkedHashMap<>();
            for (String a : aliases) result.put(a, List.of(a));
            return result;
        }).when(nameResolver).resolveAll(any());
        // Default: NONE dimension so mergeEntries uses the combined key (safe for all existing tests)
        lenient().when(nameResolver.dimensionOf(any())).thenReturn(Dimension.NONE);
        // Default: not run-number-aware (non-strict path)
        lenient().when(nameResolver.isRunNumberAware(any())).thenReturn(false);
        // Default: padToExpected is a no-op pass-through (no dimension config in controller tests)
        lenient().doAnswer(inv -> inv.getArgument(0))
                .when(expectedRunsService).padToExpected(any(), any(), any(), any());
    }

    @Test
    void batchRuns_returns200WithMapKeyedByCalculatorName() throws Exception {
        var entry = new CalculatorBatchRunsResponse.CalculatorEntry("capitalcalc", null, List.of());
        when(calculatorStateService.getState(eq(LocalDate.of(2026, 3, 6)),
                eq(Frequency.DAILY), eq("1"), eq(List.of("capitalcalc")), anyBoolean()))
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
                eq(List.of("capital", "modelled-exposure", "portfolio")), anyBoolean()))
                .thenReturn(Map.of());

        mockMvc.perform(get("/api/v1/calculators/batch/runs")
                        .param("reporting_date", "2026-03-06")
                        .param("keys", "capital|modelled-exposure|portfolio")
                        .header(TENANT_HEADER, "t1"))
                .andExpect(status().isOk());

        verify(calculatorStateService).getState(any(), any(), isNull(),
                eq(List.of("capital", "modelled-exposure", "portfolio")), anyBoolean());
    }

    @Test
    void batchRuns_omittedRunNumberPassesNullToService() throws Exception {
        var entry = new CalculatorBatchRunsResponse.CalculatorEntry("capital", null, List.of());
        when(calculatorStateService.getState(eq(LocalDate.of(2026, 3, 6)),
                eq(Frequency.DAILY), isNull(), eq(List.of("capital")), anyBoolean()))
                .thenReturn(Map.of("capital", entry));

        mockMvc.perform(get("/api/v1/calculators/batch/runs")
                        .param("reporting_date", "2026-03-06")
                        .param("keys", "capital")
                        .header(TENANT_HEADER, "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runNumber").doesNotExist());

        verify(calculatorStateService).getState(any(), any(), isNull(), any(), anyBoolean());
    }

    @Test
    void batchRuns_runningEntry_setsShortCacheControl() throws Exception {
        var runEntry = CalculatorBatchRunsResponse.RunEntry.builder()
                .runId("r-1").status("RUNNING").isRerun(false).build();
        var entry = new CalculatorBatchRunsResponse.CalculatorEntry("capital", null, List.of(runEntry));
        when(calculatorStateService.getState(any(), any(), isNull(), eq(List.of("capital")), anyBoolean()))
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
        when(calculatorStateService.getState(any(), any(), isNull(), eq(List.of("capital")), anyBoolean()))
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
        when(calculatorStateService.getState(any(), any(), eq("3"), any(), anyBoolean()))
                .thenReturn(Map.of());
        mockMvc.perform(get("/api/v1/calculators/batch/runs")
                        .param("reporting_date", "2026-03-06")
                        .param("run_number", "3")
                        .param("keys", "capital")
                        .header(TENANT_HEADER, "t1"))
                .andExpect(status().isOk());
    }

    // ── Dimension-aware dedup tests ─────────────────────────────────────────────

    @Test
    void mergeEntries_regionCalculator_collapsesBatchAndIntraForSameRegion() throws Exception {
        // capital is REGION-dimensioned; AMER/BATCH and AMER/INTRA must collapse to one AMER row
        when(nameResolver.resolveAll(eq(List.of("capital"))))
                .thenReturn(Map.of("capital", List.of("capitalcalcdev")));
        when(nameResolver.dimensionOf("capital")).thenReturn(Dimension.REGION);

        Instant earlier = Instant.parse("2026-03-31T08:00:00Z");
        Instant later   = Instant.parse("2026-03-31T09:00:00Z");
        var batch = CalculatorBatchRunsResponse.RunEntry.builder()
                .runId("r-batch").region("AMER").runType("BATCH").runNumber("2")
                .status("SUCCESS").slaStatus("ON_TIME").startTime(earlier).isRerun(false).build();
        var intra = CalculatorBatchRunsResponse.RunEntry.builder()
                .runId("r-intra").region("AMER").runType("INTRA").runNumber("2")
                .status("SUCCESS").slaStatus("ON_TIME").startTime(later).isRerun(false).build();
        var entry = new CalculatorBatchRunsResponse.CalculatorEntry("capitalcalcdev", null,
                List.of(batch, intra));
        when(calculatorStateService.getState(any(), any(), any(), eq(List.of("capitalcalcdev")), anyBoolean()))
                .thenReturn(Map.of("capitalcalcdev", entry));

        mockMvc.perform(get("/api/v1/calculators/batch/runs")
                        .param("reporting_date", "2026-03-31")
                        .param("frequency", "MONTHLY")
                        .param("run_number", "2")
                        .param("keys", "capital")
                        .header(TENANT_HEADER, "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculators.capital.runs.length()").value(1))
                .andExpect(jsonPath("$.calculators.capital.runs[0].region").value("AMER"))
                .andExpect(jsonPath("$.calculators.capital.runs[0].runId").value("r-intra"))
                .andExpect(jsonPath("$.calculators.capital.runs[0].isRerun").value(true))
                .andExpect(jsonPath("$.calculators.capital.runs[0].runNumber").value("2"));
    }

    @Test
    void mergeEntries_regionCalculator_differentRunNumbers_keepsBothRows() throws Exception {
        when(nameResolver.resolveAll(eq(List.of("capital"))))
                .thenReturn(Map.of("capital", List.of("capitalcalcdev")));
        when(nameResolver.dimensionOf("capital")).thenReturn(Dimension.REGION);

        var run1 = CalculatorBatchRunsResponse.RunEntry.builder()
                .runId("r1").region("AMER").runType("BATCH").runNumber("1")
                .status("SUCCESS").slaStatus("ON_TIME").isRerun(false).build();
        var run2 = CalculatorBatchRunsResponse.RunEntry.builder()
                .runId("r2").region("AMER").runType("BATCH").runNumber("2")
                .status("SUCCESS").slaStatus("ON_TIME").isRerun(false).build();
        var entry = new CalculatorBatchRunsResponse.CalculatorEntry("capitalcalcdev", null,
                List.of(run1, run2));
        when(calculatorStateService.getState(any(), any(), any(), eq(List.of("capitalcalcdev")), anyBoolean()))
                .thenReturn(Map.of("capitalcalcdev", entry));

        mockMvc.perform(get("/api/v1/calculators/batch/runs")
                        .param("reporting_date", "2026-03-31")
                        .param("keys", "capital")
                        .header(TENANT_HEADER, "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculators.capital.runs.length()").value(2));
    }

    @Test
    void mergeEntries_multiRealName_sameRegionAndRunNumber_collapsesToOne() throws Exception {
        // Two real names both emitting AMER/run_number=2 → one row, isRerun=true
        when(nameResolver.resolveAll(eq(List.of("capital"))))
                .thenReturn(Map.of("capital", List.of("capitalcalcdev", "capitalcalcprod")));
        when(nameResolver.dimensionOf("capital")).thenReturn(Dimension.REGION);

        Instant t1 = Instant.parse("2026-03-31T07:00:00Z");
        Instant t2 = Instant.parse("2026-03-31T08:00:00Z");
        var entryA = new CalculatorBatchRunsResponse.CalculatorEntry("capitalcalcdev", null,
                List.of(CalculatorBatchRunsResponse.RunEntry.builder()
                        .runId("rA").region("AMER").runNumber("2")
                        .status("SUCCESS").slaStatus("ON_TIME").startTime(t1).isRerun(false).build()));
        var entryB = new CalculatorBatchRunsResponse.CalculatorEntry("capitalcalcprod", null,
                List.of(CalculatorBatchRunsResponse.RunEntry.builder()
                        .runId("rB").region("AMER").runNumber("2")
                        .status("SUCCESS").slaStatus("ON_TIME").startTime(t2).isRerun(false).build()));
        when(calculatorStateService.getState(any(), any(), any(),
                eq(List.of("capitalcalcdev", "capitalcalcprod")), anyBoolean()))
                .thenReturn(Map.of("capitalcalcdev", entryA, "capitalcalcprod", entryB));

        mockMvc.perform(get("/api/v1/calculators/batch/runs")
                        .param("reporting_date", "2026-03-31")
                        .param("run_number", "2")
                        .param("keys", "capital")
                        .header(TENANT_HEADER, "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculators.capital.runs.length()").value(1))
                .andExpect(jsonPath("$.calculators.capital.runs[0].runId").value("rB"))
                .andExpect(jsonPath("$.calculators.capital.runs[0].isRerun").value(true));
    }

    @Test
    void mergeEntries_runTypeCalculator_keepsDistinctRunTypes() throws Exception {
        when(nameResolver.resolveAll(eq(List.of("modelled-exposure"))))
                .thenReturn(Map.of("modelled-exposure", List.of("modelledexposurecalc")));
        when(nameResolver.dimensionOf("modelled-exposure")).thenReturn(Dimension.RUN_TYPE);

        var etd = CalculatorBatchRunsResponse.RunEntry.builder()
                .runId("r-etd").runType("ETD").runNumber("1")
                .status("SUCCESS").slaStatus("ON_TIME").isRerun(false).build();
        var otc = CalculatorBatchRunsResponse.RunEntry.builder()
                .runId("r-otc").runType("OTC").runNumber("1")
                .status("SUCCESS").slaStatus("ON_TIME").isRerun(false).build();
        var entry = new CalculatorBatchRunsResponse.CalculatorEntry("modelledexposurecalc", null,
                List.of(etd, otc));
        when(calculatorStateService.getState(any(), any(), any(), eq(List.of("modelledexposurecalc")), anyBoolean()))
                .thenReturn(Map.of("modelledexposurecalc", entry));

        mockMvc.perform(get("/api/v1/calculators/batch/runs")
                        .param("reporting_date", "2026-03-06")
                        .param("keys", "modelled-exposure")
                        .header(TENANT_HEADER, "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculators.modelled-exposure.runs.length()").value(2));
    }

    @Test
    void mergeEntries_startedRunBeatsNotStartedInSameBucket() throws Exception {
        when(nameResolver.resolveAll(eq(List.of("capital"))))
                .thenReturn(Map.of("capital", List.of("capitalcalcdev")));
        when(nameResolver.dimensionOf("capital")).thenReturn(Dimension.REGION);

        var notStarted = CalculatorBatchRunsResponse.RunEntry.builder()
                .runId(null).region("EMEA").runNumber("1")
                .status("NOT_STARTED").slaStatus("ON_TIME").isRerun(false).build();
        var running = CalculatorBatchRunsResponse.RunEntry.builder()
                .runId("r-emea").region("EMEA").runNumber("1")
                .status("RUNNING").slaStatus("ON_TIME").startTime(Instant.parse("2026-03-06T06:00:00Z"))
                .isRerun(false).build();
        var entry = new CalculatorBatchRunsResponse.CalculatorEntry("capitalcalcdev", null,
                List.of(notStarted, running));
        when(calculatorStateService.getState(any(), any(), any(), eq(List.of("capitalcalcdev")), anyBoolean()))
                .thenReturn(Map.of("capitalcalcdev", entry));

        mockMvc.perform(get("/api/v1/calculators/batch/runs")
                        .param("reporting_date", "2026-03-06")
                        .param("run_number", "1")
                        .param("keys", "capital")
                        .header(TENANT_HEADER, "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculators.capital.runs.length()").value(1))
                .andExpect(jsonPath("$.calculators.capital.runs[0].status").value("RUNNING"))
                .andExpect(jsonPath("$.calculators.capital.runs[0].runId").value("r-emea"));
    }

    // ── runNumber-aware strict suppression tests ────────────────────────────

    @Test
    void strictMode_awarecalc_nullRunNumberRowDropped_numberedRowKept() throws Exception {
        // AMER has both a numbered (run_number=1) and a null-run_number real row;
        // strict mode should keep only the numbered one.
        when(nameResolver.resolveAll(eq(List.of("capital"))))
                .thenReturn(Map.of("capital", List.of("capitalcalcdev")));
        when(nameResolver.dimensionOf("capital")).thenReturn(Dimension.REGION);
        when(nameResolver.isRunNumberAware("capital")).thenReturn(true);

        var numbered = CalculatorBatchRunsResponse.RunEntry.builder()
                .runId("r-numbered").region("AMER").runNumber("1")
                .status("SUCCESS").slaStatus("ON_TIME").isRerun(false).build();
        var nullRn = CalculatorBatchRunsResponse.RunEntry.builder()
                .runId("r-null").region("AMER").runNumber(null)
                .status("SUCCESS").slaStatus("ON_TIME").isRerun(false).build();
        var entry = new CalculatorBatchRunsResponse.CalculatorEntry("capitalcalcdev", null,
                List.of(numbered, nullRn));
        when(calculatorStateService.getState(any(), any(), eq("1"), eq(List.of("capitalcalcdev")), anyBoolean()))
                .thenReturn(Map.of("capitalcalcdev", entry));

        mockMvc.perform(get("/api/v1/calculators/batch/runs")
                        .param("reporting_date", "2026-03-06")
                        .param("run_number", "1")
                        .param("keys", "capital")
                        .header(TENANT_HEADER, "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculators.capital.runs.length()").value(1))
                .andExpect(jsonPath("$.calculators.capital.runs[0].runId").value("r-numbered"))
                .andExpect(jsonPath("$.calculators.capital.runs[0].runNumber").value("1"));
    }

    @Test
    void strictMode_awarecalc_distinctCycles_bothKept() throws Exception {
        // run_number=1 and run_number=2 for AMER are distinct cycles — both must survive strict mode
        // (strict only drops null-run_number, not other numbers)
        when(nameResolver.resolveAll(eq(List.of("capital"))))
                .thenReturn(Map.of("capital", List.of("capitalcalcdev")));
        when(nameResolver.dimensionOf("capital")).thenReturn(Dimension.REGION);
        when(nameResolver.isRunNumberAware("capital")).thenReturn(true);

        var run1 = CalculatorBatchRunsResponse.RunEntry.builder()
                .runId("r1").region("AMER").runNumber("1")
                .status("SUCCESS").slaStatus("ON_TIME").isRerun(false).build();
        var run2 = CalculatorBatchRunsResponse.RunEntry.builder()
                .runId("r2").region("AMER").runNumber("2")
                .status("RUNNING").slaStatus("ON_TIME").isRerun(false).build();
        var entry = new CalculatorBatchRunsResponse.CalculatorEntry("capitalcalcdev", null,
                List.of(run1, run2));
        when(calculatorStateService.getState(any(), any(), eq("1"), eq(List.of("capitalcalcdev")), anyBoolean()))
                .thenReturn(Map.of("capitalcalcdev", entry));

        mockMvc.perform(get("/api/v1/calculators/batch/runs")
                        .param("reporting_date", "2026-03-06")
                        .param("run_number", "1")
                        .param("keys", "capital")
                        .header(TENANT_HEADER, "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculators.capital.runs.length()").value(2));
    }

    @Test
    void strictMode_agnosticCalc_nullRunNumberRowKept() throws Exception {
        // modelled-exposure is NOT run-number-aware → null rows must NOT be dropped
        when(nameResolver.resolveAll(eq(List.of("modelled-exposure"))))
                .thenReturn(Map.of("modelled-exposure", List.of("modelledexposurecalc")));
        when(nameResolver.dimensionOf("modelled-exposure")).thenReturn(Dimension.RUN_TYPE);
        when(nameResolver.isRunNumberAware("modelled-exposure")).thenReturn(false);

        var etd = CalculatorBatchRunsResponse.RunEntry.builder()
                .runId("r-etd").runType("ETD").runNumber(null)
                .status("SUCCESS").slaStatus("ON_TIME").isRerun(false).build();
        var otc = CalculatorBatchRunsResponse.RunEntry.builder()
                .runId("r-otc").runType("OTC").runNumber(null)
                .status("SUCCESS").slaStatus("ON_TIME").isRerun(false).build();
        var sft = CalculatorBatchRunsResponse.RunEntry.builder()
                .runId("r-sft").runType("SFT").runNumber(null)
                .status("SUCCESS").slaStatus("ON_TIME").isRerun(false).build();
        var entry = new CalculatorBatchRunsResponse.CalculatorEntry("modelledexposurecalc", null,
                List.of(etd, otc, sft));
        when(calculatorStateService.getState(any(), any(), isNull(), eq(List.of("modelledexposurecalc")), anyBoolean()))
                .thenReturn(Map.of("modelledexposurecalc", entry));

        mockMvc.perform(get("/api/v1/calculators/batch/runs")
                        .param("reporting_date", "2026-03-06")
                        .param("keys", "modelled-exposure")
                        .header(TENANT_HEADER, "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculators.modelled-exposure.runs.length()").value(3));
    }

    @Test
    void batchRuns_runEntryCarriesRunNumber() throws Exception {
        var runEntry = CalculatorBatchRunsResponse.RunEntry.builder()
                .runId("r-1").region("AMER").runNumber("2")
                .status("SUCCESS").slaStatus("ON_TIME").isRerun(false).build();
        var entry = new CalculatorBatchRunsResponse.CalculatorEntry("capital", null, List.of(runEntry));
        when(calculatorStateService.getState(any(), any(), eq("2"), eq(List.of("capital")), anyBoolean()))
                .thenReturn(Map.of("capital", entry));

        mockMvc.perform(get("/api/v1/calculators/batch/runs")
                        .param("reporting_date", "2026-03-06")
                        .param("run_number", "2")
                        .param("keys", "capital")
                        .header(TENANT_HEADER, "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculators.capital.runs[0].runNumber").value("2"));
    }
}
