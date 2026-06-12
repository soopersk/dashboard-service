package com.company.observability.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Shared test configuration providing a no-op {@link MeterRegistry}.
 *
 * <p>Import this in any test class that slices the Spring context (e.g.
 * {@code @WebMvcTest}) and therefore does not auto-configure Micrometer:
 *
 * <pre>{@code
 * @WebMvcTest(MyController.class)
 * @Import({GlobalExceptionHandler.class, TestMetricsConfig.class})
 * class MyControllerTest { ... }
 * }</pre>
 */
@TestConfiguration
public class TestMetricsConfig {

    @Bean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
