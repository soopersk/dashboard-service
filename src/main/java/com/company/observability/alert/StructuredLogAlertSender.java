package com.company.observability.alert;

import com.company.observability.domain.SlaBreachEvent;
import com.company.observability.logging.LifecycleEvent;
import com.company.observability.logging.LifecycleLogger;
import com.company.observability.util.MdcContextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Component
@RequiredArgsConstructor
public class StructuredLogAlertSender implements AlertSender {

    private final LifecycleLogger lifecycleLogger;

    @Override
    public void send(SlaBreachEvent breach) throws AlertDeliveryException {
        if (breach == null) {
            throw new AlertDeliveryException("breach payload is required");
        }

        Map<String, String> snapshot = MdcContextUtil.setAlertContext(breach);
        try {
            lifecycleLogger.emit(LifecycleEvent.SLA_BREACH_ALERT,
                    kv("calculator", breach.getCalculatorId()),
                    kv("breach", breach.getBreachType() != null ? breach.getBreachType().name() : "UNKNOWN"),
                    kv("tenant", breach.getTenantId()));
        } catch (Exception e) {
            throw new AlertDeliveryException("Failed to emit structured alert log", e);
        } finally {
            MdcContextUtil.restoreContext(snapshot);
        }
    }

    @Override
    public String channelName() {
        return "logging";
    }
}
