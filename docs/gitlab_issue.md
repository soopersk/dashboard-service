Title
Fix midnight wraparound in calculator_sli_daily start/end time averaging (circular mean)

Labels: bug, backend, observability, data-quality

Summary
calculator_sli_daily.sum_start_min_utc / sum_end_min_utc average a run's start/end time by linearly summing minute-of-day (0–1439) and dividing by total_runs at read time (CalculatorProfile.fromSums). Minute-of-day is a circular quantity, not a linear one, so this average is wrong for any calculator whose runs cluster near UTC midnight.

Example: two runs at 23:50 (minute 1430) and 00:10 (minute 10):


linear mean   = (1430 + 10) / 2 = 720  →  12:00 (noon)      ✗ wrong
circular mean = atan2(Σsin, Σcos)      →  00:00 (midnight)  ✓ correct
Off by 12 hours. Feeds directly into AnalyticsService.resolveReferenceLines, ExpectedRunsService.placeholder, and CalculatorStateService — any calculator whose typical start/end time is near 00:00 UTC currently gets a badly wrong estimate everywhere.

Previously spec'd as Task P2.1 in docs/plans/2026-07-13-production-hardening-plan.md, never implemented. Full design: docs/plans/2026-08-04-daily-aggregation-circular-mean-and-outlier-filtering.md.

Root cause
calculator_sli_daily stores sums only (single nightly writer, additive rollup — TD-3). Linearly averaging an angle is invalid across the wraparound point. Fix: vector-average via SIN/COS component sums + atan2, which stay linearly additive across the nightly rollup, so the sums-only architecture is preserved.

Proposed fix (summary — full SQL/Java in the linked plan doc)
V11__sli_daily_circular_minutes.sql — additive only: sum_start_sin/cos, sum_end_sin/cos (DOUBLE PRECISION DEFAULT 0), no backfill. Existing linear columns are kept, not repurposed (repurposing would silently corrupt already-stale rows outside the recompute window — up to ~13 months for MONTHLY — with no rebuild to fix them).
recomputeForDateRange adds the 4 SIN/COS sums to the existing INSERT...SELECT.
CalculatorProfile.fromSums gains 4 params, computes averages via new circularMeanMinute(sumSin, sumCos, linearSum, totalRuns) — falls back to the linear average when sumSin==0 && sumCos==0 (legacy row, or the genuine 12h-apart cancellation edge case).
New CalculatorProfile.empty(...) factory replaces ~10 zero-sentinel call sites (avoids an 8-trailing-zero transposition risk).
findRecentExact gets a surgical fix only — duration rounding untouched.
6 profile-read methods add the 4 columns to their SELECT.
Rejected alternatives: reporting-date-relative minutes (double-counts MONTHLY's day offset); repurposing the 2 existing columns as SIN sums (breaks the legacy fallback — silently wrong, not gracefully degraded); Postgres point type (no JdbcTemplate support, doesn't solve the repurposing problem anyway).

Scope
File	Change
db/migration/V11__sli_daily_circular_minutes.sql	New
domain/CalculatorProfile.java	fromSums +4 params, circularMeanMinute(), empty()
repository/DailyAggregateRepository.java	recomputeForDateRange + 6 read methods + findRecentExact
service/CalculatorProfileService.java	3 zero-sentinel calls → empty(...)
DailyAggregateRepositoryJdbcTest.java	New scenarios
Unaffected: DailyAggregationJob/DailyAggregationJobTest, profile-construction tests that bypass fromSums, DailyAggregate/findRecentAggregates/findByReportingDates (confirmed dead code). Outlier/baseline data-quality filtering is a separate issue.

Acceptance Criteria
 V11 migration adds the 4 columns as DOUBLE PRECISION NOT NULL DEFAULT 0; existing linear columns/data untouched
 Nightly rebuild writes all 4 new sums for every aggregate group, alongside the existing linear sums
 Midnight-straddling fix: runs at 23:50 + 00:10 → avgStartMinUtc == 0 (not 720)
 Non-wraparound regression check: mid-day clustered runs produce the same average as before
 Zero-vector edge case: two runs exactly 12h apart → falls back to the linear average, no exception, no garbage from atan2(0,0)
 Legacy rows: pre-migration row (SIN/COS at DEFAULT 0) still returns a valid average via the linear fallback
 All 6 profile-read methods return circularly-correct averages
 findRecentExact returns a circularly-correct average; avgDurationMs rounding (Math.round) provably unchanged
 No zero-sentinel call site left as an 8-trailing-zero fromSums(...) call — all converted to CalculatorProfile.empty(...)
 DailyAggregationJob/DailyAggregationJobTest require no changes, pass unmodified
 mvn test -Dtest=DailyAggregateRepositoryJdbcTest,CalculatorProfileServiceTest,DailyAggregationJobTest passes
 SPRING_PROFILES_ACTIVE=local mvn clean test — full suite green
 Manual check: trigger POST /api/v1/admin/aggregation/recompute, inspect calculator_sli_daily via psql, confirm SIN/COS populated and estimatedStartTime on /batch/runs reflects the corrected time