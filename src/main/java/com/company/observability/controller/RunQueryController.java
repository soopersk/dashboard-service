package com.company.observability.controller;

import com.company.observability.domain.enums.Dimension;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.dto.response.CalculatorBatchRunsResponse;
import com.company.observability.service.ExpectedRunsService;
import com.company.observability.service.CalculatorNameResolver;
import com.company.observability.service.CalculatorStateService;
import com.company.observability.util.ObservabilityConstants;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/calculators")
@Tag(name = "Calculator Runs", description = "Query calculator dimensional run state")
@RequiredArgsConstructor
@Validated
@Slf4j
public class RunQueryController {

    private final CalculatorStateService calculatorStateService;
    private final CalculatorNameResolver nameResolver;
    private final ExpectedRunsService expectedRunsService;
    private final MeterRegistry meterRegistry;

    @GetMapping("/batch/runs")
    @Operation(
            summary = "Batch calculator runs by reporting date",
            description = "Returns all dimensional run instances per logical calculator for a specific reporting date. " +
                    "The `keys` query param is a pipe-separated list of calculator_name values (readable, " +
                    "unique-per-tenant); upstream UUIDs are not accepted on this endpoint. " +
                    "Regional calculators return one RunEntry per region; typed calculators return one per runType. " +
                    "Without `run_number`, multi-cycle calculators return one RunEntry per run number per dimension. " +
                    "With `run_number`, rows with a NULL run_number (un-numbered single-bucket runs) are included " +
                    "alongside the requested bucket; an unknown run_number returns an empty runs list. " +
                    "Empty runs list = no run found. isRerun=true = a same-run_number re-trigger was fired for that dimension."
    )
    public ResponseEntity<CalculatorBatchRunsResponse> getBatchRuns(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestParam("reporting_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reportingDate,
            @RequestParam(defaultValue = "DAILY") String frequency,
            @RequestParam(value = "run_number", required = false) String runNumber,
            @Parameter(description = "Pipe-separated calculator_name values, e.g. capitalcalc|portfoliocalc")
            @RequestParam @NotBlank String keys,
            @Parameter(description = "Skip cache and fetch directly from DB (refreshes cache on return)")
            @RequestParam(value = "nocache", defaultValue = "false") boolean nocache) {

        List<String> aliases = Arrays.stream(keys.split("\\|"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (aliases.isEmpty()) {
            throw new IllegalArgumentException("keys must contain at least one non-blank calculator name");
        }

        Frequency freq = Frequency.fromStrict(frequency);

        // Normalize blank → null once so getState and padToExpected see the same value.
        runNumber = (runNumber == null || runNumber.isBlank()) ? null : runNumber;

        // Expand aliases → {alias: [realName, ...]}; unknown names pass through unchanged
        Map<String, List<String>> aliasToRealNames = nameResolver.resolveAll(aliases);

        List<String> allRealNames = aliasToRealNames.values().stream()
                .flatMap(Collection::stream)
                .distinct()
                .toList();

        log.info("event=batch_runs.request outcome=accepted reportingDate={} frequency={} aliasCount={} realNameCount={} runNumber={}",
                reportingDate, freq, aliases.size(), allRealNames.size(), runNumber);

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Map<String, CalculatorBatchRunsResponse.CalculatorEntry> byRealName =
                    calculatorStateService.getState(reportingDate, freq, runNumber, allRealNames, nocache);

            // Re-group by alias: merge entries from all real names under each alias key
            Map<String, CalculatorBatchRunsResponse.CalculatorEntry> calculators =
                    aliasToRealNames.entrySet().stream().collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> mergeEntries(e.getKey(), e.getValue(), byRealName),
                            (a, b) -> a,
                            LinkedHashMap::new
                    ));

            // Pad each configured alias to its full declared set of expected runs
            calculators = expectedRunsService.padToExpected(calculators, reportingDate, freq, runNumber);

            int maxAgeSeconds = isLive(calculators) ? 5 : 30;
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(maxAgeSeconds, TimeUnit.SECONDS).cachePrivate())
                    .body(new CalculatorBatchRunsResponse(
                            reportingDate, freq.name(), runNumber, Instant.now(), calculators));
        } finally {
            sample.stop(meterRegistry.timer(ObservabilityConstants.API_ANALYTICS_DURATION,
                    "endpoint", "/calculators/batch/runs"));
        }
    }

    /**
     * A response is "live" when something may change imminently: any RUNNING or NOT_STARTED entry,
     * or any calculator with an empty runs list (a run may start any second). Live responses get a
     * short HTTP max-age so the client re-polls quickly; all-terminal responses keep the longer 30 s.
     */
    private boolean isLive(Map<String, CalculatorBatchRunsResponse.CalculatorEntry> calculators) {
        return calculators.values().stream().anyMatch(entry -> {
            List<CalculatorBatchRunsResponse.RunEntry> runs = entry.runs();
            if (runs == null || runs.isEmpty()) {
                return true;
            }
            return runs.stream().anyMatch(r ->
                    "RUNNING".equals(r.status()) || "NOT_STARTED".equals(r.status()));
        });
    }

    private CalculatorBatchRunsResponse.CalculatorEntry mergeEntries(
            String alias,
            List<String> realNames,
            Map<String, CalculatorBatchRunsResponse.CalculatorEntry> byRealName) {

        List<CalculatorBatchRunsResponse.CalculatorEntry> parts = realNames.stream()
                .map(byRealName::get)
                .filter(Objects::nonNull)
                .toList();

        if (parts.isEmpty()) {
            return new CalculatorBatchRunsResponse.CalculatorEntry(alias, null, List.of());
        }

        Set<String> ids = parts.stream()
                .map(CalculatorBatchRunsResponse.CalculatorEntry::calculatorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        String mergedId = ids.size() == 1 ? ids.iterator().next() : null;

        List<CalculatorBatchRunsResponse.RunEntry> allRuns = parts.stream()
                .flatMap(e -> e.runs().stream())
                .toList();

        // Collapse runs that represent the same logical dimension slot (e.g. AMER BATCH + AMER INTRA
        // on a region-dimensioned calculator). Key by the primary dimension + runNumber so distinct
        // cycles (run_number=1 vs run_number=2) and distinct dimension values stay separate.
        Dimension dim = nameResolver.dimensionOf(alias);
        Function<CalculatorBatchRunsResponse.RunEntry, String> keyFn = r -> switch (dim) {
            case REGION   -> Objects.toString(r.region(),   "") + "|" + Objects.toString(r.runNumber(), "");
            case RUN_TYPE -> Objects.toString(r.runType(),  "") + "|" + Objects.toString(r.runNumber(), "");
            case NONE     -> Objects.toString(r.region(),   "") + "|" + Objects.toString(r.runType(), "")
                                                                + "|" + Objects.toString(r.runNumber(), "");
        };

        List<CalculatorBatchRunsResponse.RunEntry> deduped = allRuns.stream()
                .collect(Collectors.groupingBy(keyFn, LinkedHashMap::new, Collectors.toList()))
                .values().stream()
                .map(bucket -> {
                    CalculatorBatchRunsResponse.RunEntry latest = bucket.stream()
                            .max(Comparator
                                    .comparing((CalculatorBatchRunsResponse.RunEntry r) ->
                                            !"NOT_STARTED".equals(r.status()))
                                    .thenComparing(r -> r.startTime(),
                                            Comparator.nullsFirst(Comparator.naturalOrder()))
                                    .thenComparing(r -> r.endTime(),
                                            Comparator.nullsFirst(Comparator.naturalOrder())))
                            .orElseThrow();
                    long realAttempts = bucket.stream()
                            .filter(r -> !"NOT_STARTED".equals(r.status())).count();
                    boolean rerun = realAttempts > 1 || bucket.stream().anyMatch(
                            CalculatorBatchRunsResponse.RunEntry::isRerun);
                    return latest.toBuilder().isRerun(rerun).build();
                })
                .toList();

        return new CalculatorBatchRunsResponse.CalculatorEntry(alias, mergedId, deduped);
    }
}
