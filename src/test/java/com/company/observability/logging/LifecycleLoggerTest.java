package com.company.observability.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static net.logstash.logback.argument.StructuredArguments.kv;
import static org.junit.jupiter.api.Assertions.*;

class LifecycleLoggerTest {

    private LifecycleLogger lifecycleLogger;
    private ListAppender<ILoggingEvent> listAppender;
    private Logger lifecycleLogbackLogger;

    @BeforeEach
    void setUp() {
        lifecycleLogger = new LifecycleLogger();
        lifecycleLogbackLogger = (Logger) LoggerFactory.getLogger("lifecycle");
        listAppender = new ListAppender<>();
        listAppender.start();
        lifecycleLogbackLogger.addAppender(listAppender);
        lifecycleLogbackLogger.setLevel(Level.ALL);
    }

    @AfterEach
    void tearDown() {
        lifecycleLogbackLogger.detachAppender(listAppender);
    }

    // --- message format ---

    @Test
    void emit_messageContainsEventAndOutcome() {
        lifecycleLogger.emit(LifecycleEvent.RUN_START_SUCCESS);

        assertEquals(1, listAppender.list.size());
        String msg = listAppender.list.get(0).getFormattedMessage();
        assertTrue(msg.contains("event=run.start"), "message should contain event=run.start, got: " + msg);
        assertTrue(msg.contains("outcome=success"), "message should contain outcome=success, got: " + msg);
    }

    @Test
    void emit_withExtras_includesThemInMessage() {
        lifecycleLogger.emit(LifecycleEvent.RUN_START_SUCCESS,
                kv("freq", "DAILY"), kv("reportingDate", "2026-04-19"));

        String msg = listAppender.list.get(0).getFormattedMessage();
        assertTrue(msg.contains("freq=DAILY"), "message should contain freq=DAILY, got: " + msg);
        assertTrue(msg.contains("reportingDate=2026-04-19"), "message should contain reportingDate, got: " + msg);
    }

    @Test
    void emit_withRejected_usesCorrectOutcomeInMessage() {
        lifecycleLogger.emit(LifecycleEvent.RUN_START_REJECTED, kv("reason", "duplicate"));

        String msg = listAppender.list.get(0).getFormattedMessage();
        assertTrue(msg.contains("outcome=rejected"), "message should contain outcome=rejected, got: " + msg);
        assertTrue(msg.contains("reason=duplicate"), "message should contain reason=duplicate, got: " + msg);
    }

    // --- log levels ---

    @Test
    void emit_runStartSuccess_logsAtInfoLevel() {
        lifecycleLogger.emit(LifecycleEvent.RUN_START_SUCCESS);
        assertEquals(Level.INFO, listAppender.list.get(0).getLevel());
    }

    @Test
    void emit_runStartRejected_logsAtWarnLevel() {
        lifecycleLogger.emit(LifecycleEvent.RUN_START_REJECTED);
        assertEquals(Level.WARN, listAppender.list.get(0).getLevel());
    }

    @Test
    void emit_slaAlertFailed_logsAtErrorLevel() {
        lifecycleLogger.emit(LifecycleEvent.SLA_ALERT_FAILED);
        assertEquals(Level.ERROR, listAppender.list.get(0).getLevel());
    }

    @Test
    void emit_slaLiveBreach_logsAtWarnLevel() {
        lifecycleLogger.emit(LifecycleEvent.SLA_LIVE_BREACH);
        assertEquals(Level.WARN, listAppender.list.get(0).getLevel());
    }

    // --- throwable overload ---

    @Test
    void emit_withThrowable_attachesThrowableAsCause() {
        RuntimeException cause = new RuntimeException("delivery failed");

        lifecycleLogger.emit(LifecycleEvent.SLA_ALERT_FAILED, cause);

        ILoggingEvent event = listAppender.list.get(0);
        assertNotNull(event.getThrowableProxy(), "throwable proxy should be set");
        assertEquals("delivery failed", event.getThrowableProxy().getMessage());
    }

    @Test
    void emit_withThrowableAndExtras_includesExtrasInMessageAndAttachesThrowable() {
        RuntimeException cause = new RuntimeException("timeout");

        lifecycleLogger.emit(LifecycleEvent.SLA_ALERT_FAILED, cause, kv("breachId", "42"));

        ILoggingEvent event = listAppender.list.get(0);
        String msg = event.getFormattedMessage();
        assertTrue(msg.contains("breachId=42"), "message should contain breachId, got: " + msg);
        assertNotNull(event.getThrowableProxy());
    }

    // --- logger name ---

    @Test
    void emit_usesLifecycleLoggerName() {
        lifecycleLogger.emit(LifecycleEvent.RUN_COMPLETE_SUCCESS);
        assertEquals("lifecycle", listAppender.list.get(0).getLoggerName());
    }

    // --- enum correctness ---

    @Test
    void lifecycleEvent_runStartSuccess_hasCorrectFields() {
        assertEquals("run.start", LifecycleEvent.RUN_START_SUCCESS.getEventName());
        assertEquals("success", LifecycleEvent.RUN_START_SUCCESS.getDefaultOutcome());
        assertEquals(org.slf4j.event.Level.INFO, LifecycleEvent.RUN_START_SUCCESS.getLevel());
    }

    @Test
    void lifecycleEvent_slaBreachAlert_hasCorrectFields() {
        assertEquals("sla.breach.alert", LifecycleEvent.SLA_BREACH_ALERT.getEventName());
        assertEquals("emitted", LifecycleEvent.SLA_BREACH_ALERT.getDefaultOutcome());
        assertEquals(org.slf4j.event.Level.ERROR, LifecycleEvent.SLA_BREACH_ALERT.getLevel());
    }
}
