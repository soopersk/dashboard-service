package com.company.observability.service;

import com.company.observability.alert.AlertDeliveryException;
import com.company.observability.alert.AlertSender;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.SlaBreachEvent;
import com.company.observability.domain.enums.AlertStatus;
import com.company.observability.domain.enums.BreachType;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.domain.enums.SlaBand;
import com.company.observability.event.SlaBreachedEvent;
import com.company.observability.logging.LifecycleEvent;
import com.company.observability.logging.LifecycleLogger;
import com.company.observability.repository.SlaBreachEventRepository;
import com.company.observability.util.MdcContextUtil;
import com.company.observability.domain.SlaEvaluationResult;

import static net.logstash.logback.argument.StructuredArguments.kv;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

import static com.company.observability.util.ObservabilityConstants.*;

@Service
@RequiredArgsConstructor
public class AlertHandlerService {

    private final SlaBreachEventRepository breachRepository;
    private final MeterRegistry meterRegistry;
    private final AlertSender alertSender;
    private final LifecycleLogger lifecycleLogger;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleSlaBreachEvent(SlaBreachedEvent event) {
        CalculatorRun run = event.getRun();
        SlaEvaluationResult result = event.getResult();

        var prev = MdcContextUtil.setCalculatorContext(run.getCalculatorId(), run.getRunId());
        try {
            doHandleSlaBreachEvent(run, result);
        } finally {
            MdcContextUtil.restoreContext(prev);
        }
    }

    private void doHandleSlaBreachEvent(CalculatorRun run, SlaEvaluationResult result) {
        lifecycleLogger.emit(LifecycleEvent.SLA_BREACH_PROCESSED, kv("reason", result.getReason()));

        SlaBreachEvent breach = SlaBreachEvent.builder()
                .runId(run.getRunId())
                .calculatorId(run.getCalculatorId())
                .calculatorName(run.getCalculatorName())
                .tenantId(run.getTenantId())
                .reportingDate(run.getReportingDate())
                .breachType(determineBreachType(run))
                .expectedValue(calculateExpectedValue(run))
                .expectedUnit(run.getSlaTime() != null ? "epoch_seconds"
                        : run.getExpectedDurationMs() != null ? "duration_ms" : null)
                .actualValue(calculateActualValue(run))
                .actualUnit(run.getEndTime() != null && run.getSlaTime() != null ? "epoch_seconds"
                        : run.getDurationMs() != null ? "duration_ms" : null)
                .alerted(false)
                .alertStatus(AlertStatus.PENDING)
                .retryCount(0)
                .createdAt(Instant.now())
                .build();

        SlaBreachEvent savedBreach;

        try {
            savedBreach = breachRepository.save(breach);

            meterRegistry.counter(SLA_BREACH_CREATED,
                    "band", result.getBand() != null ? result.getBand().name() : "NONE",
                    "frequency", run.getFrequency().name()
            ).increment();

        } catch (DuplicateKeyException e) {
            lifecycleLogger.emit(LifecycleEvent.SLA_BREACH_PERSIST_REJECTED, kv("reason", "duplicate"));

            meterRegistry.counter(SLA_BREACH_DUPLICATE,
                    "frequency", run.getFrequency().name()
            ).increment();

            return;
        }

        sendAlert(savedBreach, run);
    }

    private void sendAlert(SlaBreachEvent breach, CalculatorRun run) {
        try {
            alertSender.send(breach);
            breach.setAlerted(true);
            breach.setAlertedAt(Instant.now());
            breach.setAlertStatus(AlertStatus.SENT);
            breachRepository.update(breach);

            meterRegistry.counter(SLA_ALERT_SENT,
                    "band", run.getSlaBand() != null ? run.getSlaBand().name() : "NONE",
                    "frequency", run.getFrequency().name(),
                    "channel", alertSender.channelName()
            ).increment();

            lifecycleLogger.emit(LifecycleEvent.SLA_ALERT_SENT, kv("breachId", breach.getBreachId()));

        } catch (AlertDeliveryException e) {
            markFailed(breach, run, e);
            throw e;
        } catch (Exception e) {
            AlertDeliveryException wrapped = new AlertDeliveryException("Unexpected sender failure", e);
            markFailed(breach, run, wrapped);
            throw wrapped;
        }
    }

    private void markFailed(SlaBreachEvent breach, CalculatorRun run, Exception e) {
        lifecycleLogger.emit(LifecycleEvent.SLA_ALERT_FAILED, e, kv("breachId", breach.getBreachId()));
        breach.setAlertStatus(AlertStatus.FAILED);
        breach.setRetryCount(breach.getRetryCount() + 1);
        breach.setLastError(e.getMessage());
        breachRepository.update(breach);

        meterRegistry.counter(SLA_ALERT_FAILED,
                "frequency", run.getFrequency().name(),
                "channel", alertSender.channelName()
        ).increment();
    }

    private BreachType determineBreachType(CalculatorRun run) {
        // Failure dimension takes precedence over timing
        if (run.getStatus() == RunStatus.FAILED) return BreachType.FAILED;
        if (run.getStatus() == RunStatus.TIMEOUT) return BreachType.TIMEOUT;
        // Timing dimension
        SlaBand band = run.getSlaBand();
        if (band == SlaBand.VERY_LATE || band == SlaBand.LATE) return BreachType.TIME_EXCEEDED;
        // Still-running live breach (no end time yet) — deadline was crossed
        if (run.getEndTime() == null) return BreachType.TIME_EXCEEDED;
        return BreachType.UNKNOWN;
    }

    private Long calculateExpectedValue(CalculatorRun run) {
        if (run.getSlaTime() != null) {
            return run.getSlaTime().getEpochSecond();
        } else if (run.getExpectedDurationMs() != null) {
            return run.getExpectedDurationMs();
        }
        return null;
    }

    private Long calculateActualValue(CalculatorRun run) {
        if (run.getEndTime() != null && run.getSlaTime() != null) {
            return run.getEndTime().getEpochSecond();
        } else if (run.getDurationMs() != null) {
            return run.getDurationMs();
        }
        return null;
    }
}

