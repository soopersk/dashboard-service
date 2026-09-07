package com.company.observability.cache;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.company.observability.config.SlaProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies the one-shot startup WARN emitted when live SLA tracking is disabled.
 *
 * <p>Runs through a real (tiny) Spring context rather than calling the method directly: the point
 * of the test is that the {@code @PostConstruct} is actually <em>invoked</em>. A wrong annotation
 * import ({@code javax} instead of {@code jakarta}) compiles fine and silently never runs, which
 * would leave a disabled-SLA deployment with no boot-time signal at all.
 *
 * <p>No Redis is needed — the callback only reads configuration.
 */
class SlaMonitoringCacheStartupWarningTest {

    private static final String WARN_MARKER = "event=sla.live_tracking.disabled";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class)
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @EnableConfigurationProperties(SlaProperties.class)
    @Import(SlaMonitoringCache.class)
    static class Config {}

    private Logger cacheLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        cacheLogger = (Logger) LoggerFactory.getLogger(SlaMonitoringCache.class);
        appender = new ListAppender<>();
        appender.start();
        cacheLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        cacheLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void warnsOnceAtStartup_whenLiveTrackingDisabled() {
        runner.withPropertyValues("observability.sla.live-tracking.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(SlaMonitoringCache.class);

                    List<ILoggingEvent> warnings = warningsContainingMarker();
                    assertThat(warnings).hasSize(1);
                    assertThat(warnings.get(0).getFormattedMessage())
                            .contains("observability.sla.live-tracking.enabled=false")
                            .contains("hung_runs_do_not_breach_until_completion");
                });
    }

    @Test
    void silentAtStartup_whenLiveTrackingEnabled() {
        runner.withPropertyValues("observability.sla.live-tracking.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(SlaMonitoringCache.class);
                    assertThat(warningsContainingMarker()).isEmpty();
                });
    }

    private List<ILoggingEvent> warningsContainingMarker() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains(WARN_MARKER))
                .toList();
    }
}
