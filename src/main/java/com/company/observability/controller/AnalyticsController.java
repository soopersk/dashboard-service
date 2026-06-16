package com.company.observability.controller;

import com.company.observability.domain.enums.Frequency;
import com.company.observability.dto.response.RunPerformanceData;
import com.company.observability.service.AnalyticsService;
import com.company.observability.util.ObservabilityConstants;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/analytics")
@Tag(name = "Analytics", description = "Calculator run execution history")
@RequiredArgsConstructor
@Validated
@Slf4j
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final MeterRegistry meterRegistry;

    @GetMapping("/calculators/{name}/executions")
    @Operation(
            summary = "Run execution history (raw)",
            description = "Returns all physical runs over the lookback window as independent entries. " +
                    "The path variable is calculator_name (readable, unique-per-tenant); upstream UUIDs " +
                    "are not accepted on this endpoint. " +
                    "Split runs sharing a correlationId appear as separate rows — no grouping. " +
                    "Each entry includes durationMs (actual) and expectedDurationMs (configured) for comparison."
    )
    public ResponseEntity<RunPerformanceData> getRunExecutions(
            @PathVariable("name") String calculatorName,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @Parameter(description = "Lookback period in days (1-365)")
            @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days,
            @Parameter(description = "Frequency: DAILY or MONTHLY")
            @RequestParam(defaultValue = "DAILY") String frequency,
            @Parameter(description = "Run number bucket: 1 or 2. Omit to return all buckets.")
            @RequestParam(value = "run_number", required = false) String runNumber,
            @Parameter(description = "Anchor date for the lookback window (ISO-8601: yyyy-MM-dd). Defaults to today.")
            @RequestParam(value = "data_as_of_date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataAsOfDate,
            @Parameter(description = "Skip cache and fetch directly from DB (refreshes cache on return)")
            @RequestParam(value = "nocache", defaultValue = "false") boolean nocache) {

        Frequency freq = Frequency.fromStrict(frequency);
        LocalDate effectiveAsOfDate = (dataAsOfDate != null) ? dataAsOfDate : LocalDate.now();

        log.info("event=executions.request outcome=accepted calculatorName={} days={} frequency={} runNumber={} asOfDate={}",
                calculatorName, days, freq, runNumber, effectiveAsOfDate);

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            RunPerformanceData response = analyticsService
                    .getRunExecutionsByName(calculatorName, days, freq, runNumber, effectiveAsOfDate, nocache);

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).cachePrivate())
                    .body(response);
        } finally {
            sample.stop(meterRegistry.timer(ObservabilityConstants.API_ANALYTICS_DURATION,
                    "endpoint", "/executions"));
        }
    }
}
