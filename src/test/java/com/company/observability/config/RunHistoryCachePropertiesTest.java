package com.company.observability.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RunHistoryCachePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @EnableConfigurationProperties(RunHistoryCacheProperties.class)
    static class Config {
    }

    @Test
    void defaults() {
        runner.run(ctx -> {
            RunHistoryCacheProperties props = ctx.getBean(RunHistoryCacheProperties.class);
            assertThat(props.isEnabled()).isTrue();
            assertThat(props.getTransientTtl()).isEqualTo(Duration.ofSeconds(10));
            assertThat(props.getTerminalTtl()).isEqualTo(Duration.ofMinutes(5));
            assertThat(props.getGenerationTtl()).isEqualTo(Duration.ofMinutes(15));
        });
    }

    @Test
    void bindsUnderDocumentedPrefix() {
        runner.withPropertyValues(
                        "observability.run-history-cache.enabled=false",
                        "observability.run-history-cache.transient-ttl=3s",
                        "observability.run-history-cache.terminal-ttl=2m",
                        "observability.run-history-cache.generation-ttl=30m")
                .run(ctx -> {
                    RunHistoryCacheProperties props = ctx.getBean(RunHistoryCacheProperties.class);
                    assertThat(props.isEnabled()).isFalse();
                    assertThat(props.getTransientTtl()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(props.getTerminalTtl()).isEqualTo(Duration.ofMinutes(2));
                    assertThat(props.getGenerationTtl()).isEqualTo(Duration.ofMinutes(30));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"transient-ttl", "terminal-ttl", "generation-ttl"})
    void subSecondDuration_failsValidation(String property) {
        runner.withPropertyValues("observability.run-history-cache." + property + "=500ms")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
