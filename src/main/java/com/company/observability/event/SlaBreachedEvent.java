package com.company.observability.event;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.SlaEvaluationResult;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SlaBreachedEvent {
    private final CalculatorRun run;
    private final SlaEvaluationResult result;
}
