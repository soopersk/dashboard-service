package com.company.observability.service;

import com.company.observability.cache.CalculatorStateCacheService;
import com.company.observability.config.SlaProperties;
import com.company.observability.domain.CalculatorProfile;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.domain.enums.SlaBand;
import com.company.observability.dto.response.CalculatorBatchRunsResponse.CalculatorEntry;
import com.company.observability.dto.response.CalculatorBatchRunsResponse.RunEntry;
import com.company.observability.repository.CalculatorRunRepository;
import com.company.observability.util.TimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class CalculatorStateService {

    private final CalculatorRunRepository runRepository;
    private final SlaProperties slaProperties;
    private final CalculatorStateCacheService stateCache;
    private final CalculatorProfileService profileService;
    private final Clock clock;

    // Lower index = worse status (worst-wins ordering mirrors LogicalRunGrouper)
    private static final List<RunStatus> STATUS_PRECEDENCE = List.of(
            RunStatus.RUNNING, RunStatus.FAILED, RunStatus.TIMEOUT, RunStatus.CANCELLED, RunStatus.SUCCESS);

    public Map<String, CalculatorEntry> getState(
            LocalDate reportingDate,
            Frequency frequency,
            String runNumber,
            List<String> calculatorNames,
            boolean nocache) {

        // Normalize blank → null so empty ?run_number= means "all runs" (not filter on empty string)
        String rn = (runNumber == null || runNumber.isBlank()) ? null : runNumber;
        String freqName = frequency.name();

        // 1. Cache read — partial hits are fine; skipped when nocache=true (forces full DB fetch)
        Map<String, CalculatorEntry> cached = nocache
                ? new HashMap<>()
                : stateCache.getEntries(reportingDate, freqName, rn, calculatorNames);
        if (nocache) {
            log.debug("event=state.cache.bypass calculators={} reportingDate={} frequency={}",
                    calculatorNames, reportingDate, frequency);
        }

        // 2. Determine misses
        List<String> missNames = calculatorNames.stream()
                .filter(name -> !cached.containsKey(name))
                .toList();

        log.debug("event=batch_runs.state cacheHits={} cacheMisses={} reportingDate={} frequency={}",
                cached.size(), missNames.size(), reportingDate, freqName);

        // 3. DB call only for misses
        if (!missNames.isEmpty()) {
            log.debug("event=batch_runs.db_fetch outcome=start misses={} reportingDate={} frequency={}",
                    missNames, reportingDate, freqName);

            Map<String, List<CalculatorRun>> runsByName = runRepository
                    .findAllRunsByDateAndDimension(reportingDate, frequency, rn, missNames)
                    .stream()
                    .collect(Collectors.groupingBy(CalculatorRun::getCalculatorName));

            log.debug("event=batch_runs.db_fetch outcome=complete fetchedCalculators={}", runsByName.size());

            Map<String, CalculatorEntry> freshEntries = missNames.stream().collect(Collectors.toMap(
                    name -> name,
                    name -> buildEntry(name, runsByName.getOrDefault(name, List.of()), reportingDate, frequency, rn),
                    (a, b) -> a,
                    java.util.LinkedHashMap::new
            ));

            // 4. Cache fresh entries (including not-started entries so absent names don't re-hit DB)
            stateCache.putEntries(reportingDate, freqName, rn, freshEntries);
            cached.putAll(freshEntries);
        }

        // Return in the original requested order
        return calculatorNames.stream().collect(Collectors.toMap(
                name -> name,
                cached::get
        ));
    }

    private CalculatorEntry buildEntry(String calculatorName, List<CalculatorRun> runs,
                                       LocalDate reportingDate, Frequency frequency, String runNumber) {
        if (runs.isEmpty()) {
            return buildNotStartedEntry(calculatorName, reportingDate, frequency, runNumber);
        }

        // Phase 1: collapse parallel splits (shared correlationId) into one RunEntry each
        Map<String, List<CalculatorRun>> splitGroups = runs.stream()
                .filter(r -> r.getCorrelationId() != null)
                .collect(Collectors.groupingBy(CalculatorRun::getCorrelationId));

        List<RunEntry> splitEntries = splitGroups.values().stream()
                .map(group -> collapseSplitGroup(group, calculatorName))
                .toList();

        // Phase 2: deduplicate sequential reruns (null correlationId) by (region, runType,
        // runNumber) — latest wins. run_number is part of the key: scheduled RUN1/RUN2 cycles
        // are distinct runs with distinct SLA deadlines, not reruns of each other; only a
        // same-run_number duplicate is a true re-trigger (isRerun).
        List<RunEntry> standaloneEntries = runs.stream()
                .filter(r -> r.getCorrelationId() == null)
                .collect(Collectors.groupingBy(r ->
                        Objects.toString(r.getRegion(), "") + ":" + Objects.toString(r.getRunType(), "")
                                + ":" + Objects.toString(r.getRunNumber(), "")))
                .values().stream()
                .map(group -> {
                    CalculatorRun latest = group.stream()
                            .max(Comparator.comparing(CalculatorRun::getCreatedAt))
                            .orElseThrow();
                    latest.setRerun(group.size() > 1);
                    return toRunEntry(latest, calculatorName);
                })
                .toList();

        String calculatorId = runs.stream().findFirst().map(CalculatorRun::getCalculatorId).orElse(null);
        List<RunEntry> allEntries = Stream.concat(splitEntries.stream(), standaloneEntries.stream()).toList();
        return new CalculatorEntry(calculatorName, calculatorId, allEntries);
    }

    /**
     * Build a synthetic "not started" entry for a calculator that has no run on the queried date.
     * Estimates and deadline are resolved independently so the projected SLA is always carried
     * regardless of whether the profile path or the latest-run path supplies the estimates.
     *
     * <p>Returns an empty entry only for brand-new calculators with no history at all.
     */
    private CalculatorEntry buildNotStartedEntry(String name, LocalDate date, Frequency freq,
                                                  String runNumber) {
        // ── Latest run (run_number-scoped: a RUN1 projection must not borrow RUN2's frozen deadline) ──
        CalculatorRun latest = runRepository.findLatestRunEstimatesByName(
                name, freq, runNumber, slaProperties.lookbackDays(freq)).orElse(null);

        // A run_number filter with zero scoped history is not an expected run — projecting one
        // would invent a bucket (e.g. run_number=99 → a confident T+99 deadline). Empty is honest.
        if (runNumber != null && latest == null) {
            log.debug("event=batch_runs.not_started source=none reason=unknown_run_number calculator={} runNumber={}",
                    name, runNumber);
            return new CalculatorEntry(name, null, List.of());
        }

        // Runs execute T+n business days after the reporting date. For DAILY, recover the real offset
        // from the latest run's reportingDate→slaTime distance; else fall back to run_number.
        int offsetDays = deriveOffsetDays(latest, freq, runNumber);
        LocalDate executionDate = TimeUtils.nextBusinessDay(date, offsetDays);
        log.debug("event=batch_runs.not_started.offset calculator={} freq={} reportingDate={} executionDate={} offsetDays={}",
                name, freq, date, executionDate, offsetDays);

        // ── Estimates (display) ──────────────────────────────────────────────
        // 1a. Run_number-scoped profile (Redis-cached 26h — very cheap)
        CalculatorProfile profile = profileService.getProfile(name, freq, runNumber);
        Instant estStart = null;
        Instant estEnd = null;
        Long expectedMs = null;
        String calculatorId = null;

        if (profile.hasSufficientSamples(slaProperties.getMinSampleSize())) {
            estStart = TimeUtils.instantFromUtcMinuteOfDay(executionDate, profile.avgStartMinUtc());
            estEnd = estStart.plusMillis(profile.avgDurationMs());
            expectedMs = profile.avgDurationMs();
            log.debug("event=batch_runs.not_started source=profile calculator={} date={} executionDate={} " +
                      "estStart={} estEnd={} expectedMs={} profileSamples={}",
                    name, date, executionDate, estStart, estEnd, expectedMs, profile.totalRuns());
        }

        // The profile path supplies estimates but no id; carry the latest run's id when present
        // so Case A entries are not needlessly missing calculatorId.
        if (calculatorId == null && latest != null) {
            calculatorId = latest.getCalculatorId();
        }

        // 1b. Fallback estimates from most recent run's stored values, projected onto execution date
        if (estStart == null && latest != null
                && latest.getEstimatedStartTime() != null && latest.getExpectedDurationMs() != null) {
            int minuteOfDay = (int) Duration.between(
                    latest.getEstimatedStartTime().truncatedTo(ChronoUnit.DAYS),
                    latest.getEstimatedStartTime()).toMinutes();
            estStart = TimeUtils.instantFromUtcMinuteOfDay(executionDate, minuteOfDay);
            estEnd = estStart.plusMillis(latest.getExpectedDurationMs());
            expectedMs = latest.getExpectedDurationMs();
            calculatorId = latest.getCalculatorId();
            log.debug("event=batch_runs.not_started source=latest_run calculator={} date={} executionDate={} " +
                      "estStart={} estEnd={} expectedMs={}",
                    name, date, executionDate, estStart, estEnd, expectedMs);
        }

        // ── Deadline (calculator-level, independent of estimate source) ──────
        Instant projectedSla = null;
        if (latest != null && latest.getSlaTime() != null) {
            projectedSla = projectSlaTime(latest, date, freq, executionDate, estStart);
        }
        log.debug("event=batch_runs.not_started.sla_projection calculator={} date={} historicalSla={} projectedSla={}",
                name, date, latest != null ? latest.getSlaTime() : null, projectedSla);

        if (estStart == null && projectedSla == null) {
            // Brand-new calculator with no history — return empty
            log.debug("event=batch_runs.not_started source=none calculator={} date={}", name, date);
            return new CalculatorEntry(name, null, List.of());
        }

        return entryWithSyntheticRun(name, calculatorId, estStart, estEnd, expectedMs, projectedSla);
    }

    /**
     * Recovers the T+N business-day offset for a not-started projection. For DAILY, derives it
     * from the latest run's {@code reportingDate → slaTime} distance (recovers the real offset for
     * both {@code T+N@HH:mm} and bare-clock runs without persisting the spec); falls back to
     * {@code parseRunNumber(runNumber)} when no usable latest run exists. MONTHLY deadlines are
     * start-anchored, not offset-based, so MONTHLY always uses the run_number fallback for estimates.
     */
    private int deriveOffsetDays(CalculatorRun latest, Frequency freq, String runNumber) {
        int fallback = SlaBaselineResolver.parseRunNumber(runNumber);
        if (freq == Frequency.MONTHLY || latest == null
                || latest.getSlaTime() == null || latest.getReportingDate() == null) {
            return fallback;
        }
        ZoneId zone = ZoneId.of(slaProperties.getSlaTimezone());
        int derivedN = TimeUtils.businessDaysBetween(
                latest.getReportingDate(), latest.getSlaTime().atZone(zone).toLocalDate());
        return derivedN >= 1 ? derivedN : fallback;
    }

    /**
     * Projects a historical frozen SLA deadline onto the queried reporting date.
     *
     * <p><b>DAILY</b>: re-anchors the historical time-of-day onto {@code executionDate} (already
     * advanced by the derived T+N offset), extracting the time-of-day in the configured timezone
     * (correct for DST).
     *
     * <p><b>MONTHLY</b>: the real deadline is start-anchored, so pre-start it can only be estimated
     * from the estimated start date (profile/latest-run estimate) with the overnight roll. Returns
     * {@code null} when no estimated start is available.
     */
    private Instant projectSlaTime(CalculatorRun latest, LocalDate targetDate, Frequency freq,
                                   LocalDate executionDate, Instant estStart) {
        Instant historicalSlaTime = latest.getSlaTime();
        ZoneId zone = ZoneId.of(slaProperties.getSlaTimezone());
        if (freq == Frequency.MONTHLY) {
            if (estStart == null) {
                return null;
            }
            // Read the cutoff back in the same zone the deadline was derived in.
            LocalTime cutoff = historicalSlaTime.atZone(zone).toLocalTime();
            return TimeUtils.clockTimeDeadline(estStart, cutoff, zone);
        }
        LocalTime clockTime = historicalSlaTime.atZone(zone).toLocalTime();
        return ZonedDateTime.of(executionDate, clockTime, zone).toInstant();
    }

    private CalculatorEntry entryWithSyntheticRun(String name, String calculatorId,
                                                   Instant estStart, Instant estEnd,
                                                   Long expectedMs, Instant projectedSla) {
        ExpectedRunsService.SlaEval sla =
                ExpectedRunsService.evaluateSlaStatus(projectedSla, slaProperties.bandGapMs(), clock.instant());
        log.debug("event=batch_runs.not_started.sla_eval calculator={} projectedSla={} slaStatus={} slaBreached={}",
                name, projectedSla, sla.slaStatus(), sla.slaBreached());
        RunEntry synthetic = RunEntry.builder()
                .status("NOT_STARTED")
                .slaStatus(sla.slaStatus())
                .slaBreached(sla.slaBreached() ? Boolean.TRUE : null)
                .estimatedStartTime(estStart)
                .estimatedEndTime(estEnd)
                .expectedDurationMs(expectedMs)
                .sla(projectedSla)
                .isRerun(false)
                .build();
        return new CalculatorEntry(name, calculatorId, List.of(synthetic));
    }

    private RunEntry collapseSplitGroup(List<CalculatorRun> splits, String entryName) {
        CalculatorRun first = splits.stream()
                .min(Comparator.comparing(CalculatorRun::getCreatedAt))
                .orElseThrow();

        RunStatus worstStatus = splits.stream()
                .map(CalculatorRun::getStatus)
                .min(Comparator.comparingInt(STATUS_PRECEDENCE::indexOf))
                .orElse(RunStatus.SUCCESS);

        Instant startTime = splits.stream().map(CalculatorRun::getStartTime)
                .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        Instant endTime = worstStatus == RunStatus.RUNNING ? null :
                splits.stream().map(CalculatorRun::getEndTime)
                        .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        Long durationMs = startTime != null && endTime != null
                ? endTime.toEpochMilli() - startTime.toEpochMilli() : null;

        // Pick worst band across splits (VERY_LATE > LATE > ON_TIME > null)
        SlaBand worstBand = splits.stream()
                .map(CalculatorRun::getSlaBand)
                .filter(Objects::nonNull)
                .max(Comparator.comparingInt(b -> b == SlaBand.VERY_LATE ? 2 : b == SlaBand.LATE ? 1 : 0))
                .orElse(null);
        String breachReason = splits.stream().map(CalculatorRun::getSlaBreachReason)
                .filter(s -> s != null && !s.isBlank()).collect(Collectors.joining("; "));

        CalculatorRun rep = new CalculatorRun();
        rep.setRunId(first.getRunId());
        rep.setCalculatorId(first.getCalculatorId());
        rep.setCalculatorName(first.getCalculatorName());
        rep.setRegion(first.getRegion());
        rep.setRunType(first.getRunType());
        rep.setRunNumber(first.getRunNumber());
        rep.setStatus(worstStatus);
        rep.setStartTime(startTime);
        rep.setEndTime(endTime);
        rep.setDurationMs(durationMs);
        rep.setSlaBand(worstBand);
        rep.setSlaBreachReason(breachReason.isBlank() ? null : breachReason);
        rep.setSlaTime(first.getSlaTime());
        rep.setExpectedDurationMs(first.getExpectedDurationMs());
        rep.setEstimatedStartTime(first.getEstimatedStartTime());
        rep.setEstimatedEndTime(first.getEstimatedEndTime());
        rep.setRerun(false);  // parallel splits are not sequential reruns

        return toRunEntry(rep, entryName);
    }

    private RunEntry toRunEntry(CalculatorRun run, String entryName) {
        // Populate calculatorName on RunEntry only when the run originates from a
        // differently-named real calculator (multi-alias merge). Null otherwise → omitted in JSON.
        String runCalcName = run.getCalculatorName() != null
                && !run.getCalculatorName().equals(entryName)
                ? run.getCalculatorName() : null;

        return RunEntry.builder()
                .calculatorName(runCalcName)
                .runId(run.getRunId())
                .region(run.getRegion())
                .runType(run.getRunType())
                .runNumber(run.getRunNumber())
                .status(run.getStatus().name())
                .slaStatus(run.getSlaBand() != null ? run.getSlaBand().name() : "ON_TIME")
                .slaBreached(run.isSlaBreached() ? Boolean.TRUE : null)
                .startTime(run.getStartTime())
                .endTime(run.getEndTime())
                .estimatedStartTime(run.getEstimatedStartTime())
                .estimatedEndTime(run.getEstimatedEndTime())
                .sla(run.getSlaTime())
                .durationMs(run.getDurationMs())
                .expectedDurationMs(run.getExpectedDurationMs())
                .slaBreachReason(run.getSlaBreachReason())
                .isRerun(run.isRerun())
                .build();
    }
}
