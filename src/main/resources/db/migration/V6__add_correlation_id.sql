-- Adds correlation_id to calculator_runs to support grouping split runs into one logical run.
-- Airflow sets the same correlationId on every physical split that belongs to one logical run.
-- NULL means the run is standalone and needs no grouping.
-- Immutable after first INSERT (omitted from ON CONFLICT DO UPDATE in repository).

ALTER TABLE calculator_runs ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(100);