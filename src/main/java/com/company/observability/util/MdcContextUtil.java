package com.company.observability.util;

import com.company.observability.domain.SlaBreachEvent;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * MDC context management with snapshot/restore semantics.
 * Every "set" method returns a snapshot of prior MDC state.
 * The only way to undo context is restoreContext(snapshot).
 */
public final class MdcContextUtil {

    private MdcContextUtil() {}

    /**
     * Set calculatorId and runId in MDC. Returns snapshot of prior state.
     */
    public static Map<String, String> setCalculatorContext(String calculatorId, String runId) {
        Map<String, String> snapshot = MDC.getCopyOfContextMap();
        if (snapshot == null) {
            snapshot = new HashMap<>();
        }
        MDC.put("calculatorId", calculatorId != null ? calculatorId : "-");
        MDC.put("runId", runId != null ? runId : "-");
        return snapshot;
    }

    /**
     * Set job context in MDC (for scheduled jobs). Returns snapshot of prior state.
     * Sets a synthetic requestId and tenant=system.
     */
    public static Map<String, String> setJobContext(String jobName) {
        Map<String, String> snapshot = MDC.getCopyOfContextMap();
        if (snapshot == null) {
            snapshot = new HashMap<>();
        }
        String shortId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("requestId", "job-" + shortId);
        MDC.put("tenant", "system");
        return snapshot;
    }

    /**
     * Set alert context in MDC for structured alert log emission. Returns snapshot of prior state.
     * Must always be followed by restoreContext(snapshot) in a finally block.
     */
    public static Map<String, String> setAlertContext(SlaBreachEvent breach) {
        Map<String, String> snapshot = MDC.getCopyOfContextMap();
        if (snapshot == null) {
            snapshot = new HashMap<>();
        }
        MDC.put("alert_source", "observability-service");
        MDC.put("alert_type", "SLA_BREACH");
        MDC.put("calculatorId", nullSafe(breach.getCalculatorId()));
        MDC.put("calculatorName", nullSafe(breach.getCalculatorName()));
        MDC.put("tenantId", nullSafe(breach.getTenantId()));
        MDC.put("runId", nullSafe(breach.getRunId()));
        MDC.put("breachTypeName", breach.getBreachType() != null ? breach.getBreachType().name() : "UNKNOWN");
        MDC.put("breachType", breach.getBreachType() != null ? breach.getBreachType().name() : "UNKNOWN");
        if (breach.getExpectedValue() != null) {
            MDC.put("expectedValue", String.valueOf(breach.getExpectedValue()));
            MDC.put("expectedUnit", nullSafe(breach.getExpectedUnit()));
        }
        if (breach.getActualValue() != null) {
            MDC.put("actualValue", String.valueOf(breach.getActualValue()));
            MDC.put("actualUnit", nullSafe(breach.getActualUnit()));
        }
        return snapshot;
    }

    private static String nullSafe(String v) {
        return v != null ? v : "-";
    }

    /**
     * Restore MDC to a previous snapshot. Keys present in current MDC
     * but absent from the snapshot are removed.
     */
    public static void restoreContext(Map<String, String> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(snapshot);
        }
    }
}
