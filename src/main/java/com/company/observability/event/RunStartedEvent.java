package com.company.observability.event;

import com.company.observability.domain.CalculatorRun;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class RunStartedEvent {
    private final CalculatorRun run;
}