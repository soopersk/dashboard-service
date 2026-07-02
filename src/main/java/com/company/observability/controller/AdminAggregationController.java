package com.company.observability.controller;

import com.company.observability.dto.response.RecomputeResponse;
import com.company.observability.scheduled.DailyAggregationJob;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

@RestController
@RequestMapping("/api/v1/admin/aggregation")
@Tag(name = "Admin Aggregation", description = "Admin-only on-demand recompute of calculator_sli_daily")
@RequiredArgsConstructor
public class AdminAggregationController {

    private static final long MAX_SPAN_DAYS = 800;

    private final DailyAggregationJob dailyAggregationJob;

    @PostMapping("/recompute")
    @Operation(summary = "Recompute calculator_sli_daily over an explicit date range",
            description = "Recomputes both DAILY and MONTHLY aggregates for the given range and warms profiles. " +
                    "Used for dev iteration with backdated data and prod go-live backfills.")
    public ResponseEntity<RecomputeResponse> recompute(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        LocalDate resolvedTo = to != null ? to : LocalDate.now(ZoneOffset.UTC);

        if (from.isAfter(resolvedTo)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        if (ChronoUnit.DAYS.between(from, resolvedTo) > MAX_SPAN_DAYS) {
            throw new IllegalArgumentException("Range exceeds maximum span of " + MAX_SPAN_DAYS + " days");
        }

        DailyAggregationJob.RecomputeOutcome outcome = dailyAggregationJob.recomputeRange(from, resolvedTo);

        return ResponseEntity.ok(new RecomputeResponse(
                from, resolvedTo, outcome.rowsRecomputed(), outcome.profilesWarmed()));
    }
}
