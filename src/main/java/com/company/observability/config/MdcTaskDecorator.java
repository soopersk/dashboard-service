package com.company.observability.config;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Propagates MDC context from the caller thread to the async thread.
 * Ensures @Async event listeners retain requestId, tenant, calculatorId, runId.
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // Capture caller thread's MDC
        Map<String, String> callerMdc = MDC.getCopyOfContextMap();

        return () -> {
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            try {
                if (callerMdc != null) {
                    MDC.setContextMap(callerMdc);
                } else {
                    MDC.clear();
                }
                runnable.run();
            } finally {
                if (previousMdc != null) {
                    MDC.setContextMap(previousMdc);
                } else {
                    MDC.clear();
                }
            }
        };
    }
}
