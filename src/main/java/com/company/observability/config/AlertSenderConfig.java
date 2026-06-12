package com.company.observability.config;

import com.company.observability.alert.AlertSender;
import com.company.observability.alert.StructuredLogAlertSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

@Configuration
public class AlertSenderConfig {

    private static final Set<String> KNOWN_CHANNELS = Set.of("logging");

    @Bean
    public AlertSender alertSender(
            @Value("${observability.alerts.channel:logging}") String channel,
            StructuredLogAlertSender structuredLogAlertSender) {

        if (!KNOWN_CHANNELS.contains(channel)) {
            throw new IllegalStateException(
                    "Unknown alert channel: '" + channel + "'. Known channels: " + KNOWN_CHANNELS);
        }

        return structuredLogAlertSender;
    }
}
