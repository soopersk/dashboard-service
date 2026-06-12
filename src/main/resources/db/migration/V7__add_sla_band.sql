-- V7: Add sla_band column to calculator_runs.
--
-- Context: The 97651d7 ("enhanced SLA") commit retroactively edited V2 to replace
-- sla_breached BOOLEAN with sla_band VARCHAR(20), but existing databases that had
-- already applied V2 only have sla_breached and are missing sla_band.
-- This migration adds the missing column to bring those databases in sync.
-- ADD COLUMN on a partitioned table propagates to all child partitions automatically (PG 12+).

ALTER TABLE calculator_runs
    ADD COLUMN IF NOT EXISTS sla_band VARCHAR(20)
        CHECK (sla_band IS NULL OR sla_band IN ('ON_TIME', 'LATE', 'VERY_LATE'));
