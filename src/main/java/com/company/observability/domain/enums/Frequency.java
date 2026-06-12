package com.company.observability.domain.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.time.Duration;

public enum Frequency {
    DAILY(2),    // Look back 2 days for DAILY calculators
    MONTHLY(10); // Look back 10 days for MONTHLY calculators

    private final int lookbackDays;

    Frequency(int lookbackDays) {
        this.lookbackDays = lookbackDays;
    }

    public int getLookbackDays() {
        return lookbackDays;
    }

    public Duration getLookbackDuration() {
        return Duration.ofDays(lookbackDays);
    }


    /**
     * Jackson binding for request DTOs ({@code StartRunRequest}). Strict: unknown values are
     * rejected (surface as 400 via HttpMessageNotReadableException) instead of being silently
     * coerced to DAILY — a typo'd frequency must not ingest a run into the wrong bucket.
     * Absent/null JSON fields bypass this creator and are caught by {@code @NotNull}.
     */
    @JsonCreator
    public static Frequency fromJson(String frequency) {
        return fromStrict(frequency);
    }

    /**
     * Accepts D, DAILY, M, MONTHLY (case-insensitive)
     * Normalizes to DAILY / MONTHLY
     * Default is DAILY
     *
     * <p>Lenient — for DB row mappers only (stored values are trusted). NOT used for JSON
     * binding; see {@link #fromJson(String)}.
     */
    public static Frequency from(String frequency) {
        if (frequency == null || frequency.isBlank()) {
            return DAILY;
        }
        return switch (frequency.trim().toUpperCase()) {
            case "M", "MONTHLY" -> MONTHLY;
            default -> DAILY;
        };
    }

    /**
     * Strict parsing for query/analytics endpoints. Rejects invalid values with IllegalArgumentException.
     */
    public static Frequency fromStrict(String frequency) {
        if (frequency == null || frequency.isBlank()) {
            throw new IllegalArgumentException("Frequency is required. Valid values: DAILY, D, MONTHLY, M");
        }
        return switch (frequency.trim().toUpperCase()) {
            case "D", "DAILY" -> DAILY;
            case "M", "MONTHLY" -> MONTHLY;
            default -> throw new IllegalArgumentException(
                    "Unknown frequency: '" + frequency + "'. Valid values: DAILY, D, MONTHLY, M");
        };
    }
}