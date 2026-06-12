package com.company.observability;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableCaching
@EnableScheduling
@OpenAPIDefinition(
        info = @Info(
                title = "Observability Service API",
                version = "1.0.0",
                description = "Calculator runtime observability and SLA monitoring service"
        )
)
@SecurityScheme(
        name = "basicAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "basic"
)
public class ObservabilityServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ObservabilityServiceApplication.class, args);
    }
}
