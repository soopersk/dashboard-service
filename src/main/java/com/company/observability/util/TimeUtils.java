package com.company.observability.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.*;

public class TimeUtils {

    // "Europe/Amsterdam" correctly handles CET (UTC+1) and CEST (UTC+2, DST).
    // "CET" is a fixed offset (+01:00) and does NOT observe DST — never use it.
    private static final ZoneId CET_ZONE = ZoneId.of("Europe/Amsterdam");

    /**
     * Advance {@code start} by {@code n} business days, skipping weekends (Sat/Sun).
     *
     * <p>An SLA deadline is a <em>business fact</em>: anchoring to {@code reportingDate + n}
     * makes it independent of when Airflow happened to trigger the run.
     *
     * <ul>
     *   <li>{@code n=1} → next business day after {@code start} (Fri+1 → Mon)</li>
     *   <li>{@code n=2} → T+2 (Fri+2 → Tue)</li>
     *   <li>{@code n<=0} → {@code start} unchanged (guard for null/missing run_number)</li>
     * </ul>
     */
    public static LocalDate nextBusinessDay(LocalDate start, int n) {
        if (start == null || n <= 0) {
            return start;
        }
        LocalDate result = start;
        int stepsTaken = 0;
        while (stepsTaken < n) {
            result = result.plusDays(1);
            DayOfWeek dow = result.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                stepsTaken++;
            }
        }
        return result;
    }

    /**
     * Count business days (weekends skipped) stepping forward from {@code from} to {@code to}.
     * Inverse of {@link #nextBusinessDay(LocalDate, int)}: {@code nextBusinessDay(from, businessDaysBetween(from, to)) == to}
     * for any {@code to > from} that is itself a business day.
     *
     * <ul>
     *   <li>Fri → Mon = 1, Fri → Tue = 2, Mon → Tue = 1</li>
     *   <li>{@code to <= from} or either null → 0</li>
     * </ul>
     */
    public static int businessDaysBetween(LocalDate from, LocalDate to) {
        if (from == null || to == null || !to.isAfter(from)) {
            return 0;
        }
        int count = 0;
        LocalDate cursor = from;
        while (cursor.isBefore(to)) {
            cursor = cursor.plusDays(1);
            DayOfWeek dow = cursor.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                count++;
            }
        }
        return count;
    }

    /**
     * Calculate absolute SLA deadline time from reporting date and SLA time of day (CET)
     *
     * @param reportingDate Reporting date in CET calendar
     * @param slaTimeCet Target completion time in CET (e.g., 06:15:00)
     * @return Absolute deadline time in UTC
     */
    public static Instant calculateSlaDeadline(LocalDate reportingDate, LocalTime slaTimeCet) {
        if (reportingDate == null || slaTimeCet == null) return null;

        ZonedDateTime slaDateTime = ZonedDateTime.of(reportingDate, slaTimeCet, CET_ZONE);
        return slaDateTime.toInstant();
    }

    /**
     * Derive an absolute deadline from a clock time (HH:mm in {@code zone}) anchored to the
     * run's start date in that zone. If the derived deadline is at or before {@code startTime},
     * it is rolled forward by one day to handle overnight SLA windows (e.g. start 23:00,
     * sla 06:00 → next day 06:00). The zone is the configured {@code observability.sla.slaTimezone},
     * matching how DAILY clock specs are interpreted.
     */
    public static Instant clockTimeDeadline(Instant startTime, LocalTime slaClockTime, ZoneId zone) {
        if (startTime == null || slaClockTime == null || zone == null) return null;
        LocalDate startDate = startTime.atZone(zone).toLocalDate();
        Instant deadline = ZonedDateTime.of(startDate, slaClockTime, zone).toInstant();
        if (!deadline.isAfter(startTime)) {
            deadline = deadline.plus(Duration.ofDays(1));
        }
        return deadline;
    }

    /**
     * Calculate estimated end time from start time and expected duration
     */
    public static Instant calculateEstimatedEndTime(Instant startTime, Long expectedDurationMs) {
        if (startTime == null || expectedDurationMs == null) return null;
        return startTime.plusMillis(expectedDurationMs);
    }

    /**
     * Map an average minute-of-day (minutes since UTC midnight) onto a UTC date.
     * Used to turn a calculator's historical average start/end minute into a concrete
     * Instant for estimated start/end fallbacks.
     */
    public static Instant instantFromUtcMinuteOfDay(LocalDate dateUtc, int minuteOfDay) {
        if (dateUtc == null) return null;
        return dateUtc.atStartOfDay(ZoneOffset.UTC).toInstant().plus(Duration.ofMinutes(minuteOfDay));
    }

    public static BigDecimal calculateCetHour(Instant instant) {
        if (instant == null) return null;

        int secondsOfDay = instant.atZone(CET_ZONE).toLocalTime().toSecondOfDay();
        return BigDecimal.valueOf(secondsOfDay).divide(BigDecimal.valueOf(3600), 2, RoundingMode.HALF_UP);
    }

    public static LocalDate getCetDate(Instant instant) {
        if (instant == null) return null;
        return instant.atZone(CET_ZONE).toLocalDate();
    }

    public static String formatDuration(Long durationMs) {
        if (durationMs == null) return null;

        long hours = durationMs / 3_600_000;
        long minutes = (durationMs % 3_600_000) / 60_000;
        long seconds = (durationMs % 60_000) / 1_000;

        if (hours > 0) {
            return String.format("%dhrs %dmins", hours, minutes);
        } else if (minutes > 0) {
            return String.format("%dmins %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }

    public static String formatCetHour(BigDecimal hourCet) {
        if (hourCet == null) return null;

        // Convert fractional hours back to total seconds to avoid floating-point rounding
        // e.g. 6.25 → 22500s → hour=6, minute=15
        int totalSeconds = hourCet.multiply(BigDecimal.valueOf(3600))
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
        int hour = totalSeconds / 3600;
        int minute = (totalSeconds % 3600) / 60;

        return String.format("%02d:%02d", hour, minute);
    }

    public static Timestamp toTimestamp(Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }

    public static Instant fromTimestamp(Timestamp timestamp) {
        return timestamp != null ? timestamp.toInstant() : null;
    }

}
