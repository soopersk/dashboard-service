-- Add run_number dimension to calculator_sli_daily so profiles can be scoped per cycle.
-- Capital calculators run in two cycles (run_number='1'=T+1, '2'=T+2) with different
-- start times; a blended profile gives a misleading estimatedStartTime for NOT_STARTED entries.
-- Null-run_number calculators (modelled-exposure, gemini-hedge) are fanned into BOTH buckets
-- by the nightly DailyAggregationJob two-pass UNION query.
--
-- Existing rows default to '2' (T+2), matching COALESCE(run_number, '2') convention.

ALTER TABLE calculator_sli_daily
    ADD COLUMN IF NOT EXISTS run_number VARCHAR(10) NOT NULL DEFAULT '2';

-- Replace 3-column PK with 4-column PK that includes run_number.
ALTER TABLE calculator_sli_daily DROP CONSTRAINT IF EXISTS calculator_sli_daily_pkey;
ALTER TABLE calculator_sli_daily
    ADD PRIMARY KEY (calculator_name, frequency, reporting_date, run_number);

-- Update the profile index to include run_number for efficient per-cycle reads.
DROP INDEX IF EXISTS calculator_sli_daily_profile_idx;
CREATE INDEX IF NOT EXISTS calculator_sli_daily_profile_idx
    ON calculator_sli_daily (calculator_name, frequency, run_number, reporting_date DESC);
