package com.company.observability.service;

import com.company.observability.alert.AlertDeliveryException;
import com.company.observability.alert.AlertSender;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.SlaBreachEvent;
import com.company.observability.domain.enums.AlertStatus;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.domain.enums.SlaBand;
import com.company.observability.event.SlaBreachedEvent;
import com.company.observability.repository.SlaBreachEventRepository;
import com.company.observability.domain.SlaEvaluationResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertHandlerServiceTest {

    @Mock
    private SlaBreachEventRepository breachRepository;

    @Mock
    private AlertSender alertSender;

    private SimpleMeterRegistry meterRegistry;
    private AlertHandlerService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new AlertHandlerService(breachRepository, meterRegistry, alertSender,
                new com.company.observability.logging.LifecycleLogger());
    }

    @Test
    void handleSlaBreachEvent_savesAndMarksAlertSent() {
        when(alertSender.channelName()).thenReturn("logging");
        CalculatorRun run = baseRun();
        SlaEvaluationResult result = new SlaEvaluationResult(SlaBand.LATE, "Finished 10 minutes late");

        SlaBreachEvent saved = SlaBreachEvent.builder()
                .breachId(101L)
                .runId(run.getRunId())
                .calculatorId(run.getCalculatorId())
                .calculatorName(run.getCalculatorName())
                .tenantId(run.getTenantId())
                .alerted(false)
                .retryCount(0)
                .build();

        when(breachRepository.save(any(SlaBreachEvent.class))).thenReturn(saved);

        service.handleSlaBreachEvent(new SlaBreachedEvent(run, result));

        verify(breachRepository).save(any(SlaBreachEvent.class));
        verify(alertSender).send(any(SlaBreachEvent.class));

        ArgumentCaptor<SlaBreachEvent> updateCaptor = ArgumentCaptor.forClass(SlaBreachEvent.class);
        verify(breachRepository).update(updateCaptor.capture());
        SlaBreachEvent updated = updateCaptor.getValue();

        assertTrue(Boolean.TRUE.equals(updated.getAlerted()));
        assertEquals(AlertStatus.SENT, updated.getAlertStatus());
        assertEquals(1.0, meterRegistry.get("obs.sla.breach.created").counter().count());
        assertEquals(1.0, meterRegistry.get("obs.sla.alert.sent").tag("channel", "logging").counter().count());
    }

    @Test
    void handleSlaBreachEvent_senderFails_marksAlertFailed() {
        when(alertSender.channelName()).thenReturn("logging");
        CalculatorRun run = baseRun();
        SlaEvaluationResult result = new SlaEvaluationResult(SlaBand.LATE, "Finished 10 minutes late");

        SlaBreachEvent saved = SlaBreachEvent.builder()
                .breachId(102L)
                .runId(run.getRunId())
                .calculatorId(run.getCalculatorId())
                .tenantId(run.getTenantId())
                .alerted(false)
                .retryCount(0)
                .build();

        when(breachRepository.save(any(SlaBreachEvent.class))).thenReturn(saved);
        doThrow(new AlertDeliveryException("send failed")).when(alertSender).send(any());

        assertThrows(AlertDeliveryException.class,
                () -> service.handleSlaBreachEvent(new SlaBreachedEvent(run, result)));

        ArgumentCaptor<SlaBreachEvent> updateCaptor = ArgumentCaptor.forClass(SlaBreachEvent.class);
        verify(breachRepository).update(updateCaptor.capture());
        SlaBreachEvent updated = updateCaptor.getValue();

        assertEquals(AlertStatus.FAILED, updated.getAlertStatus());
        assertEquals(1, updated.getRetryCount());
        assertEquals("send failed", updated.getLastError());
        assertEquals(1.0, meterRegistry.get("obs.sla.alert.failed").tag("channel", "logging").counter().count());
    }

    @Test
    void handleSlaBreachEvent_duplicateSaveSkipsAlertUpdate() {
        CalculatorRun run = baseRun();
        SlaEvaluationResult result = new SlaEvaluationResult(SlaBand.LATE, "Still running 5 minutes past SLA deadline");

        when(breachRepository.save(any(SlaBreachEvent.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        service.handleSlaBreachEvent(new SlaBreachedEvent(run, result));

        verify(breachRepository).save(any(SlaBreachEvent.class));
        verify(alertSender, never()).send(any());
        verify(breachRepository, never()).update(any(SlaBreachEvent.class));
        assertEquals(1.0, meterRegistry.get("obs.sla.breach.duplicate").counter().count());
    }

    private CalculatorRun baseRun() {
        return CalculatorRun.builder()
                .runId("run-1")
                .calculatorId("calc-1")
                .calculatorName("Calculator 1")
                .tenantId("tenant-1")
                .frequency(Frequency.DAILY)
                .slaTime(Instant.parse("2026-02-22T05:15:00Z"))
                .endTime(Instant.parse("2026-02-22T05:25:00Z"))
                .durationMs(600000L)
                .slaBreachReason("breach")
                .build();
    }
}
