package com.company.observability.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binding tests for {@link SlaProperties}.
 *
 * <p>Focus: {@code observability.sla.live-tracking.enabled}. That key is also referenced as a raw
 * string by {@code LiveSlaBreachDetectionJob}'s {@code @ConditionalOnProperty} (bean conditions are
 * evaluated before property binding, so it cannot read this class), which makes a silent binding
 * break — a renamed nested type, a lost setter — invisible until a hung run fails to breach in
 * production. These tests pin the key and the default.
 */
class SlaPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @EnableConfigurationProperties(SlaProperties.class)
    static class Config {}

    @Test
    void liveTrackingEnabled_defaultsToTrueWhenKeyAbsent() {
        // Preserves the former @Value("${...:true}") default; base application.yml overrides to false.
        runner.run(context ->
                assertThat(context.getBean(SlaProperties.class).isLiveTrackingEnabled()).isTrue());
    }

    @Test
    void liveTrackingEnabled_bindsFalseFromProperty() {
        runner.withPropertyValues("observability.sla.live-tracking.enabled=false")
                .run(context ->
                        assertThat(context.getBean(SlaProperties.class).isLiveTrackingEnabled()).isFalse());
    }

    @Test
    void liveTrackingEnabled_bindsTrueFromProperty() {
        runner.withPropertyValues("observability.sla.live-tracking.enabled=true")
                .run(context ->
                        assertThat(context.getBean(SlaProperties.class).isLiveTrackingEnabled()).isTrue());
    }
}
