-- Indexes for GET /api/v1/calculators/{calculatorName}/runs (run history / on-demand runs).
-- Both serve queries WITHOUT reporting_date, where partition pruning is impossible: each partition
-- is probed via its local index instead of seq-scanned.

-- Last-N runs with no frequency filter: ORDER BY reporting_date DESC, created_at DESC LIMIT n.
-- calculator_runs_consumer_executions_idx leads with frequency, so it can't serve this without
-- skip-scan (not available in PG17). Ordered partition scan + LIMIT stops after the newest partitions.
CREATE INDEX IF NOT EXISTS calculator_runs_history_idx
    ON calculator_runs (calculator_name, reporting_date DESC, created_at DESC);

-- On-demand lookup by additional_attributes->>'odRunId'. Partial: only on-demand runs carry the
-- attribute, so the index stays tiny and scheduled-run inserts pay no maintenance cost.
-- The query's equality predicate implies IS NOT NULL, so the planner matches this partial index.
CREATE INDEX IF NOT EXISTS calculator_runs_od_run_id_idx
    ON calculator_runs (calculator_name, (additional_attributes ->> 'odRunId'))
    WHERE (additional_attributes ->> 'odRunId') IS NOT NULL;
