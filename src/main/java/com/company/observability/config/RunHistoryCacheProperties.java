package com.company.observability.config;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Run-history cache ({@code GET /calculators/{name}/runs}). Snapshots are generation-invalidated
 * by run lifecycle events; the TTLs only bound staleness if an invalidation is lost.
 */
@Component
@ConfigurationProperties(prefix = "observability.run-history-cache")
@Validated
@Getter
@Setter
public class RunHistoryCacheProperties {

    /** false → reads go straight to the DB and nothing is written. Invalidation still runs. */
    private boolean enabled = true;

    /** Snapshot that is empty or contains a RUNNING run. */
    @DurationMin(seconds = 1)
    private Duration transientTtl = Duration.ofSeconds(10);

    /** Non-empty snapshot whose runs are all terminal. */
    @DurationMin(seconds = 1)
    private Duration terminalTtl = Duration.ofMinutes(5);

    /** Token lifetime since its last invalidation or seed (reads don't refresh it). Keep above terminalTtl (efficiency only). */
    @DurationMin(seconds = 1)
    private Duration generationTtl = Duration.ofMinutes(15);
}
