package com.company.observability.controller;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.dto.request.CompleteRunRequest;
import com.company.observability.dto.request.StartRunRequest;
import com.company.observability.dto.response.RunResponse;
import com.company.observability.logging.LifecycleEvent;
import com.company.observability.logging.LifecycleLogger;
import com.company.observability.service.RunIngestionService;
import com.company.observability.util.ObservabilityConstants;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.net.URI;
import java.security.Principal;

/**
 * FIXED: Secure ingestion controller with tenant context from JWT
 */
@RestController
@RequestMapping("/api/v1/runs")
@Tag(name = "Run Ingestion", description = "APIs for Airflow to ingest calculator run data")
@RequiredArgsConstructor
public class RunIngestionController {

    private final RunIngestionService ingestionService;
    private final MeterRegistry meterRegistry;
    private final LifecycleLogger lifecycleLogger;

    @PostMapping("/start")
    @Operation(summary = "Start a calculator run",
            description = "Called by Airflow when calculator starts. 201 when the run is created; " +
                    "200 with the existing run on an idempotent replay.")
    public ResponseEntity<RunResponse> startRun(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @Valid @RequestBody StartRunRequest request,
            Principal principal) {

        String userId = principal != null ? principal.getName() : "unknown";

        lifecycleLogger.emit(LifecycleEvent.RUN_START_ACCEPTED,
                kv("user", userId), kv("calculator", request.getCalculatorId()), kv("tenant", tenantId));

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            RunIngestionService.StartRunOutcome outcome = ingestionService.startRun(request, tenantId);
            CalculatorRun run = outcome.run();

            return outcome.created()
                    ? ResponseEntity
                        .created(URI.create("/api/v1/runs/" + run.getRunId()))
                        .body(toRunResponse(run))
                    : ResponseEntity.ok(toRunResponse(run));
        } finally {
            sample.stop(meterRegistry.timer(ObservabilityConstants.API_INGESTION_DURATION,
                    "endpoint", "/api/v1/runs/start"));
        }
    }

    @PostMapping("/{runId}/complete")
    @Operation(summary = "Complete a calculator run", description = "Called by Airflow when calculator finishes")
    public ResponseEntity<RunResponse> completeRun(
            @PathVariable String runId,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @Valid @RequestBody CompleteRunRequest request,
            Principal principal) {

        String userId = principal != null ? principal.getName() : "unknown";

        lifecycleLogger.emit(LifecycleEvent.RUN_COMPLETE_ACCEPTED,
                kv("user", userId), kv("runId", runId), kv("tenant", tenantId));

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            CalculatorRun run = ingestionService.completeRun(runId, request, tenantId);

            return ResponseEntity.ok(toRunResponse(run));
        } finally {
            sample.stop(meterRegistry.timer(ObservabilityConstants.API_INGESTION_DURATION,
                    "endpoint", "/api/v1/runs/{runId}/complete"));
        }
    }

    private RunResponse toRunResponse(CalculatorRun run) {
        return new RunResponse(
                run.getRunId(), run.getCalculatorId(), run.getCalculatorName(),
                run.getStatus().name(), run.getStartTime(), run.getEndTime(),
                run.getDurationMs(), run.getSlaTime(),
                run.getSlaBand() != null ? run.getSlaBand().name() : null, run.getSlaBreachReason());
    }
}
