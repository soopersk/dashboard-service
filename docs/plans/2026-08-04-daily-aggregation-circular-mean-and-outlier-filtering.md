# Daily Aggregation: Circular Mean + Baseline Data-Quality Filtering

## Context

`calculator_sli_daily` is the nightly-rebuilt, sum-only table that backs every SLA
baseline and estimated-start/end read in the service (`docs/daily-aggregation-spec.md`).
Two correctness bugs were flagged for review:

1. **Midnight wraparound.** `sum_start_min_utc`/`sum_end_min_utc` linearly sum
   minute-of-day (0–1439) and divide at read time. Minute-of-day is a *circular*
   quantity — two runs at 23:50 and 00:10 average to ~12:00 (noon), not ~00:00. This
   is already fully spec'd as "Task P2.1" in `docs/plans/2026-07-13-production-hardening-plan.md`
   (circular mean via SIN/COS component sums) but **not implemented** in the current
   code. Verified by reading `DailyAggregateRepository.recomputeForDateRange` directly.
2. **Baseline data-quality.** Two distinct concerns were raised under this heading:
   (a) non-SUCCESS runs (FAILED/TIMEOUT/CANCELLED, typically short-circuited and
   atypically fast) are summed into `sum_duration_ms` alongside genuine SUCCESS
   runs, skewing the baseline low — already spec'd as "Task P1.7" in the same
   hardening-plan doc, also **not implemented**; (b) a single anomalously slow but
   *successful* run permanently skews the baseline until it ages out of the read
   lookback (30d DAILY / ~395d MONTHLY) — no prior spec, raised fresh in this
   review. **Two options are specified in full below** (Option A / Option B) —
   see the recommendation note at the start of that section for the tradeoff.

Both bugs share one root constraint: `calculator_sli_daily` is deliberately **sums,
not raw values or averages** (single nightly writer, additive rollup — this is what
makes profile reads cheap and is explicitly protected by TD-3 / the "two-window
model" in the spec doc). Every fix below preserves that invariant — nothing changes
about the hot read path (`CalculatorProfileService`, the 6 `findProfile*` methods)
beyond the column additions problem 1 requires.

A Plan sub-agent independently reviewed this design against the live source (not
just the spec doc) and confirmed the next free migration number is **V11** (not
V13 — P1.x migrations V11/V12 referenced in the hardening-plan doc haven't landed).

---

## Problem 1: Circular mean for start/end minute-of-day

Adopting the existing P2.1 design (it's architecturally correct — SIN/COS sums are
linearly additive across the day-by-day rollup, unlike percentiles, so they fit the
sums-only architecture exactly the way the existing linear sums do), with three
adjustments found during review:

- **Migration is `V11__sli_daily_circular_minutes.sql`**, not V13.
- **Add `CalculatorProfile.empty(name, freq, runNumber, dim)`** static factory.
  `fromSums` gains 4 params (`sumStartSin/Cos`, `sumEndSin/Cos`), which turns every
  existing zero-sentinel call — `fromSums(name, freq, rn, dim, 0, 0, 0, 0)` — into an
  8-trailing-zero call. That's a real transposition-bug risk at the ~10 call sites in
  `DailyAggregateRepository` and `CalculatorProfileService`; a named `empty(...)`
  factory removes it. Confirmed via grep this clears 3+ real call sites (not
  speculative).
- **`findRecentExact` gets the "surgical" fix**, not a full re-route through
  `fromSums`: keep `AVG(duration_ms)` / `Math.round(...)` exactly as-is (untouched
  dimension of this bug), change only the minute columns to
  `SUM(...)`/`SUM(SIN(...))`/`SUM(COS(...))`, and call a new
  `public static CalculatorProfile.circularMeanMinute(sumSin, sumCos, linearSum, totalRuns)`
  directly in the row-mapper. Routing fully through `fromSums` would silently change
  Tier-2's duration rounding from round-half-up to floor-division — an unrequested
  side effect on code this bug doesn't touch.

**Legacy-row heuristic** (`sumSin==0 && sumCos==0` → fall back to the linear
average): kept as-is, no schema flag added. It has one genuine edge case — two runs
exactly 12h apart cancel to an exact zero vector and would hit the same fallback —
but a zero-magnitude resultant vector means there *is* no well-defined circular mean
for that group (like averaging "north" and "south"), so falling back to the linear
answer there is a valid tie-break, not a wrong answer. Not worth a schema flag to
distinguish from the real "legacy row" case it's meant for.

**Old-column lifecycle after this ships** (the old `sum_start_min_utc` /
`sum_end_min_utc` are never deprecated or frozen in code — every recompute keeps
writing them alongside the new sin/cos sums): rows still inside the recompute
window are rebuilt nightly, so old and new columns are always written together
from the same source rows and never drift apart — the common case going forward.
Rows that age out of the recompute window but stay inside the read lookback
freeze at their last-rebuilt values (old behavior, unchanged by this migration).
Rows that are *already* outside the recompute window the day this migration
deploys never get rebuilt again — the self-heal only retires rows still inside
(or re-entering) the window; already-stale rows keep valid old linear sums
forever but stay at `sumSin==0/sumCos==0` (the migration's `DEFAULT 0`) for the
rest of their life in the read lookback, relying on the zero-sentinel fallback
until they age past it — up to 30 days for DAILY, up to ~13 months for MONTHLY.
Once the full lookback has elapsed since deploy, every row still being read has
gone through a post-migration rebuild, so the old columns become functionally
dead (still computed nightly, never read) barring the 12h-cancellation edge
case above — a safe-to-drop cleanup for a later migration, not in scope here.

**Why the nightly rebuild must keep writing them, not just keep the schema
column.** Confirmed against the live code: `CalculatorProfile.fromSums`
(`CalculatorProfile.java:88-89`) is *currently* the entire implementation of
`avgStartMinUtc`/`avgEndMinUtc` — plain `sumStartMinUtc / totalRuns`. After this
migration it becomes the fallback branch only, taken whenever
`sumSin==0 && sumCos==0`. That branch isn't exclusive to legacy rows — a
freshly-rebuilt row hits it too whenever its runs' start (or end) times cancel to
an exact zero vector (the 12h-symmetry edge case already documented above). If
the new `recomputeForDateRange` stopped writing `sum_start_min_utc`/
`sum_end_min_utc`, that edge case would silently degrade from "valid linear
tie-break" to `0 / totalRuns = 0` (midnight) — wrong, not just imprecise. Keeping
them costs nothing extra: same source rows, same `GROUP BY`, two more `SUM()`
expressions alongside the six already in the `SELECT`. (Separately confirmed via
grep: `DailyAggregate.avgStartMinUtc()`/`avgEndMinUtc()` and the
`findRecentAggregates`/`findByReportingDates` methods that feed them have no
caller outside `DailyAggregateRepository` itself and its JDBC test — dead code,
unaffected by and irrelevant to this decision.)

**Blast radius, confirmed by grep** (nothing beyond this list changes):
`DailyAggregateRepository.java` (6 profile-read methods + `findRecentExact` +
`recomputeForDateRange`), `CalculatorProfile.java`, `CalculatorProfileService.java`
(3 zero-sentinel calls). `DailyAggregationJob`/`DailyAggregationJobTest` are
untouched (still call the 3-arg `recomputeForDateRange`). Test files that build
`CalculatorProfile` via its already-averaged 8-arg constructor
(`RunIngestionServiceTest`, `CalculatorStateServiceTest`, `ExpectedRunsServiceTest`,
`SlaBaselineResolverTest`, etc.) are untouched — they never call `fromSums`.
`DailyAggregate`/`findRecentAggregates`/`findByReportingDates` stay untouched —
confirmed dead (no production caller), out of scope for both problems.

### Alternative considered and rejected: reporting-date-relative minutes

Instead of circular mean, one could anchor "minute of day" to `reporting_date`'s
midnight rather than UTC calendar midnight — a run at 00:12 the day after
`reporting_date` would then be minute 1452, not 12, closing the gap with a 21:55
run (1315) without any trig. Rejected after checking how `avgStartMinUtc` is
actually consumed downstream:

- `CalculatorStateService.java` (~L176-190) and `ExpectedRunsService.java` (~L173)
  resolve **which calendar day** via a wholly separate mechanism
  (`deriveOffsetDays` + `TimeUtils.nextBusinessDay(reportingDate, offsetDays)` —
  this is what already handles MONTHLY's up-to-~15-day lag), then treat
  `avgStartMinUtc` as a pure `[0, 1440)` time-of-day added on top.
  `AnalyticsService.java` (~L189) anchors directly on `reportingDate`.
  `RunIngestionService.java` (~L403) anchors on the actual start's own date. All
  four assume `avgStartMinUtc` never leaves `[0, 1440)`.
- Reporting-date-relative minutes would break that split two ways: (1) it
  double-counts the day offset — for MONTHLY, `executionDate` is already shifted
  up to ~15 days out, so adding a multi-day-relative minute value on top pushes
  the estimate further out again; (2) for a calculator whose day-offset itself
  varies (e.g. sometimes day+3, sometimes day+12), the day component isn't
  linearly averageable any more than minute-of-day is — this just moves the same
  circularity problem up one level instead of solving it.
- A fixed epoch-shift (e.g. "minutes from UTC noon" instead of midnight) would
  stay bounded and avoid the double-count, but only works if every calculator's
  schedule avoids the new cut point — an unverifiable, silently-breakable
  assumption on a multi-tenant platform with unknown per-calculator schedules.
  Circular mean has no such assumption: vector arithmetic on the full circle has
  no cut point to place at all.

Circular mean is the only option that (a) is correct for any schedule/cluster
location without assumptions and (b) requires zero changes to the four existing
day-resolution call sites, since its output stays a conventional `[0, 1440)`
time-of-day value. Confirmed as the right fix, not just the simpler one.

### Alternative considered and rejected: reuse existing columns instead of adding 4

Circular mean needs two independent linear-additive components per angle
(sin sum + cos sum) to run `atan2` — irreducible, since one scalar can't recover
a mean angle. Today there's exactly one column per angle
(`sum_start_min_utc`, `sum_end_min_utc`, confirmed `BIGINT` in `V3`), so at least
one *new* column per angle is unavoidable. The real question is whether the
existing 2 columns can be repurposed as the sin-sums (adding only 2 new cos
columns) instead of adding 4 wholly new columns.

Rejected: this breaks the legacy-row fallback that makes the migration safe with
no backfill. Rows outside the write (recompute) window but inside the read
(lookback) window are never rebuilt by the nightly job — for MONTHLY that's up
to ~13 months of untouched rows after this ships (20-day recompute window vs.
~395-day read lookback via `SlaProperties.lookbackDays`). Those rows currently
hold real, valid linear minute sums. If `sum_start_min_utc` is redefined to mean
"sum of sin" going forward:

- A legacy row's old linear sum (e.g. `47000` accumulated from 30 runs) gets
  silently reinterpreted as a sin-sum. `atan2(47000, 0)` (cos defaults to 0 on
  the new column) resolves to a fixed ~90°-equivalent angle — a confidently
  *wrong* `avgStartMinUtc`, not a degraded-but-sane one.
- With 4 **new**, additive `DEFAULT 0` columns and the old columns left
  untouched, the same legacy row reads `sumSin==0 && sumCos==0` → the existing
  heuristic cleanly falls back to the still-valid old linear average. Wrong in
  the pre-existing way (midnight wraparound), never newly broken.

So the 4-column version isn't overengineering — it's what makes "no backfill,
self-heal via nightly rebuild" true rather than aspirational. Repurposing trades
2 columns of storage (trivial on an aggregate table) for months of silently
wrong reference times on any row that hasn't been rebuilt yet.

One genuine way to cut 4 new columns to 2 does exist: Postgres's native `point`
type (`(x, y)` in a single column) could pack sin+cos together. Not recommended
here — Spring's `JdbcTemplate` has no first-party `point` mapping (would need
custom `PGpoint` handling, at odds with this codebase's plain-JDBC style), and
it doesn't solve the repurposing problem above; the 2 columns would still be
*new*, not reused, making this a minor storage optimization, not a
simplification.

### `V11__sli_daily_circular_minutes.sql`

```sql
ALTER TABLE calculator_sli_daily
    ADD COLUMN IF NOT EXISTS sum_start_sin DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS sum_start_cos DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS sum_end_sin   DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS sum_end_cos   DOUBLE PRECISION NOT NULL DEFAULT 0;
```

Purely additive, `DEFAULT 0` — no risk to existing columns, no backfill needed (the
nightly rebuild self-heals the trailing window; older rows outside it age out of the
read lookback the same way the run_number fan-out transition already does).

### Read-path changes (shared by both Problem 2 options below)

Each of `findProfile`, `findAllProfiles`, `findProfileByRunNumber`,
`findAllProfilesByRunNumber`, `findProfileByRunNumberAndDimension`,
`findAllProfilesByRunNumberAndDimension` adds 4 columns
(`SUM(sum_start_sin) AS sum_start_sin`, etc.) to its SELECT and passes them into the
grown `fromSums(...)` call. `WHERE`/`GROUP BY` on these six methods are untouched.
This part of the change is identical regardless of which Problem 2 option is chosen.

---

## Problem 2: two options, both fully specified

**Recommendation, read before picking:** Option A is recommended for this pass.
Option B is preserved here in full, ready to implement as-is, in case the team
wants maximum protection now or wants to revisit later with production evidence.

The two options address different failure modes:
- **Option A (SUCCESS-only filtering, P1.7)** removes a *confirmed, structural*
  contamination source: short-circuited FAILED/TIMEOUT/CANCELLED durations. It's a
  one-line WHERE-clause change, no new config, no new dependencies, ~1 new test.
- **Option B (statistical outlier filtering)** additionally guards against a
  *hypothesized, not-yet-observed* failure mode: a genuinely successful run that's
  abnormally slow for environmental reasons (slow dependency, DB contention,
  one-off data-volume spike) — a status filter can't catch this by definition,
  since the run's status is SUCCESS. It costs roughly 15x Option A's implementation
  and test surface (three new SQL CTEs, a reintroduced `SlaProperties` dependency,
  three new config knobs, ~11 additional test scenarios) for a narrower, harder to
  observe, and probabilistic (tunable) benefit.

**Residual risk if only Option A is implemented, quantified:** a single anomalous
day is diluted across the whole lookback-window average, not read in isolation.
For DAILY (30-day lookback), one 10x-duration anomaly among ~30 samples inflates
the blended average by roughly `9/30 ≈ 30%`, clearing within 30 days as the bad
day ages out — bounded, self-healing. For MONTHLY (~13 EOM samples in its
lookback), the same anomaly inflates the average by roughly `9/13 ≈ 69%` and takes
**up to ~13 months** to fully clear — the same self-heal timescale already
documented elsewhere in this system for the run_number transition. MONTHLY is the
meaningfully weaker case if Option B is skipped.

**When to revisit Option B if Option A ships alone:** production data shows a
MONTHLY (or DAILY) baseline visibly distorted by a confirmed slow-but-successful
run — real data to calibrate `iqrMultiplier`/`minSampleSize` against beats
guessing at defaults now.

Both options are written as complete replacements for `recomputeForDateRange`'s
body (each already includes Problem 1's sin/cos columns, since Problem 1 ships
regardless of which Problem 2 option is chosen) — pick one, not both, unless
explicitly combining (Option B's design is independent of Option A's status
filter by construction — see its reference-population note below — so nothing
stops running both together for maximum protection; that just means implementing
Option B's SQL as specified, which already restricts its *reference population*
to SUCCESS-only even without Option A's filter on the main written rows).

### Option A: SUCCESS-only baseline filtering (P1.7)

One-line change: add `AND status = 'SUCCESS'` to `recomputeForDateRange`'s WHERE
clause. Aligns the main aggregate with what `findRecentExact` (Tier-2) already
does — it has filtered `status = 'SUCCESS'` all along, so this closes a
long-standing inconsistency between the two tiers, not just a new fix.

Consequence: `success_runs` becomes definitionally equal to `total_runs` (both
count the same, already-SUCCESS-only, rows) — kept as separate columns per the
existing schema, but both computed as plain `COUNT(*)` with a comment noting the
equality, matching the hardening-plan doc's own note: *"success_runs is therefore
equal to total_runs; failure analytics must read calculator_runs directly."*

No config, no new dependency.

#### Option A — SQL shape (`recomputeForDateRange`, Problem 1 + Option A, one statement)

```sql
INSERT INTO calculator_sli_daily (
    calculator_name, frequency, reporting_date, run_number, dimension_value,
    total_runs, success_runs, sla_breaches,
    sum_duration_ms, sum_start_min_utc, sum_end_min_utc,
    sum_start_sin, sum_start_cos, sum_end_sin, sum_end_cos,
    computed_at
)
SELECT
    calculator_name, frequency, reporting_date,
    COALESCE(run_number, 'ALL')       AS run_number,
    COALESCE(region, run_type, 'ALL') AS dimension_value,
    COUNT(*),                              -- total_runs
    COUNT(*),                              -- success_runs: == total_runs, WHERE already SUCCESS-only
    COUNT(*) FILTER (WHERE sla_breached),  -- a SUCCESS run can still be late
    COALESCE(SUM(duration_ms), 0),
    COALESCE(SUM(start_min), 0),
    COALESCE(SUM(end_min), 0),
    COALESCE(SUM(SIN(2*PI()*start_min/1440.0)), 0),
    COALESCE(SUM(COS(2*PI()*start_min/1440.0)), 0),
    COALESCE(SUM(SIN(2*PI()*end_min/1440.0)), 0),
    COALESCE(SUM(COS(2*PI()*end_min/1440.0)), 0),
    NOW()
FROM (
    SELECT
        calculator_name, frequency, reporting_date, run_number, region, run_type, sla_breached,
        duration_ms,
        (EXTRACT(HOUR FROM start_time AT TIME ZONE 'UTC') * 60 +
         EXTRACT(MINUTE FROM start_time AT TIME ZONE 'UTC'))::int AS start_min,
        (EXTRACT(HOUR FROM end_time AT TIME ZONE 'UTC') * 60 +
         EXTRACT(MINUTE FROM end_time AT TIME ZONE 'UTC'))::int   AS end_min
    FROM calculator_runs
    WHERE end_time IS NOT NULL
      AND status = 'SUCCESS'                          -- Option A (P1.7)
      AND frequency = :frequency
      AND reporting_date BETWEEN :from AND :to
    -- end_time IS NOT NULL is already guaranteed above, so the CASE-guarded end_min
    -- expression from the current query is vestigial in this restructure and dropped.
) r
GROUP BY calculator_name, frequency, reporting_date,
         COALESCE(run_number, 'ALL'),
         COALESCE(region, run_type, 'ALL')
```

No new bind parameters beyond the existing `:frequency`/`:from`/`:to`, no new
repository dependencies, no config.

#### Option A — Files to change

| File | Change |
|---|---|
| `src/main/resources/db/migration/V11__sli_daily_circular_minutes.sql` | New — 4 columns (Problem 1, shared) |
| `src/main/java/com/company/observability/domain/CalculatorProfile.java` | Problem 1 changes (shared) |
| `src/main/java/com/company/observability/repository/DailyAggregateRepository.java` | Problem 1 read-path changes (shared) + `recomputeForDateRange` → SQL above (adds `status='SUCCESS'` filter, no new dependencies); javadoc updated per P1.7's note |
| `src/main/java/com/company/observability/service/CalculatorProfileService.java` | Problem 1 changes (shared) |
| `src/test/java/com/company/observability/repository/DailyAggregateRepositoryJdbcTest.java` | Problem 1 tests (shared) + Option A tests below |

**Unaffected:** `DailyAggregationJob.java`/`DailyAggregationJobTest.java`,
`AggregationProperties.java`/`application.yml` (no new config under this option).

#### Option A — Test plan additions

1. `recompute_excludesNonSuccessRunsFromBaselineSums` (already spec'd in the
   hardening-plan doc, reused here): one SUCCESS run (100_000ms) + one FAILED run
   (5_000ms), same group → `profile.totalRuns()==1`, `avgDurationMs()==100_000L`
   (not diluted by the FAILED row's short duration).
2. `success_runs == total_runs` after the fix, for a mixed SUCCESS/FAILED fixture
   (raw-column query, not through `fromSums`).
3. Combined regression: one recompute pass with a FAILED-run-dilution scenario
   *and* a separate midnight-straddling pair in the same run → both hold
   simultaneously.

---

### Option B: Statistical outlier filtering (Tukey IQR) — full original design

**Signal:** duration only. Not start/end-of-day too — and this isn't just simpler,
it's required: start/end are circular quantities (Problem 1's whole premise), so a
plain linear IQR fence on raw minute-of-day would reintroduce the exact
midnight-wraparound bug being fixed in Problem 1. Duration is the only one of the
three measures that's a proper linear scalar suitable for a linear IQR fence.

**Method:** Tukey IQR fences (`Q1 - k×IQR`, `Q3 + k×IQR`), computed per
`(calculator_name, frequency, run_number, dimension_value)` group via
`PERCENTILE_CONT` — standard, robust to skew (duration distributions are typically
right-skewed), doesn't fight its own outliers the way mean±stddev does.

**Reference population for Q1/Q3:** `status = 'SUCCESS'` rows only, within a
per-frequency bounds window — `SlaProperties.lookbackDays(DAILY)` (30d) for DAILY,
the existing 20-day recompute window for MONTHLY (no new config — reuses two
already-existing windows). Restricting the *reference* to SUCCESS keeps
short-circuited FAILED/TIMEOUT durations from biasing what "normal" means for the
group. **This reference-population filter is independent of whether Option A's
filter is also applied to the main written rows** — Option B's own `bounds_source`
CTE always filters SUCCESS, regardless. Note the bounds window can be (and for
DAILY, is) wider than the `[:from, :to]` range of rows actually being written — it
only widens what "normal" is computed from, not what gets rewritten into
`calculator_sli_daily`.

Why per-frequency, and why these specific windows: MONTHLY calculators execute
daily for the first ~10–15 days of the following month, so their runs all share
one EOM `reporting_date` — the existing 20-day recompute window already holds a
workable sample (~10–15 points) with zero extra I/O, so it's reused as-is. DAILY's
narrow 7-day recompute window is too thin on its own (a single-run/day calculator
caps at 7 samples, no margin for a holiday or a bad week), so DAILY instead reuses
the existing `SlaProperties.lookbackDays(DAILY)` (30 days) — already paid for
conceptually (the documented SLA-relevance lookback), and reading 30 contiguous
days instead of 7 is a trivial linear cost, unlike reusing MONTHLY's 395-day
lookback would be (that would revive the TD-8 partition-scan cost for no benefit,
since MONTHLY's own window is already adequate).

**Minimum-sample guard:** with n≤2, Q1=Q3 and any second value looks like an
outlier. With DAILY drawing on 30 days and MONTHLY on ~10–15 samples, default
**`min-sample-size = 5`** has real margin on both sides (below that, skip
filtering for the group entirely that night; self-correcting since the whole table
rebuilds nightly).

**Uniform exclusion, one denominator:** a run is flagged outlier or not as a single
boolean (duration-based). That same boolean excludes the run from *all* eight
summed measures for its group-row: `total_runs`, `success_runs`, `sla_breaches`,
`sum_duration_ms`, `sum_start_min_utc`, `sum_end_min_utc`, and — combined with
Problem 1 — `sum_start_sin/cos`, `sum_end_sin/cos`. This is what keeps
`CalculatorProfile.fromSums`'s single `totalRuns` divisor valid; per-measure
outlier detection (duration vs. start vs. end independently) was considered and
rejected — it would need separate denominators per measure and, per the point
above, isn't even methodologically sound for the two circular measures without a
much bigger circular-statistics lift.

`success_runs`/`sla_breaches` also exclude outliers for definitional consistency
(so `success_runs` can't exceed `total_runs`), though `sla_breaches` is confirmed
dead in the live read path today (no `fromSums` param, not selected by any live
profile query) — this is cheap hygiene, not a functional fix.

**No changes to `findRecentExact`:** it reads only the last 5 raw runs; the
min-sample-size guard (5) would always skip filtering there. Adding IQR logic to a
5-row sample is dead code.

#### Option B — SQL shape (`recomputeForDateRange`, Problem 1 + Option B, one statement)

```sql
WITH runs AS (
    -- The narrow window: exactly today's rows-to-write, unchanged from the current query.
    -- (Add "AND status = 'SUCCESS'" here too if combining with Option A — see the note above.)
    SELECT
        calculator_name, frequency, reporting_date, status, sla_breached, duration_ms,
        COALESCE(run_number, 'ALL')       AS run_number,
        COALESCE(region, run_type, 'ALL') AS dimension_value,
        (EXTRACT(HOUR FROM start_time AT TIME ZONE 'UTC') * 60 +
         EXTRACT(MINUTE FROM start_time AT TIME ZONE 'UTC'))::int AS start_min,
        (EXTRACT(HOUR FROM end_time AT TIME ZONE 'UTC') * 60 +
         EXTRACT(MINUTE FROM end_time AT TIME ZONE 'UTC'))::int   AS end_min
    FROM calculator_runs
    WHERE end_time IS NOT NULL
      AND frequency = :frequency
      AND reporting_date BETWEEN :from AND :to
    -- end_time IS NOT NULL is already guaranteed here, so the CASE-guarded end_min
    -- expression from the current query is vestigial in this restructure and dropped.
),
bounds_source AS (
    -- The reference window for "what's normal": wider than `runs` for DAILY
    -- (:boundsFrom = today - SlaProperties.lookbackDays(DAILY) = 30d), identical to
    -- `runs`' own window for MONTHLY (:boundsFrom = :from, so this is a no-op widening
    -- — Postgres just re-reads the same 20-day range). SUCCESS-only, per above.
    SELECT
        calculator_name,
        COALESCE(run_number, 'ALL')       AS run_number,
        COALESCE(region, run_type, 'ALL') AS dimension_value,
        duration_ms
    FROM calculator_runs
    WHERE end_time IS NOT NULL
      AND status = 'SUCCESS'
      AND frequency = :frequency
      AND reporting_date BETWEEN :boundsFrom AND :to
),
bounds AS (
    SELECT calculator_name, run_number, dimension_value,
           COUNT(*)                                                  AS sample_size,
           PERCENTILE_CONT(0.25) WITHIN GROUP (ORDER BY duration_ms) AS q1,
           PERCENTILE_CONT(0.75) WITHIN GROUP (ORDER BY duration_ms) AS q3
    FROM bounds_source
    GROUP BY calculator_name, run_number, dimension_value
),
classified AS (
    SELECT r.*,
           (:outlierEnabled
             AND b.sample_size >= :minSampleSize
             AND (b.q3 - b.q1) > 0
             AND (r.duration_ms < b.q1 - :iqrMultiplier * (b.q3 - b.q1)
                  OR r.duration_ms > b.q3 + :iqrMultiplier * (b.q3 - b.q1))
           ) IS TRUE AS is_outlier
    FROM runs r
    LEFT JOIN bounds b
      ON b.calculator_name = r.calculator_name
     AND b.run_number      = r.run_number
     AND b.dimension_value = r.dimension_value
)
INSERT INTO calculator_sli_daily (
    calculator_name, frequency, reporting_date, run_number, dimension_value,
    total_runs, success_runs, sla_breaches,
    sum_duration_ms, sum_start_min_utc, sum_end_min_utc,
    sum_start_sin, sum_start_cos, sum_end_sin, sum_end_cos,
    computed_at
)
SELECT
    calculator_name, frequency, reporting_date, run_number, dimension_value,
    COUNT(*) FILTER (WHERE NOT is_outlier),
    COUNT(*) FILTER (WHERE NOT is_outlier AND status = 'SUCCESS'),
    COUNT(*) FILTER (WHERE NOT is_outlier AND sla_breached),
    COALESCE(SUM(duration_ms) FILTER (WHERE NOT is_outlier), 0),
    COALESCE(SUM(start_min)   FILTER (WHERE NOT is_outlier), 0),
    COALESCE(SUM(end_min)     FILTER (WHERE NOT is_outlier), 0),
    COALESCE(SUM(SIN(2*PI()*start_min/1440.0)) FILTER (WHERE NOT is_outlier), 0),
    COALESCE(SUM(COS(2*PI()*start_min/1440.0)) FILTER (WHERE NOT is_outlier), 0),
    COALESCE(SUM(SIN(2*PI()*end_min/1440.0))   FILTER (WHERE NOT is_outlier), 0),
    COALESCE(SUM(COS(2*PI()*end_min/1440.0))   FILTER (WHERE NOT is_outlier), 0),
    NOW()
FROM classified
GROUP BY calculator_name, frequency, reporting_date, run_number, dimension_value
```

`:outlierEnabled`/`:minSampleSize`/`:iqrMultiplier` are bound from the new
`AggregationProperties.Outlier` config (below). `:boundsFrom` is computed in Java,
per call, from configs that already exist — no new window-sizing knob:

```java
LocalDate boundsFrom = frequency == Frequency.DAILY
        ? toInclusive.minusDays(slaProperties.lookbackDays(Frequency.DAILY))
        : fromInclusive;   // MONTHLY: identical to the narrow window, no widening
```

A single statement handles both the enabled and disabled cases (disabled just means
`is_outlier` collapses to false for every row), so no Java-side SQL-variant switch
is needed — the only added cost of `enabled=false` is the (cheap) `bounds`
computation still running. This repository method needs both `AggregationProperties`
(outlier config) and `SlaProperties` (DAILY's `lookbackDays`) as new dependencies.

#### Option B — Config: `AggregationProperties.java`

```java
private Outlier outlier = new Outlier();

@Getter @Setter
public static class Outlier {
    private boolean enabled = true;
    private double iqrMultiplier = 1.5;
    private int minSampleSize = 5;
}
```

`application.yml`, under `observability.aggregation`:

```yaml
outlier:
  enabled: true
  iqr-multiplier: 1.5
  min-sample-size: 5
```

Matches the existing `Daily`/`RecomputeWindow` nested-class pattern in this exact
file — three fields, no speculative knobs, consistent with how `observability.sla.*`
already externalizes comparable tunables (`duration-threshold-percent`,
`late-band-minutes`).

#### Option B — Files to change

| File | Change |
|---|---|
| `src/main/resources/db/migration/V11__sli_daily_circular_minutes.sql` | New — 4 columns (Problem 1, shared) |
| `src/main/java/com/company/observability/domain/CalculatorProfile.java` | Problem 1 changes (shared) |
| `src/main/java/com/company/observability/repository/DailyAggregateRepository.java` | Problem 1 read-path changes (shared) + new `AggregationProperties` + `SlaProperties` deps; `recomputeForDateRange` → SQL above |
| `src/main/java/com/company/observability/service/CalculatorProfileService.java` | Problem 1 changes (shared) |
| `src/main/java/com/company/observability/config/AggregationProperties.java` | New `Outlier` nested class |
| `src/main/resources/application.yml` | New `observability.aggregation.outlier.*` keys |
| `src/test/java/com/company/observability/repository/DailyAggregateRepositoryJdbcTest.java` | `@TestConfiguration` needs an `AggregationProperties` bean (low `min-sample-size` for small fixtures); Problem 1 tests (shared) + Option B tests below |

**Unaffected:** `DailyAggregationJob.java`/`DailyAggregationJobTest.java` (still
call the unchanged 3-arg `recomputeForDateRange`).

#### Option B — Test plan additions

1. Clear outlier excluded: ≥5 similar-duration SUCCESS runs in-window + one
   extreme-duration run → excluded from `sum_duration_ms`/`total_runs`.
2. Guard respected: same shape but <5 SUCCESS runs in-window → not excluded.
3. `outlier.enabled=false` → nothing excluded regardless of shape.
4. Denominator consistency: the excluded run also has an atypical start time →
   `avgStartMinUtc`/`avgEndMinUtc` reflect only the kept runs (Problem 1 × Option B
   interaction).
5. `success_runs`/`sla_breaches` exclusion: outlier run is `status='SUCCESS'` and
   `sla_breached=true` → raw-column query shows both exclude it.
6. Reference is SUCCESS-only: seed short-duration FAILED-run noise alongside
   steady SUCCESS history; a genuine SUCCESS-side outlier is still correctly
   flagged (proves the FAILED noise didn't skew Q1/Q3).
7. Per-group isolation: two dimension_value groups (regions) for one calculator —
   an outlier in region A doesn't affect region B's bounds or counts.
8. Multiplier takes effect: a borderline run is excluded at `iqrMultiplier=1.5`
   but retained at `iqrMultiplier=3.0`.
9. Combined regression: one recompute pass with both a midnight-straddling pair
   and a separate duration outlier in the same group → both hold simultaneously.
10. DAILY bounds window is wider than the narrow window: seed enough SUCCESS
    history in days 8–30 (outside `[:from,:to]` but inside
    `SlaProperties.lookbackDays(DAILY)`) to clear `min-sample-size`, plus one
    outlier inside `[:from,:to]` — outlier is still excluded even though the
    narrow window alone wouldn't have had enough samples to compute bounds.
11. MONTHLY bounds window equals its narrow window (no widening): a MONTHLY
    group's bounds are unaffected by SUCCESS runs older than the 20-day recompute
    window, confirming no accidental widening happened for MONTHLY.

---

## Verification (either option)

- `mvn test -Dtest=DailyAggregateRepositoryJdbcTest,CalculatorProfileServiceTest,DailyAggregationJobTest` → full pass, including all new scenarios for Problem 1 and whichever Problem 2 option is implemented.
- `SPRING_PROFILES_ACTIVE=local mvn clean test` → full suite green (confirms no blast-radius surprises beyond the files listed).
- Manual sanity: `SPRING_PROFILES_ACTIVE=local mvn spring-boot:run`, trigger
  `POST /api/v1/admin/aggregation/recompute?from=<date>&to=<date>` against local
  seed data spanning a midnight-straddling calculator and (Option A) a calculator
  with a mix of SUCCESS/FAILED runs, or (Option B) a calculator with a planted
  extreme-duration SUCCESS run; inspect `calculator_sli_daily` rows directly via
  `docker exec -it observability-postgres psql` to confirm sin/cos columns are
  populated and the chosen option's exclusion/filter took effect.
