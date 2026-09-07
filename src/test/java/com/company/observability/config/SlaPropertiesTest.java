package com.company.observability.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binding tests for {@link SlaProperties}.
 *
 * <p>Focus: {@code observability.sla.live-detection.enabled}. The same key is named as a raw string
 * by {@code LiveSlaBreachDetectionJob}'s {@code @ConditionalOnProperty} (bean conditions are
 * evaluated before property binding, so it cannot read this class). These tests pin the key and the
 * opt-in default so the two halves cannot drift apart silently — a broken binding here surfaces in
 * production as a hung run that never breaches.
 */
class SlaPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @EnableConfigurationProperties(SlaProperties.class)
    static class Config {}

    @Test
    void liveDetectionEnabled_defaultsToFalseWhenKeyAbsent() {
        // Opt-in: must match matchIfMissing=false on the job's @ConditionalOnProperty.
        runner.run(context ->
                assertThat(context.getBean(SlaProperties.class).isLiveDetectionEnabled()).isFalse());
    }

    @Test
    void liveDetectionEnabled_bindsTrueFromProperty() {
        runner.withPropertyValues("observability.sla.live-detection.enabled=true")
                .run(context ->
                        assertThat(context.getBean(SlaProperties.class).isLiveDetectionEnabled()).isTrue());
    }

    @Test
    void liveDetectionEnabled_bindsFalseFromProperty() {
        runner.withPropertyValues("observability.sla.live-detection.enabled=false")
                .run(context ->
                        assertThat(context.getBean(SlaProperties.class).isLiveDetectionEnabled()).isFalse());
    }
}
