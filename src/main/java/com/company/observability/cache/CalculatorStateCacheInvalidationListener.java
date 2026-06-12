package com.company.observability.cache;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.event.RunCompletedEvent;
import com.company.observability.event.RunStartedEvent;
import com.company.observability.event.SlaBreachedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Evicts the {@code obs:state} cache entry for a run whenever its lifecycle state changes,
 * so the {@code /batch/runs} dashboard feed reflects NOT_STARTED→RUNNING→terminal transitions on
 * the next query rather than waiting out the TTL (previously up to ~60 s stale).
 *
 * <p>Mirrors {@link AnalyticsCacheService}'s listener pattern: {@code AFTER_COMMIT} + {@code @Async}
 * so eviction runs after the DB transaction commits, on the async pool. State-cache keys use the
 * <em>real</em> {@code calculator_name} (writes happen per real name in {@code CalculatorStateService};
 * alias re-grouping is per-request in the controller) — no alias eviction needed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CalculatorStateCacheInvalidationListener {

    private final CalculatorStateCacheService stateCache;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onRunStarted(RunStartedEvent event) {
        evict(event.getRun());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onRunCompleted(RunCompletedEvent event) {
        evict(event.getRun());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onSlaBreached(SlaBreachedEvent event) {
        evict(event.getRun());
    }

    private void evict(CalculatorRun run) {
        stateCache.evictEntry(run.getCalculatorName(), run.getReportingDate(),
                run.getFrequency().name(), run.getRunNumber());
    }
}
