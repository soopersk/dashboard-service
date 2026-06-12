package com.company.observability.alert;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.company.observability.domain.SlaBreachEvent;
import com.company.observability.domain.enums.BreachType;
import com.company.observability.logging.LifecycleLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class StructuredLogAlertSenderTest {

    private StructuredLogAlertSender sender;
    private ListAppender<ILoggingEvent> listAppender;
    private Logger lifecycleLogger;

    @BeforeEach
    void setUp() {
        sender = new StructuredLogAlertSender(new LifecycleLogger());
        lifecycleLogger = (Logger) LoggerFactory.getLogger("lifecycle");
        listAppender = new ListAppender<>();
        listAppender.start();
        lifecycleLogger.addAppender(listAppender);
        lifecycleLogger.setLevel(Level.ALL);
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        lifecycleLogger.detachAppender(listAppender);
        MDC.clear();
    }

    @Test
    void send_emitsErrorLogWithStructuredAlertEvent() {
        SlaBreachEvent breach = validBreach();

        sender.send(breach);

        assertEquals(1, listAppender.list.size());
        ILoggingEvent event = listAppender.list.get(0);
        String message = event.getFormattedMessage();
        assertEquals(Level.ERROR, event.getLevel());
        assertTrue(message.contains("event=sla.breach.alert"), "message should contain event=sla.breach.alert, got: " + message);
        assertTrue(message.contains("outcome=emitted"), "message should contain outcome=emitted, got: " + message);
        assertTrue(message.contains("CALC-1"));
    }

    @Test
    void send_setsMdcAlertFieldsDuringSend_andRestoresAfterward() {
        MDC.put("calculatorId", "prior-calc");
        MDC.put("requestId", "req-123");

        sender.send(validBreach());

        // After send, MDC must be restored to prior state
        assertEquals("prior-calc", MDC.get("calculatorId"));
        assertEquals("req-123", MDC.get("requestId"));
        assertNull(MDC.get("alert_source"));
        assertNull(MDC.get("alert_type"));
    }

    @Test
    void send_mdcKeysSetDuringLogEmission() {
        listAppender.list.clear();

        sender.send(validBreach());

        assertEquals(1, listAppender.list.size());
        ILoggingEvent event = listAppender.list.get(0);
        assertEquals("observability-service", event.getMDCPropertyMap().get("alert_source"));
        assertEquals("SLA_BREACH", event.getMDCPropertyMap().get("alert_type"));
        assertEquals("TIME_EXCEEDED", event.getMDCPropertyMap().get("breachType"));
        assertEquals("CALC-1", event.getMDCPropertyMap().get("calculatorId"));
        assertEquals("tenant-1", event.getMDCPropertyMap().get("tenantId"));
    }

    @Test
    void send_whenNullBreach_throwsAlertDeliveryException() {
        assertThrows(AlertDeliveryException.class, () -> sender.send(null));
    }

    @Test
    void channelName_returnsLogging() {
        assertEquals("logging", sender.channelName());
    }

    private SlaBreachEvent validBreach() {
        return SlaBreachEvent.builder()
                .breachId(1L)
                .runId("run-1")
                .calculatorId("CALC-1")
                .calculatorName("Risk Calculator")
                .tenantId("tenant-1")
                .breachType(BreachType.TIME_EXCEEDED)
                .expectedValue(1740200100L)
                .expectedUnit("epoch_seconds")
                .actualValue(1740200700L)
                .actualUnit("epoch_seconds")
                .createdAt(Instant.now())
                .build();
    }
}
