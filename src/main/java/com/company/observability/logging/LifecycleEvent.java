package com.company.observability.logging;

import org.slf4j.event.Level;

/**
 * Type-safe lifecycle events for domain state transitions.
 * Use with {@link LifecycleLogger} — never log lifecycle events via raw logger calls.
 *
 * Lifecycle events are business-meaningful domain state transitions (run start/complete,
 * SLA breach, alert delivery). Operational logs (cache ops, partition management, queries)
 * use standard log.info/warn/error calls and are NOT represented here.
 */
public enum LifecycleEvent {

    // Run ingestion — controller boundary (HTTP accepted)
    RUN_START_ACCEPTED("run.start", "accepted", Level.INFO),
    RUN_COMPLETE_ACCEPTED("run.complete", "accepted", Level.INFO),

    // Run ingestion — service boundary (persisted to DB)
    RUN_START_SUCCESS("run.start", "success", Level.INFO),
    RUN_START_REJECTED("run.start", "rejected", Level.WARN),
    RUN_COMPLETE_SUCCESS("run.complete", "success", Level.INFO),
    RUN_COMPLETE_REJECTED("run.complete", "rejected", Level.WARN),

    // SLA breach lifecycle
    SLA_BREACH_PROCESSED("sla.breach.process", "success", Level.INFO),
    SLA_BREACH_PERSIST_REJECTED("sla.breach.persist", "rejected", Level.WARN),
    SLA_LIVE_BREACH("sla.live_breach", "success", Level.WARN),

    // Alert delivery
    SLA_ALERT_SENT("sla.alert.send", "success", Level.INFO),
    SLA_ALERT_FAILED("sla.alert.send", "failure", Level.ERROR),
    SLA_BREACH_ALERT("sla.breach.alert", "emitted", Level.ERROR);

    private final String eventName;
    private final String defaultOutcome;
    private final Level level;

    LifecycleEvent(String eventName, String defaultOutcome, Level level) {
        this.eventName = eventName;
        this.defaultOutcome = defaultOutcome;
        this.level = level;
    }

    public String getEventName() {
        return eventName;
    }

    public String getDefaultOutcome() {
        return defaultOutcome;
    }

    public Level getLevel() {
        return level;
    }
}
