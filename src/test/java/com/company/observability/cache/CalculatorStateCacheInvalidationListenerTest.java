package com.company.observability.cache;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.event.RunCompletedEvent;
import com.company.observability.event.RunStartedEvent;
import com.company.observability.event.SlaBreachedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CalculatorStateCacheInvalidationListenerTest {

    @Mock
    private CalculatorStateCacheService stateCache;

    @InjectMocks
    private CalculatorStateCacheInvalidationListener listener;

    private static final LocalDate DATE = LocalDate.of(2026, 5, 1);

    private CalculatorRun run() {
        return CalculatorRun.builder()
                .runId("r-1")
                .calculatorName("cap")
                .reportingDate(DATE)
                .frequency(Frequency.DAILY)
                .runNumber("1")
                .build();
    }

    @Test
    void onRunStarted_evictsByRunCoordinates() {
        listener.onRunStarted(new RunStartedEvent(run()));
        verify(stateCache).evictEntry("cap", DATE, "DAILY", "1");
    }

    @Test
    void onRunCompleted_evictsByRunCoordinates() {
        listener.onRunCompleted(new RunCompletedEvent(run()));
        verify(stateCache).evictEntry("cap", DATE, "DAILY", "1");
    }

    @Test
    void onSlaBreached_evictsByRunCoordinates() {
        listener.onSlaBreached(new SlaBreachedEvent(run(), null));
        verify(stateCache).evictEntry("cap", DATE, "DAILY", "1");
    }
}
