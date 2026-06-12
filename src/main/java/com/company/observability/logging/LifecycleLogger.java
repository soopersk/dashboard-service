package com.company.observability.logging;

import net.logstash.logback.argument.StructuredArgument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Central logger for domain lifecycle events.
 *
 * Uses {@code StructuredArguments.kv()} so that {@code event} and {@code outcome} appear as:
 * <ul>
 *   <li><b>Plain text</b> (local profile): {@code event=run.start outcome=success ...}</li>
 *   <li><b>JSON</b> (dev/prod via LogstashEncoder): top-level JSON fields alongside MDC keys</li>
 * </ul>
 *
 * Usage:
 * <pre>
 *   lifecycleLogger.emit(RUN_START_SUCCESS, kv("freq", frequency), kv("reportingDate", date));
 *   lifecycleLogger.emit(SLA_ALERT_FAILED, exception, kv("breachId", breach.getBreachId()));
 * </pre>
 */
@Component
public class LifecycleLogger {

    private static final Logger log = LoggerFactory.getLogger("lifecycle");

    /**
     * Emits a lifecycle event with optional extra structured fields.
     */
    public void emit(LifecycleEvent event, StructuredArgument... extras) {
        List<Object> args = buildArgs(event, extras);
        String pattern = buildPattern(args.size());
        logAtLevel(event, pattern, args.toArray());
    }

    /**
     * Emits a lifecycle event with a throwable cause and optional extra structured fields.
     * The throwable is attached to the log event (not rendered as a {} placeholder).
     */
    public void emit(LifecycleEvent event, Throwable cause, StructuredArgument... extras) {
        List<Object> args = buildArgs(event, extras);
        String pattern = buildPattern(args.size());
        // SLF4J normalizes trailing Throwable — no placeholder needed
        args.add(cause);
        logAtLevel(event, pattern, args.toArray());
    }

    private List<Object> buildArgs(LifecycleEvent event, StructuredArgument[] extras) {
        List<Object> args = new ArrayList<>();
        args.add(kv("event", event.getEventName()));
        args.add(kv("outcome", event.getDefaultOutcome()));
        args.addAll(Arrays.asList(extras));
        return args;
    }

    private String buildPattern(int argCount) {
        return IntStream.range(0, argCount)
                .mapToObj(i -> "{}")
                .collect(Collectors.joining(" "));
    }

    private void logAtLevel(LifecycleEvent event, String pattern, Object[] args) {
        switch (event.getLevel()) {
            case ERROR -> log.error(pattern, args);
            case WARN  -> log.warn(pattern, args);
            case INFO  -> log.info(pattern, args);
            default    -> log.debug(pattern, args);
        }
    }
}
