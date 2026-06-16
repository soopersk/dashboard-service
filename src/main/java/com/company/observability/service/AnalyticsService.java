package com.company.observability.service;

import com.company.observability.cache.AnalyticsCacheService;
import com.company.observability.config.SlaProperties;
import com.company.observability.domain.CalculatorProfile;
import com.company.observability.domain.RunWithSlaStatus;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.domain.enums.SlaBand;
import com.company.observability.dto.response.*;
import com.company.observability.repository.CalculatorRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsService {

    private final CalculatorRunRepository calculatorRunRepository;
    private final AnalyticsCacheService cacheService;
    private final CalculatorProfileService calculatorProfileService;
    private final SlaProperties slaProperties;
    private final CalculatorNameResolver nameResolver;

    private static final String CACHE_EXECUTIONS = "executions";

    // ================================================================
    // Run Executions (raw, no grouping)
    // ================================================================

    /**
     * Name-keyed variant. Queries by calculator_name; the envelope's calculatorId field carries
     * the same name, so no upstream UUID appears in the response.
     */
    public RunPerformanceData getRunExecutionsByName(
            String calculatorName, int days, Frequency frequency, String runNumber,
            LocalDate asOfDate, boolean nocache) {

        // Normalize blank → null so empty ?run_number= means "all runs" (not filter on empty string)
        String rn = (runNumber == null || runNumber.isBlank()) ? null : runNumber;

        if (nocache) {
            log.debug("event=analytics.cache.bypass calculatorName={} frequency={} days={}",
                    calculatorName, frequency, days);
        }
        RunPerformanceData cached = nocache ? null
                : cacheService.getFromCache(
                        CACHE_EXECUTIONS, calculatorName, frequency.name(), days, rn,
                        asOfDate, RunPerformanceData.class);
        if (cached != null) {
            log.debug("event=executions.cache outcome=hit calculatorName={} frequency={} days={} runNumber={} asOfDate={}",
                    calculatorName, frequency, days, rn, asOfDate);
            return cached;
        }

        // Expand alias to real DB calculator_name values; unknown names pass through unchanged
        List<String> realNames = nameResolver.resolve(calculatorName);

        log.debug("event=executions.db_fetch outcome=start calculatorName={} realNames={} frequency={} days={} runNumber={} asOfDate={}",
                calculatorName, realNames, frequency, days, rn, asOfDate);

        List<RunWithSlaStatus> rawRuns = realNames.stream()
                .flatMap(name -> calculatorRunRepository.findRunsByName(name, frequency, days, rn, asOfDate).stream())
                .sorted(Comparator.comparing(RunWithSlaStatus::reportingDate)
                        .thenComparing(RunWithSlaStatus::startTime, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();

        RunPerformanceData response = buildExecutionsResponse(calculatorName, rawRuns, days, frequency);
        cacheService.putInCache(CACHE_EXECUTIONS, calculatorName, frequency.name(), days, rn, asOfDate, response);
        return response;
    }

    private RunPerformanceData buildExecutionsResponse(
            String calculatorKey,
            List<RunWithSlaStatus> rawRuns,
            int days,
            Frequency frequency) {

        if (rawRuns.isEmpty()) {
            return new RunPerformanceData(
                    calculatorKey, null, frequency.name(), days, 0L,
                    0, 0, 0, 0, 0,
                    Collections.emptyList(), null, null);
        }

        List<RunPerformanceData.RunDataPoint> dataPoints = rawRuns.stream()
                .map(run -> {
                    String slaStatus = classifySlaStatusForRun(run);
                    boolean isRunning = run.status() == RunStatus.RUNNING;
                    return new RunPerformanceData.RunDataPoint(
                            run.runId(),
                            run.reportingDate(),
                            run.startTime(),
                            isRunning ? null : run.endTime(),
                            isRunning ? null : run.durationMs(),
                            run.status().name(),
                            run.slaBand() != null ? run.slaBand().name() : null,
                            slaStatus,
                            null,
                            run.estimatedStartTime(),
                            run.slaTime(),
                            run.runNumber(),
                            run.expectedDurationMs()
                    );
                })
                .toList();

        return buildRunPerformanceDataEnvelope(calculatorKey, rawRuns, dataPoints, days, frequency);
    }

    private RunPerformanceData buildRunPerformanceDataEnvelope(
            String calculatorId,
            List<RunWithSlaStatus> rawRuns,
            List<RunPerformanceData.RunDataPoint> dataPoints,
            int days,
            Frequency frequency) {

        RunWithSlaStatus latestRaw = rawRuns.get(rawRuns.size() - 1);

        long totalDuration = 0;
        int completedCount = 0;
        int terminalRuns = 0;
        int runningRuns = 0;
        int slaMetCount = 0, lateCount = 0, veryLateCount = 0;

        for (RunPerformanceData.RunDataPoint dp : dataPoints) {
            boolean isRunning = RunStatus.RUNNING.name().equals(dp.status());
            if (isRunning) {
                runningRuns++;
            } else {
                terminalRuns++;
            }
            if (!isRunning && dp.durationMs() != null && dp.durationMs() > 0) {
                totalDuration += dp.durationMs();
                completedCount++;
            }
            if (!isRunning) {
                if ("ON_TIME".equals(dp.slaStatus())) slaMetCount++;
                else if ("LATE".equals(dp.slaStatus())) lateCount++;
                else if ("VERY_LATE".equals(dp.slaStatus())) veryLateCount++;
            }
        }

        long meanDuration = completedCount > 0 ? totalDuration / completedCount : 0;

        ReferenceLines refLines = resolveReferenceLines(latestRaw, frequency);

        return new RunPerformanceData(
                calculatorId,
                latestRaw.calculatorName(),
                frequency.name(),
                days,
                meanDuration,
                terminalRuns,
                runningRuns,
                slaMetCount,
                lateCount,
                veryLateCount,
                dataPoints,
                refLines.estimatedStartTime(),
                refLines.slaTime());
    }

    /**
     * Chart reference lines for the executions view. Sourced from the cached profile (stable
     * "typical" start + buffered deadline) when it has enough samples; otherwise falls back to
     * the most recent run's stored values.
     */
    private ReferenceLines resolveReferenceLines(
            RunWithSlaStatus latestRaw, Frequency frequency) {

        CalculatorProfile profile = calculatorProfileService.getProfile(
                latestRaw.calculatorName(), frequency);

        if (profile.hasSufficientSamples(slaProperties.getMinSampleSize())) {
            java.time.Instant estStart = com.company.observability.util.TimeUtils
                    .instantFromUtcMinuteOfDay(latestRaw.reportingDate(), profile.avgStartMinUtc());

            // The frozen slaTime is the authoritative deadline for every spec kind — use it directly.
            if (latestRaw.slaTime() != null) {
                return new ReferenceLines(estStart, latestRaw.slaTime());
            }

            // No frozen deadline (e.g. ungraded run): synthesize from the buffered profile average.
            long bufferedMs = Math.round(profile.avgDurationMs() * (1 + slaProperties.getDurationThresholdPercent() / 100.0))
                    + slaProperties.lateBandMs();
            return new ReferenceLines(estStart, estStart.plusMillis(bufferedMs));
        }
        return new ReferenceLines(latestRaw.estimatedStartTime(), latestRaw.slaTime());
    }

    private record ReferenceLines(java.time.Instant estimatedStartTime, java.time.Instant slaTime) {}

    private String classifySlaStatusForRun(RunWithSlaStatus run) {
        if (run.status() == RunStatus.RUNNING || run.slaBand() == null) return SlaBand.ON_TIME.name();
        return run.slaBand().name(); // ON_TIME / LATE / VERY_LATE
    }
}
