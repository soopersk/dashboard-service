package com.company.observability.config;

import com.company.observability.repository.CalculatorRunRepository;
import com.company.observability.util.ObservabilityConstants;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Application-specific metrics
 */
@Configuration
@Slf4j
@RequiredArgsConstructor
public class MetricsConfiguration {

    private final CalculatorRunRepository runRepository;

    @Bean
    public MeterBinder customMetrics(MeterRegistry registry) {
        return (reg) -> {
            // Active calculator runs gauge
            Gauge.builder(ObservabilityConstants.INGESTION_RUN_ACTIVE, runRepository, repo -> {
                        try {
                            return repo.countRunning();
                        } catch (Exception e) {
                            log.warn("event=metrics.gauge outcome=failure gauge={} error={}",
                                    ObservabilityConstants.INGESTION_RUN_ACTIVE, e.getMessage());
                            return 0;
                        }
                    })
                    .description("Number of currently running calculator runs")
                    .register(reg);

            log.info("event=metrics.registered gauges=[{}]", ObservabilityConstants.INGESTION_RUN_ACTIVE);
        };
    }
}
