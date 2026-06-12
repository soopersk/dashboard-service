package com.company.observability.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Calculator configuration bound to {@code observability.calculator.*}.
 *
 * <ul>
 *   <li>{@code aliases} — UI alias → real {@code calculator_name} values (env-specific,
 *       defined in {@code application-dev/uat/prod.yml}).</li>
 *   <li>{@code regions} — alias → declared region values for region-dimensioned calculators
 *       (env-invariant, defined once in {@code application.yml}).</li>
 *   <li>{@code runTypes} — alias → declared run-type values for type-dimensioned calculators
 *       (env-invariant, defined once in {@code application.yml}).</li>
 * </ul>
 *
 * An alias appears in at most one of {@code regions}/{@code runTypes}; aliases in neither are
 * passed through unpadded.
 */
@Component
@ConfigurationProperties(prefix = "observability.calculator")
@Getter
@Setter
public class CalculatorProperties {

    private Map<String, List<String>> aliases = new HashMap<>();
    private Map<String, List<String>> regions = new HashMap<>();
    private Map<String, List<String>> runTypes = new HashMap<>();
}
