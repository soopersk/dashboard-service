package com.company.observability.service;

import com.company.observability.config.SlaProperties;
import com.company.observability.domain.CalculatorProfile;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.dto.request.StartRunRequest;
import com.company.observability.exception.DomainValidationException;
import com.company.observability.util.TimeUtils;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a run's SLA deadline at start time and freezes it into {@code slaTime} so the
 * rest of the SLA machinery (on-write evaluation, Redis live detection) operates on a
 * single absolute instant.
 *
 * <p>{@code slaTime} on the request is <b>self-describing</b> — there is no global mode
 * switch. The accepted forms are:
 * <ul>
 *   <li>{@code "T+N@HH:mm"} (N ≥ 1) — a day offset plus a UTC cutoff. DAILY only: deadline is
 *       {@code nextBusinessDay(reportingDate, N)} at the cutoff in {@code slaTimezone}. No band is
 *       added (bands are grading-only). MONTHLY rejects this form.</li>
 *   <li>{@code "HH:mm"} (bare clock) — DAILY: offset falls back to {@code parseRunNumber(runNumber)}.
 *       MONTHLY: anchored to the {@code startTime} date with overnight roll
 *       ({@link TimeUtils#clockTimeDeadline}).</li>
 *   <li>{@code "PT2H30M"} (ISO-8601 duration) — deadline = {@code startTime + buffered + lateBand};
 *       {@code baselineDurationMs} = the duration.</li>
 *   <li>blank/null — duration fallback chain (always active): {@code expectedDurationMs} →
 *       profile average → ungraded.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlaBaselineResolver {

    private static final Pattern TPLUS_PATTERN = Pattern.compile("^T\\+(\\d+)@(\\d{2}:\\d{2})$");

    private final SlaProperties slaProperties;
    private final MeterRegistry meterRegistry;

    public record SlaResolution(
            Long baselineDurationMs,
            Instant deadline
    ) {}

    /** Exactly one shape is set: (offsetDays+cutoff) | (cutoff only) | (duration). */
    private record ParsedSpec(Integer offsetDays, LocalTime cutoff, Duration duration) {}

    /**
     * @param profile the calculator's cached rolling profile (may be a zero-sample sentinel)
     * @return baseline + deadline resolution details for persistence and estimated-end fallback.
     */
    public SlaResolution resolve(StartRunRequest request, Frequency frequency, CalculatorProfile profile) {
        Instant startTime = request.getStartTime();
        if (startTime == null) {
            return new SlaResolution(null, null);
        }

        String raw = request.getSlaTime();

        // Blank/null → duration fallback chain (always on; no enabled gate).
        if (raw == null || raw.isBlank()) {
            return resolveFromFallback(request, frequency, profile);
        }

        ParsedSpec spec = parseSpec(raw.trim());

        if (spec.duration() != null) {
            long ms = spec.duration().toMillis();
            long bufferedMs = Math.round(ms * (1 + slaProperties.getDurationThresholdPercent() / 100.0));
            Instant deadline = startTime.plusMillis(bufferedMs + slaProperties.lateBandMs());
            log.debug("event=sla.baseline.resolve outcome=success form=duration calculatorId={} frequency={} baselineMs={} deadline={}",
                    request.getCalculatorId(), frequency, ms, deadline);
            return new SlaResolution(ms, deadline);
        }

        if (spec.offsetDays() != null) {
            // T+N@HH:mm — DAILY only
            if (frequency == Frequency.MONTHLY) {
                throw new DomainValidationException("MONTHLY clock SLA must be a bare clock time HH:mm");
            }
            if (request.getReportingDate() == null) {
                return new SlaResolution(null, null);
            }
            ZoneId zone = ZoneId.of(slaProperties.getSlaTimezone());
            LocalDate executionDate = TimeUtils.nextBusinessDay(request.getReportingDate(), spec.offsetDays());
            Instant deadline = ZonedDateTime.of(executionDate, spec.cutoff(), zone).toInstant();
            log.debug("event=sla.baseline.resolve outcome=success form=tplus calculatorId={} frequency={} offsetDays={} cutoff={} executionDate={} deadline={}",
                    request.getCalculatorId(), frequency, spec.offsetDays(), spec.cutoff(), executionDate, deadline);
            return new SlaResolution(null, deadline);
        }

        // Bare clock (cutoff only)
        if (frequency == Frequency.MONTHLY) {
            // Same configured zone as DAILY clock specs — no DAILY/MONTHLY asymmetry.
            Instant deadline = TimeUtils.clockTimeDeadline(
                    startTime, spec.cutoff(), ZoneId.of(slaProperties.getSlaTimezone()));
            log.debug("event=sla.baseline.resolve outcome=success form=clock-monthly calculatorId={} frequency={} cutoff={} deadline={}",
                    request.getCalculatorId(), frequency, spec.cutoff(), deadline);
            return new SlaResolution(null, deadline);
        }

        // DAILY bare clock — offset from run_number
        if (request.getReportingDate() == null) {
            return new SlaResolution(null, null);
        }
        int n = parseRunNumber(request.getRunNumber());
        ZoneId zone = ZoneId.of(slaProperties.getSlaTimezone());
        LocalDate executionDate = TimeUtils.nextBusinessDay(request.getReportingDate(), n);
        Instant deadline = ZonedDateTime.of(executionDate, spec.cutoff(), zone).toInstant();
        log.debug("event=sla.baseline.resolve outcome=success form=clock-daily calculatorId={} frequency={} runNumber={} executionDate={} deadline={}",
                request.getCalculatorId(), frequency, n, executionDate, deadline);
        return new SlaResolution(null, deadline);
    }

    /**
     * Parses a self-describing slaTime spec. Tries, in order: {@code T+N@HH:mm}, ISO-8601 duration,
     * bare {@code HH:mm}. Throws {@link DomainValidationException} naming the three accepted forms otherwise.
     */
    private static ParsedSpec parseSpec(String value) {
        Matcher m = TPLUS_PATTERN.matcher(value);
        if (m.matches()) {
            int offset = Integer.parseInt(m.group(1));
            if (offset < 1) {
                throw new DomainValidationException(
                        "Invalid slaTime. T+N offset must be >= 1. Accepted forms: T+N@HH:mm (N>=1), HH:mm, ISO-8601 duration (e.g. PT2H30M).");
            }
            LocalTime cutoff = LocalTime.parse(m.group(2));
            return new ParsedSpec(offset, cutoff, null);
        }

        // ISO-8601 duration (no HH:mm value parses as a Duration, so this is safe to try first).
        try {
            Duration duration = Duration.parse(value);
            if (duration.toMillis() <= 0) {
                throw new DomainValidationException("slaTime must be a positive ISO-8601 duration");
            }
            return new ParsedSpec(null, null, duration);
        } catch (DateTimeParseException ignored) {
            // fall through to clock / invalid
        }

        try {
            LocalTime cutoff = LocalTime.parse(value);
            return new ParsedSpec(null, cutoff, null);
        } catch (DateTimeParseException ignored) {
            // fall through to invalid
        }

        throw new DomainValidationException(
                "Invalid slaTime. Accepted forms: T+N@HH:mm (N>=1), HH:mm, ISO-8601 duration (e.g. PT2H30M).");
    }

    private SlaResolution resolveFromFallback(StartRunRequest request, Frequency frequency, CalculatorProfile profile) {
        Long baselineMs = resolveFallbackBaselineMs(request, profile);
        if (baselineMs == null) {
            meterRegistry.counter("obs.sla.baseline.ungraded", "frequency", frequency.name()).increment();
            log.debug("event=sla.baseline.resolve outcome=ungraded form=fallback calculatorId={} frequency={}",
                    request.getCalculatorId(), frequency);
            return new SlaResolution(null, null);
        }
        long bufferedMs = Math.round(baselineMs * (1 + slaProperties.getDurationThresholdPercent() / 100.0));
        Instant deadline = request.getStartTime().plusMillis(bufferedMs + slaProperties.lateBandMs());
        log.debug("event=sla.baseline.resolve outcome=success form=fallback calculatorId={} frequency={} baselineMs={} deadline={}",
                request.getCalculatorId(), frequency, baselineMs, deadline);
        return new SlaResolution(baselineMs, deadline);
    }

    private Long resolveFallbackBaselineMs(StartRunRequest request, CalculatorProfile profile) {
        if (request.getExpectedDurationMs() != null && request.getExpectedDurationMs() > 0) {
            return request.getExpectedDurationMs();
        }
        if (profile != null && profile.hasSufficientSamples(slaProperties.getMinSampleSize()) && profile.avgDurationMs() > 0) {
            return profile.avgDurationMs();
        }
        return null;
    }

    /**
     * Parses run_number string to an integer offset for business-day advancement.
     * Null or non-numeric → 2 (T+2 default, matching {@code COALESCE(run_number, '2')} convention).
     */
    static int parseRunNumber(String runNumber) {
        if (runNumber == null || runNumber.isBlank()) {
            return 2;
        }
        try {
            int n = Integer.parseInt(runNumber.trim());
            return n > 0 ? n : 2;
        } catch (NumberFormatException e) {
            return 2;
        }
    }
}
