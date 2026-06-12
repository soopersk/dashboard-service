package com.company.observability.controller;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.exception.DomainValidationException;
import com.company.observability.exception.GlobalExceptionHandler;
import com.company.observability.logging.LifecycleLogger;
import com.company.observability.service.RunIngestionService;
import com.company.observability.config.TestMetricsConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RunIngestionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, TestMetricsConfig.class})
class RunIngestionControllerTest {

    private static final String TENANT_HEADER = "X-Tenant-Id";
    private static final Instant START_TIME = Instant.parse("2026-02-22T06:00:00Z");
    private static final Instant END_TIME = Instant.parse("2026-02-22T06:10:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RunIngestionService ingestionService;

    @MockitoBean
    private LifecycleLogger lifecycleLogger;

    @Test
    void startRun_returnsCreatedWithLocationAndResponseBody() throws Exception {
        CalculatorRun savedRun = CalculatorRun.builder()
                .runId("run-1")
                .calculatorId("calc-1")
                .calculatorName("Calculator One")
                .status(RunStatus.RUNNING)
                .startTime(START_TIME)
                .slaBand(null)
                .build();

        when(ingestionService.startRun(any(), eq("tenant-a")))
                .thenReturn(new RunIngestionService.StartRunOutcome(savedRun, true));

        String payload = """
                {
                  "runId": "run-1",
                  "calculatorId": "calc-1",
                  "calculatorName": "Calculator One",
                  "frequency": "DAILY",
                  "reportingDate": "2026-02-22",
                  "startTime": "2026-02-22T06:00:00Z",
                  "slaTime": "PT2H30M"
                }
                """;

        mockMvc.perform(post("/api/v1/runs/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT_HEADER, "tenant-a")
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/runs/run-1"))
                .andExpect(jsonPath("$.runId").value("run-1"))
                .andExpect(jsonPath("$.calculatorId").value("calc-1"))
                .andExpect(jsonPath("$.status").value("RUNNING"));

        verify(ingestionService).startRun(any(), eq("tenant-a"));
    }

    @Test
    void startRun_duplicateReplay_returnsOkWithoutLocation() throws Exception {
        CalculatorRun existingRun = CalculatorRun.builder()
                .runId("run-1")
                .calculatorId("calc-1")
                .calculatorName("Calculator One")
                .status(RunStatus.RUNNING)
                .startTime(START_TIME)
                .slaBand(null)
                .build();

        when(ingestionService.startRun(any(), eq("tenant-a")))
                .thenReturn(new RunIngestionService.StartRunOutcome(existingRun, false));

        String payload = """
                {
                  "runId": "run-1",
                  "calculatorId": "calc-1",
                  "calculatorName": "Calculator One",
                  "frequency": "DAILY",
                  "reportingDate": "2026-02-22",
                  "startTime": "2026-02-22T06:00:00Z",
                  "slaTime": "PT2H30M"
                }
                """;

        mockMvc.perform(post("/api/v1/runs/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT_HEADER, "tenant-a")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.runId").value("run-1"));
    }

    @Test
    void startRun_unknownFrequency_returnsBadRequestNotSilentDaily() throws Exception {
        String payload = """
                {
                  "runId": "run-1",
                  "calculatorId": "calc-1",
                  "calculatorName": "Calculator One",
                  "frequency": "WEEKLY",
                  "reportingDate": "2026-02-22",
                  "startTime": "2026-02-22T06:00:00Z",
                  "slaTime": "PT2H30M"
                }
                """;

        mockMvc.perform(post("/api/v1/runs/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT_HEADER, "tenant-a")
                        .content(payload))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(ingestionService);
    }

    @Test
    void completeRun_conflictingStatus_returnsConflict() throws Exception {
        when(ingestionService.completeRun(eq("run-1"), any(), eq("tenant-a")))
                .thenThrow(new com.company.observability.exception.DomainConflictException(
                        "Run run-1 already completed with status SUCCESS; cannot change it to FAILED"));

        String payload = """
                {
                  "reportingDate": "2026-02-22",
                  "endTime": "2026-02-22T06:10:00Z",
                  "status": "FAILED"
                }
                """;

        mockMvc.perform(post("/api/v1/runs/run-1/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT_HEADER, "tenant-a")
                        .content(payload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Conflict"));
    }

    @Test
    void startRun_legacySlaInstant_returnsBadRequest() throws Exception {
        when(ingestionService.startRun(any(), eq("tenant-a")))
                .thenThrow(new DomainValidationException("Invalid slaTime. Use ISO-8601 duration (for example PT2H30M)."));

        String payload = """
                {
                  "runId": "run-1",
                  "calculatorId": "calc-1",
                  "calculatorName": "Calculator One",
                  "frequency": "DAILY",
                  "reportingDate": "2026-02-22",
                  "startTime": "2026-02-22T06:00:00Z",
                  "slaTime": "2026-02-22T05:15:00Z"
                }
                """;

        mockMvc.perform(post("/api/v1/runs/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT_HEADER, "tenant-a")
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));

        verify(ingestionService).startRun(any(), eq("tenant-a"));
    }

    @Test
    void startRun_withMissingRequiredField_returnsBadRequest() throws Exception {
        String invalidPayload = """
                {
                  "calculatorId": "calc-1",
                  "calculatorName": "Calculator One",
                  "frequency": "DAILY",
                  "reportingDate": "2026-02-22",
                  "startTime": "2026-02-22T06:00:00Z",
                  "slaTime": "2026-02-22T05:15:00Z"
                }
                """;

        mockMvc.perform(post("/api/v1/runs/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT_HEADER, "tenant-a")
                        .content(invalidPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"));

        verifyNoInteractions(ingestionService);
    }

    @Test
    void completeRun_returnsOkAndMappedResponse() throws Exception {
        CalculatorRun completedRun = CalculatorRun.builder()
                .runId("run-1")
                .calculatorId("calc-1")
                .calculatorName("Calculator One")
                .status(RunStatus.SUCCESS)
                .startTime(START_TIME)
                .endTime(END_TIME)
                .durationMs(600000L)
                .slaBand(null)
                .build();

        when(ingestionService.completeRun(eq("run-1"), any(), eq("tenant-a")))
                .thenReturn(completedRun);

        String payload = """
                {
                  "reportingDate": "2026-02-22",
                  "endTime": "2026-02-22T06:10:00Z",
                  "status": "SUCCESS"
                }
                """;

        mockMvc.perform(post("/api/v1/runs/run-1/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT_HEADER, "tenant-a")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-1"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.durationMs").value(600000));

        verify(ingestionService).completeRun(eq("run-1"), any(), eq("tenant-a"));
    }

    @Test
    void completeRun_missingReportingDate_returnsBadRequest() throws Exception {
        String payload = """
                {
                  "endTime": "2026-02-22T06:10:00Z",
                  "status": "SUCCESS"
                }
                """;

        mockMvc.perform(post("/api/v1/runs/run-1/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT_HEADER, "tenant-a")
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"));

        verifyNoInteractions(ingestionService);
    }

    @Test
    void completeRun_whenServiceThrows_returnsInternalServerError() throws Exception {
        String payload = """
                {
                  "reportingDate": "2026-02-22",
                  "endTime": "2026-02-22T06:10:00Z",
                  "status": "SUCCESS"
                }
                """;

        when(ingestionService.completeRun(eq("run-1"), any(), eq("tenant-a")))
                .thenThrow(new RuntimeException("boom"));

        mockMvc.perform(post("/api/v1/runs/run-1/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT_HEADER, "tenant-a")
                        .content(payload))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));

        verify(ingestionService).completeRun(eq("run-1"), any(), eq("tenant-a"));
    }

    @Test
    void startRun_missingTenantIdHeader_succeedsWithNullTenant() throws Exception {
        CalculatorRun savedRun = CalculatorRun.builder()
                .runId("run-1")
                .calculatorId("calc-1")
                .calculatorName("Calculator One")
                .status(RunStatus.RUNNING)
                .startTime(START_TIME)
                .slaBand(null)
                .build();

        when(ingestionService.startRun(any(), isNull()))
                .thenReturn(new RunIngestionService.StartRunOutcome(savedRun, true));

        String payload = """
                {
                  "runId": "run-1",
                  "calculatorId": "calc-1",
                  "calculatorName": "Calculator One",
                  "frequency": "DAILY",
                  "reportingDate": "2026-02-22",
                  "startTime": "2026-02-22T06:00:00Z",
                  "slaTime": "2026-02-22T05:15:00Z"
                }
                """;

        mockMvc.perform(post("/api/v1/runs/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.runId").value("run-1"));

        verify(ingestionService).startRun(any(), isNull());
    }

    @Test
    void startRun_malformedSlaTime_returnsBadRequest() throws Exception {
        when(ingestionService.startRun(any(), eq("tenant-a")))
                .thenThrow(new DomainValidationException("Invalid slaTime"));

        String payload = """
                {
                  "runId": "run-1",
                  "calculatorId": "calc-1",
                  "calculatorName": "Calculator One",
                  "frequency": "DAILY",
                  "reportingDate": "2026-02-22",
                  "startTime": "2026-02-22T06:00:00Z",
                  "slaTime": "22:00"
                }
                """;

        mockMvc.perform(post("/api/v1/runs/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT_HEADER, "tenant-a")
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Invalid slaTime"));
    }
}
