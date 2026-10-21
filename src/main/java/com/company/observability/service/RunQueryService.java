package com.company.observability.service;

import com.company.observability.cache.AnalyticsCacheService;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.dto.response.RunHistoryResponse;
import com.company.observability.repository.CalculatorRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;

/**
 * Generic run-history reads (last-N runs, by reporting date, by on-demand {@code odRunId}).
 * Redis-first; entries are evicted by run lifecycle events, so the TTL is only a backstop.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunQueryService {

    static final Duration LIVE_TTL = Duration.ofSeconds(30);
    static final Duration SETTLED_TTL = Duration.ofMinutes(5);

    private final CalculatorRunRepository runRepository;
    private final AnalyticsCacheService cacheService;

    /**
     * @param frequency     null = both frequencies
     * @param reportingDate null = any date (no partition pruning — served by index, see V12)
     * @param odRunId       null = no on-demand filter
     */
    public RunHistoryResponse getRunHistory(String calculatorName, Frequency frequency, LocalDate reportingDate,
                                            String odRunId, int limit, boolean nocache) {
        if (!nocache) {
            RunHistoryResponse cached = cacheService.getRunHistory(calculatorName, frequency, reportingDate, odRunId, limit);
            if (cached != null) {
                return cached;
            }
        }

        RunHistoryResponse response = new RunHistoryResponse(calculatorName,
                runRepository.findRunHistory(calculatorName, frequency, reportingDate, odRunId, limit).stream()
                        .map(RunHistoryResponse.RunEntry::from)
                        .toList());

        cacheService.putRunHistory(calculatorName, frequency, reportingDate, odRunId, limit, response,
                response.anyLive() ? LIVE_TTL : SETTLED_TTL);
        return response;
    }
}
