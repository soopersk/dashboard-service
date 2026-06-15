package com.company.observability.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.annotation.RequestScope;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

@Configuration
public class ClockConfig {

    @Bean
    @RequestScope
    Clock slaReferenceClock(HttpServletRequest request, SlaProperties slaProps) {
        if (slaProps.isAllowReferenceTime()) {
            String asOf = request.getParameter("as_of");
            if (asOf != null && !asOf.isBlank()) {
                try {
                    return Clock.fixed(Instant.parse(asOf), ZoneOffset.UTC);
                } catch (DateTimeParseException ignored) {
                    // invalid ISO instant — fall through to system clock
                }
            }
        }
        return Clock.systemUTC();
    }
}
