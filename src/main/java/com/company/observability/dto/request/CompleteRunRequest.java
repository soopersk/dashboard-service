package com.company.observability.dto.request;

import com.company.observability.domain.enums.CompletionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompleteRunRequest {
    @NotNull(message = "Reporting date is required")
    @Schema(example = "2026-02-06", description = "Must match the reporting_date sent at start — used for partition-pruned lookup")
    private LocalDate reportingDate;

    @NotNull(message = "End time is required")
    private Instant endTime;

    private CompletionStatus status; // Optional. Defaults to SUCCESS when omitted.
}
