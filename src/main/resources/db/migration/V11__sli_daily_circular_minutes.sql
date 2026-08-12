-- Minute-of-day is a cyclic quantity: linear averaging breaks across UTC midnight
-- (23:50 & 00:10 must average to 00:00, not 12:00). Store unit-circle component sums;
-- the mean angle is recovered at read time via atan2. Legacy linear sums are kept as a
-- fallback until the nightly recompute has repopulated the lookback window.
ALTER TABLE calculator_sli_daily
    ADD COLUMN IF NOT EXISTS sum_start_sin DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS sum_start_cos DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS sum_end_sin   DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS sum_end_cos   DOUBLE PRECISION NOT NULL DEFAULT 0;
