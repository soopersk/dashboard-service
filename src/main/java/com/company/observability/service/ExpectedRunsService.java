package com.company.observability.service;

import com.company.observability.config.CalculatorProperties;
import com.company.observability.config.SlaProperties;
import com.company.observability.domain.CalculatorProfile;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.dto.response.CalculatorBatchRunsResponse.CalculatorEntry;
import com.company.observability.dto.response.CalculatorBatchRunsResponse.RunEntry;
import com.company.observability.util.TimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Pads a calculator's run list to its full declared set of expected runs.
 *
 * <p>For partial batches (Case B), missing dimension values receive per-dimension estimates
 * sourced from the dimension-scoped profile and grade against the calculator-level SLA deadline
 * shared with the real sibling runs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExpectedRunsService {

    private final CalculatorProperties props;
    private final CalculatorProfileService profileService;
    private final SlaProperties slaProps;
    private final Clock clock;

    /**
     * For each alias with a declared region/run-type set, pads its {@link CalculatorEntry} to the
     * full declared dimension set. Missing values get per-dimension estimates and are graded
     * against the calculator-level SLA deadline. Unconfigured aliases pass through unchanged.
     */
    public Map<String, CalculatorEntry> padToExpected(Map<String, CalculatorEntry> calculators,
                                                       LocalDate reportingDate, Frequency frequency,
                                                       String runNumber) {
        Map<String, List<String>> regions = props.getRegions();
        Map<String, List<String>> runTypes = props.getRunTypes();
        if (regions.isEmpty() && runTypes.isEmpty()) {
            return calculators;
        }

        Map<String, CalculatorEntry> result = new LinkedHashMap<>(calculators);
        for (Map.Entry<String, CalculatorEntry> entry : calculators.entrySet()) {
            String alias = entry.getKey();
            List<String> declaredValues;
            boolean isRegion;
            if (regions.containsKey(alias)) {
                declaredValues = regions.get(alias);
                isRegion = true;
            } else if (runTypes.containsKey(alias)) {
                declaredValues = runTypes.get(alias);
                isRegion = false;
            } else {
                continue;
            }
            result.put(alias, pad(entry.getValue(), alias, declaredValues, isRegion,
                    reportingDate, frequency, runNumber));
        }
        return result;
    }

    private CalculatorEntry pad(CalculatorEntry existing, String alias, List<String> declaredValues,
                                 boolean isRegion, LocalDate reportingDate, Frequency frequency,
                                 String runNumber) {
        // Real runs carry a runId; the upstream not-started synthetic (template) never does.
        // Distinguishing by null-dimension alone would swallow a real run that arrived untagged.
        List<RunEntry> realRuns = existing.runs().stream()
                .filter(r -> !isSynthetic(r))
                .toList();

        // Group (not first-wins) — a dimension can legitimately hold one entry per run number.
        Map<String, List<RunEntry>> byDimension = realRuns.stream()
                .filter(r -> dimensionValue(r, isRegion) != null)
                .collect(Collectors.groupingBy(r -> dimensionValue(r, isRegion)));

        // Real runs the declared set doesn't cover: undeclared dimension values (config drift,
        // case mismatch) and untagged (null-dimension) runs. The declared set is a minimum to
        // pad up to, never a whitelist — these must stay visible in the response.
        List<RunEntry> uncovered = realRuns.stream()
                .filter(r -> {
                    String dim = dimensionValue(r, isRegion);
                    return dim == null || !declaredValues.contains(dim);
                })
                .toList();

        boolean allDeclaredCovered = declaredValues.stream().allMatch(byDimension::containsKey);
        if (allDeclaredCovered) {
            List<RunEntry> ordered = new ArrayList<>();
            declaredValues.forEach(dim -> ordered.addAll(byDimension.get(dim)));
            ordered.addAll(uncovered);
            return new CalculatorEntry(existing.calculatorName(), existing.calculatorId(), ordered);
        }

        // Calculator-level deadline: sibling runs' frozen SLA first (Case B), then template's projected SLA (Case A).
        Instant calculatorDeadline = existing.runs().stream()
                .map(RunEntry::sla)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);

        // Template = the upstream not-started synthetic, carries projected SLA + estimates.
        RunEntry template = existing.runs().stream()
                .filter(ExpectedRunsService::isSynthetic)
                .findFirst()
                .orElse(null);

        if (calculatorDeadline == null && template != null) {
            calculatorDeadline = template.sla();
            // estEnd fallback: when no explicit SLA, estimatedEndTime acts as a soft deadline
            if (calculatorDeadline == null) {
                calculatorDeadline = template.estimatedEndTime();
            }
        }

        // Profiles are keyed by real calculator_name, not alias.
        String realName = props.getAliases().getOrDefault(alias, List.of(alias)).get(0);
        // DAILY: recover the real T+N offset from the calculator deadline (reportingDate→deadline
        // distance) so placeholder estimates anchor on the right execution date; else run_number.
        int offsetDays = SlaBaselineResolver.parseRunNumber(runNumber);
        if (frequency != Frequency.MONTHLY && calculatorDeadline != null) {
            ZoneId zone = ZoneId.of(slaProps.getSlaTimezone());
            int derivedN = TimeUtils.businessDaysBetween(
                    reportingDate, calculatorDeadline.atZone(zone).toLocalDate());
            if (derivedN >= 1) {
                offsetDays = derivedN;
            }
        }
        LocalDate executionDate = TimeUtils.nextBusinessDay(reportingDate, offsetDays);

        final Instant deadline = calculatorDeadline;
        List<RunEntry> padded = new ArrayList<>(declaredValues.size());
        for (String dimValue : declaredValues) {
            List<RunEntry> actual = byDimension.get(dimValue);
            if (actual != null) {
                padded.addAll(actual);
            } else {
                padded.add(placeholder(dimValue, isRegion, template, realName, frequency, runNumber,
                        executionDate, deadline));
            }
        }
        padded.addAll(uncovered);
        return new CalculatorEntry(existing.calculatorName(), existing.calculatorId(), padded);
    }

    /**
     * The upstream not-started synthetic projection (template). Real runs always carry a
     * runId; a real run that merely arrived without region/runType must not match.
     */
    private static boolean isSynthetic(RunEntry r) {
        return r.runId() == null && "NOT_STARTED".equals(r.status());
    }

    private RunEntry placeholder(String dimValue, boolean isRegion, RunEntry template,
                                  String realName, Frequency frequency, String runNumber,
                                  LocalDate executionDate, Instant calculatorDeadline) {
        // Estimates: dimension-scoped profile → template estimates → none.
        Instant estStart = null;
        Instant estEnd = null;
        Long expectedMs = null;

        CalculatorProfile dimProfile = profileService.getProfile(realName, frequency, runNumber, dimValue);
        if (dimProfile.hasSufficientSamples(slaProps.getMinSampleSize())) {
            estStart = TimeUtils.instantFromUtcMinuteOfDay(executionDate, dimProfile.avgStartMinUtc());
            estEnd = estStart.plusMillis(dimProfile.avgDurationMs());
            expectedMs = dimProfile.avgDurationMs();
        } else if (template != null) {
            estStart = template.estimatedStartTime();
            estEnd = template.estimatedEndTime();
            expectedMs = template.expectedDurationMs();
        }

        SlaEval eval = evaluateSlaStatus(calculatorDeadline, slaProps.bandGapMs(), clock.instant());
        log.debug("event=batch_runs.placeholder calculator={} dimension={} source={} estStart={} estEnd={} deadline={} slaStatus={}",
                realName, dimValue,
                dimProfile.hasSufficientSamples(slaProps.getMinSampleSize()) ? "dim_profile" : "template",
                estStart, estEnd, calculatorDeadline, eval.slaStatus());

        return RunEntry.builder()
                .status("NOT_STARTED")
                .slaStatus(eval.slaStatus())
                .slaBreached(eval.slaBreached() ? Boolean.TRUE : null)
                .estimatedStartTime(estStart)
                .estimatedEndTime(estEnd)
                .expectedDurationMs(expectedMs)
                .sla(calculatorDeadline)
                .region(isRegion ? dimValue : null)
                .runType(isRegion ? null : dimValue)
                .isRerun(false)
                .build();
    }

    private static String dimensionValue(RunEntry run, boolean isRegion) {
        return isRegion ? run.region() : run.runType();
    }

    // ── Shared SLA grading for synthetic not-started entries (used by CalculatorStateService) ──

    record SlaEval(String slaStatus, Boolean slaBreached) {}

    static SlaEval evaluateSlaStatus(Instant deadline, long bandGapMs) {
        return evaluateSlaStatus(deadline, bandGapMs, Instant.now());
    }

    static SlaEval evaluateSlaStatus(Instant deadline, long bandGapMs, Instant now) {
        if (deadline == null) return new SlaEval("ON_TIME", false);
        if (!now.isAfter(deadline)) return new SlaEval("ON_TIME", false);
        if (!now.isAfter(deadline.plusMillis(bandGapMs))) return new SlaEval("LATE", true);
        return new SlaEval("VERY_LATE", true);
    }
}
