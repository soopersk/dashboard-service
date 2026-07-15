-- Realign the latest-estimate partial index with the leniently-populated predicate.
-- findLatestRunEstimatesByName now filters on estimated_end_time IS NOT NULL (a field set
-- on every run that has any history to draw from) instead of expected_duration_ms IS NOT NULL
-- (the strictly minSampleSize-gated field). Postgres won't use the old partial index once the
-- query predicate no longer matches, so the index must be recreated on the new predicate.
DROP INDEX IF EXISTS calculator_runs_latest_estimate_by_name_idx;

CREATE INDEX IF NOT EXISTS calculator_runs_latest_estimate_by_name_idx
    ON calculator_runs (calculator_name, frequency, reporting_date DESC, created_at DESC)
    WHERE estimated_end_time IS NOT NULL;
