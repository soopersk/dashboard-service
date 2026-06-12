-- Consumer-focused index set for calculator_name-keyed query paths.
-- Parent-table indexes are used for calculator_runs partition compatibility.

CREATE INDEX IF NOT EXISTS calculator_runs_consumer_batch_idx
    ON calculator_runs (reporting_date, frequency, calculator_name, run_number, created_at);

CREATE INDEX IF NOT EXISTS calculator_runs_consumer_executions_idx
    ON calculator_runs (calculator_name, frequency, reporting_date DESC, run_number, created_at DESC);

CREATE INDEX IF NOT EXISTS calculator_runs_latest_estimate_by_name_idx
    ON calculator_runs (calculator_name, frequency, reporting_date DESC, created_at DESC)
    WHERE expected_duration_ms IS NOT NULL;