package com.company.observability.domain.enums;

/**
 * Timing band for a calculator run's SLA evaluation.
 *
 * <p>Purely a timing concern — independent of run outcome (FAILED/TIMEOUT).
 * NULL band means the run was ungraded (no baseline available).
 *
 * <pre>
 *   actual <= lateEdgeMs         → ON_TIME
 *   lateEdgeMs < actual <= veryLateEdgeMs → LATE
 *   actual > veryLateEdgeMs      → VERY_LATE
 * </pre>
 *
 * <p>FAILED/TIMEOUT runs are NOT represented here; use {@code RunStatus} for that.
 * A failed run can be ON_TIME (failed fast) or VERY_LATE (ran long then failed).
 * A run is "problematic" when: {@code status IN (FAILED, TIMEOUT) OR band <> ON_TIME}.
 */
public enum SlaBand {
    ON_TIME,    // actual <= late edge
    LATE,       // late edge < actual <= very-late edge
    VERY_LATE;  // actual > very-late edge

    public boolean isBreached() {
        return this != ON_TIME;
    }
}
