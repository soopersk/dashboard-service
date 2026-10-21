package com.company.observability.service;

import com.company.observability.cache.CalculatorRunHistoryCacheService;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.dto.response.RunHistoryResponse;
import com.company.observability.repository.CalculatorRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Generic run-history reads (last-N runs, by reporting date, by on-demand {@code odRunId}).
 * Redis-first via the generation-invalidated {@link CalculatorRunHistoryCacheService}.
 */
@Service
@RequiredArgsConstructor
public class RunQueryService {

    private final CalculatorRunRepository runRepository;
    private final CalculatorRunHistoryCacheService historyCache;

    /**
     * @param frequency     null = both frequencies
     * @param reportingDate null = any date (no partition pruning — served by index, see V12)
     * @param odRunId       null = no on-demand filter
     * @param nocache       true = skip the cache read and refresh the snapshot from the DB
     */
    public RunHistoryResponse getRunHistory(String calculatorName, Frequency frequency, LocalDate reportingDate,
                                            String odRunId, int limit, boolean nocache) {
        return historyCache.getOrLoad(calculatorName, frequency, reportingDate, odRunId, limit, nocache,
                () -> new RunHistoryResponse(calculatorName,
                        runRepository.findRunHistory(calculatorName, frequency, reportingDate, odRunId, limit).stream()
                                .map(RunHistoryResponse.RunEntry::from)
                                .toList()));
    }
}
