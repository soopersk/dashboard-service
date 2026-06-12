package com.company.observability.domain;

import com.company.observability.domain.enums.AlertStatus;
import com.company.observability.domain.enums.BreachType;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "breachId")
@ToString
public class SlaBreachEvent {
    private Long breachId;
    private String runId;
    private String calculatorId;
    private String calculatorName;
    private String tenantId;
    private LocalDate reportingDate;
    private BreachType breachType;
    private Long expectedValue;
    private Long actualValue;
    private Boolean alerted;
    private Instant alertedAt;
    private AlertStatus alertStatus;
    private Integer retryCount;
    private String lastError;
    private Instant createdAt;
    // Transient — not persisted, set at alert time to disambiguate expectedValue/actualValue units
    private String expectedUnit;
    private String actualUnit;
}
