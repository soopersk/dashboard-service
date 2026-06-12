-- dimension_value = COALESCE(region, run_type, 'ALL') — region and run_type are mutually
-- exclusive per calculator; 'ALL' = runs with neither (single-run calcs).
ALTER TABLE calculator_sli_daily
    ADD COLUMN IF NOT EXISTS dimension_value VARCHAR(20) NOT NULL DEFAULT 'ALL';
ALTER TABLE calculator_sli_daily DROP CONSTRAINT IF EXISTS calculator_sli_daily_pkey;
ALTER TABLE calculator_sli_daily
    ADD PRIMARY KEY (calculator_name, frequency, reporting_date, run_number, dimension_value);
DROP INDEX IF EXISTS calculator_sli_daily_profile_idx;
CREATE INDEX IF NOT EXISTS calculator_sli_daily_profile_idx
    ON calculator_sli_daily (calculator_name, frequency, run_number, dimension_value, reporting_date DESC);
