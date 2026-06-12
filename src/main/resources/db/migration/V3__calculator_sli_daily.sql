-- Daily aggregate table: SUM-based storage keyed by (calculator_name, frequency, reporting_date).
-- Averages are computed at read time via DailyAggregate helper methods.
-- Rebuilt nightly by DailyAggregationJob (idempotent recompute from calculator_runs).

-- frequency NOT NULL DEFAULT 'DAILY' means a calculator with no explicit frequency writes 'DAILY' transparently.
CREATE TABLE IF NOT EXISTS calculator_sli_daily (
    calculator_name    VARCHAR(255)   NOT NULL,
    frequency          VARCHAR(10)    NOT NULL DEFAULT 'DAILY',
    reporting_date     DATE           NOT NULL,
    total_runs         INT            DEFAULT 0,
    success_runs       INT            DEFAULT 0,
    sla_breaches       INT            DEFAULT 0,
    sum_duration_ms    BIGINT         DEFAULT 0,
    sum_start_min_utc  BIGINT         DEFAULT 0,
    sum_end_min_utc    BIGINT         DEFAULT 0,
    computed_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    PRIMARY KEY (calculator_name, frequency, reporting_date)
);

CREATE INDEX IF NOT EXISTS calculator_sli_daily_profile_idx
    ON calculator_sli_daily (calculator_name, frequency, reporting_date DESC);