package com.company.observability.domain;

import com.company.observability.domain.enums.SlaBand;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SlaEvaluationResult {
    private SlaBand band;
    private String reason;

    /** Convenience: a run is breached when it has a non-null, non-ON_TIME band, or when the
     *  caller signals failure (FAILED/TIMEOUT) independently of timing. */
    public boolean isBreached() {
        return band != null && band.isBreached();
    }
}
