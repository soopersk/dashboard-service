# Fix missing estimated start/end for thin-history calculators (e.g. `marketriskrwacalcdev`)

## Context

`GET /api/v1/calculators/batch/runs` returns a `NOT_STARTED` entry with no
`estimatedStartTime`/`estimatedEndTime` (or, in the worst case, an empty entry with no
run at all) for calculators with too little history — reported for
`marketriskrwacalcdev` (MONTHLY, run-number-agnostic, dimension-agnostic /
`Dimension.NONE`). Confirmed data:

| dimension_value | reporting_date | run_number | total_runs |
|---|---|---|---|
| `ALL`  | 2026-04-30 | `ALL` | 1 |
| `GLB3` | 2026-03-31 | `ALL` | 3 |

`minSampleSize = 5` ([application.yml:106](../../src/main/resources/application.yml#L106)).
Blended total = `1 + 3 = 4 < 5`.

The `GLB3` row is a red herring for the numeric bug (see "Out of scope" below) — the blended
profile query never filters on `dimension_value`, so it's summed into the same total regardless.
The actual defect is three gates, all keyed off the same `minSampleSize` config, applied
inconsistently between where an estimate is **written** (lenient) and where it's **read back for
display** (strict) — see root cause below.

## Root cause (traced in code)

1. **`CalculatorProfile.hasSufficientSamples(minSampleSize)`** (`totalRuns >= minSampleSize`) is the
   right gate for *SLA-grading trustworthiness* — you shouldn't grade a run against a duration
   average built from one sample. It's correctly used for that purpose in
   `SlaBaselineResolver.resolveFallbackBaselineMs` and
   `RunIngestionService.resolveExpectedDuration` (persists `expected_duration_ms`).

2. But `CalculatorStateService.buildNotStartedEntry`
   ([CalculatorStateService.java:180](../../src/main/java/com/company/observability/service/CalculatorStateService.java#L180))
   and `ExpectedRunsService.placeholder`
   ([ExpectedRunsService.java:172](../../src/main/java/com/company/observability/service/ExpectedRunsService.java#L172))
   reuse the **same strict gate** to decide whether to *display* an estimate — even though
   `RunIngestionService.resolveEstimatedStart`/`resolveEstimatedEnd`
   ([RunIngestionService.java:395-436](../../src/main/java/com/company/observability/service/RunIngestionService.java#L395-L436))
   already use a **lenient** gate (`totalRuns() > 0` / `avgDurationMs() > 0`) for the exact same
   estimated-start/end fields when persisting them on a run. The read paths never got aligned with
   the write path.

3. `buildNotStartedEntry`'s fallback for "profile too thin" is
   [`findLatestRunEstimatesByName`](../../src/main/java/com/company/observability/repository/CalculatorRunRepository.java#L556-L603),
   which filters `WHERE expected_duration_ms IS NOT NULL` — i.e. it only considers runs whose
   *strictly-gated* field is populated. For a calculator whose recent runs never individually
   cleared `minSampleSize` at their own start time, this returns **zero rows**, not a row missing
   two fields. When that happens `latest == null`, and `buildNotStartedEntry` also loses
   `calculatorId` and the projected SLA deadline (`latest.getSlaTime()` is never reached) — the
   whole entry can come back empty, not just the estimate.

   Even when a row *is* found, the 1b block reads `latest.getExpectedDurationMs()` instead of the
   already-available, leniently-gated `latest.getEstimatedStartTime()`/`getEstimatedEndTime()` pair.

Net effect: a calculator needs `minSampleSize` samples before it ever shows an estimate on
`/batch/runs`, even though a single real historical run is enough to produce one at ingestion
time. Low-volume/new calculators — disproportionately the doubly-agnostic archetype (see #3 below)
— are worst affected.

### Why not just lower `minSampleSize` to 1 instead of adding `hasAnySample()`

Considered and rejected. `minSampleSize` is also the gate for `SlaBaselineResolver`'s blank-`slaTime`
fallback baseline and `AnalyticsService.resolveReferenceLines`'s synthesized buffered-deadline line —
both **grading/alerting** paths, not display. A "baseline" built from one sample isn't a baseline,
it's that one run's duration treated as a rule: if that run was unusually slow, every normal run
after it grades as comfortably early and a genuinely slow run can still clear the inflated bar; if it
was unusually fast, normal runs start breaching and `AlertHandlerService` pages on noise. Setting
`minSampleSize=1` globally would silence the display symptom by quietly degrading breach-detection
quality — the fix would be worse than the bug. The two call sites this plan touches
(`buildNotStartedEntry`, `ExpectedRunsService.placeholder`) are pure display — nothing is graded or
alerted against the number — so they get a separate, hardcoded `totalRuns() > 0` floor
(`hasAnySample()`) instead of relaxing the shared grading threshold. One config value, one fixed
floor — not a second tunable, since "do I have any real data at all" isn't a policy anyone needs to
adjust per environment.

## Approach — three coordinated, minimal changes

Each restores symmetry with a pattern that's already correct elsewhere in the code; no new
concepts introduced.

### 1. Split "trustworthy for grading" from "good enough to display"

Add to `CalculatorProfile`:

```java
/** Any real history at all — the bar for *displaying* an estimate, not for SLA grading. */
public boolean hasAnySample() {
    return totalRuns > 0 && avgDurationMs > 0;
}
```

Switch the two display-only call sites from `hasSufficientSamples(minSampleSize)` to
`hasAnySample()`:
- `CalculatorStateService.buildNotStartedEntry` line 180
- `ExpectedRunsService.placeholder` line 172

Leave every SLA-grading/baseline call site (`SlaBaselineResolver`,
`RunIngestionService.resolveExpectedDuration`, `AnalyticsService.resolveReferenceLines`) on
`hasSufficientSamples` — unchanged, still strict, still correct for that purpose.

`profile.confidence()` (already `EXACT`/`SPARSE_EXACT`/`RECENT_EXACT`) keeps riding along on the
response so a future UI change can flag a thin estimate instead of hiding it — no schema change
needed now, just noting the field is already there if wanted later.

### 2. Fix the latest-run fallback filter + its index

`CalculatorRunRepository.findLatestRunEstimatesByName`: change the WHERE predicate from
`expected_duration_ms IS NOT NULL` to `estimated_end_time IS NOT NULL` — the leniently-populated
field, non-null on every run except a calculator's absolute first-ever start with zero history
anywhere to draw from.

Requires a matching index swap (current index is a **partial** index on the old predicate —
Postgres won't use it once the query predicate no longer matches):

`V10__latest_estimate_index.sql`:
```sql
DROP INDEX IF EXISTS calculator_runs_latest_estimate_by_name_idx;

CREATE INDEX IF NOT EXISTS calculator_runs_latest_estimate_by_name_idx
    ON calculator_runs (calculator_name, frequency, reporting_date DESC, created_at DESC)
    WHERE estimated_end_time IS NOT NULL;
```

In `CalculatorStateService.buildNotStartedEntry`'s 1b block, replace the
`latest.getExpectedDurationMs()` read with the implied duration:

```java
if (estStart == null && latest != null
        && latest.getEstimatedStartTime() != null && latest.getEstimatedEndTime() != null) {
    long impliedMs = Duration.between(latest.getEstimatedStartTime(), latest.getEstimatedEndTime()).toMillis();
    int minuteOfDay = (int) Duration.between(
            latest.getEstimatedStartTime().truncatedTo(ChronoUnit.DAYS),
            latest.getEstimatedStartTime()).toMinutes();
    estStart = TimeUtils.instantFromUtcMinuteOfDay(executionDate, minuteOfDay);
    estEnd = estStart.plusMillis(impliedMs);
    expectedMs = impliedMs;
    calculatorId = latest.getCalculatorId();
}
```

### 3. Tier-2 fallback for the blended (doubly-agnostic) profile

`CalculatorProfileService.getProfile(name, freq)` (2-arg) is the only overload with no
`RECENT_EXACT` fallback to raw `calculator_runs`. Every calculator that's run-number-aware *or*
dimension-aware never reaches this bare method (their 3-arg/4-arg overloads only delegate down to
it when *both* are agnostic) — so only the fully-agnostic archetype loses the "brand-new
calculator → last 5 raw runs" guarantee documented in `docs/daily-aggregation-spec.md` Scenario 3.

`DailyAggregateRepository`: expose the existing private two-tier helper for the null/null case:

```java
public CalculatorProfile findRecentExactBlended(String calculatorName, String frequency, int days) {
    return findRecentExact(calculatorName, frequency, days, null, null);
}
```

`CalculatorProfileService.getProfile(String, Frequency)`: add the Tier-2 step, mirroring the
3-arg/4-arg structure:

```java
public CalculatorProfile getProfile(String calculatorName, Frequency frequency) {
    String key = key(calculatorName, frequency, null, null);
    CalculatorProfile cached = readFromCache(key);
    if (cached != null) { ...hit... return cached; }
    ...miss...

    CalculatorProfile profile = dailyAggregateRepository.findProfile(
            calculatorName, frequency.name(), slaProperties.lookbackDays(frequency));
    if (profile.totalRuns() > 0) {
        return cacheAndReturn(key, tagAggregateConfidence(profile));
    }

    CalculatorProfile recent = dailyAggregateRepository.findRecentExactBlended(
            calculatorName, frequency.name(), slaProperties.lookbackDays(frequency));
    if (recent.totalRuns() > 0) {
        return cacheAndReturn(key, recent.withConfidence(CalculatorProfile.ProfileConfidence.RECENT_EXACT));
    }

    return cacheAndReturn(key, CalculatorProfile.fromSums(calculatorName, frequency.name(), null, null, 0, 0, 0, 0));
}
```

(`tagAggregateConfidence` already exists; reuse it instead of hand-rolling the EXACT/SPARSE_EXACT
split.)

## Behavior after the change

| Case | Before | After |
|---|---|---|
| Calculator with 1-4 total samples in window (marketriskdev today) | No estimate, possibly empty entry | Estimate shown, `confidence=SPARSE_EXACT` |
| Brand-new doubly-agnostic calculator, zero aggregate rows yet | No estimate until next nightly run | `RECENT_EXACT` from last raw runs, same as aware/dimensioned calcs |
| Calculator with ≥5 samples | Estimate shown | Unchanged |
| SLA baseline / grading for thin-history calc | Ungraded (correct) | Unchanged — still ungraded until `minSampleSize` |
| `/executions` reference lines (`AnalyticsService`) | Falls back to latest run's own fields when thin | Unchanged |

## Testing

- `CalculatorStateServiceTest` (or equivalent): NOT_STARTED entry for a calculator with
  `totalRuns` between 1 and `minSampleSize-1` now returns non-null `estimatedStartTime`/`estimatedEndTime`.
- `CalculatorRunRepositoryJdbcTest`: `findLatestRunEstimatesByName` returns a row whose
  `expectedDurationMs` is null but `estimatedEndTime` is set.
- `DailyAggregateRepositoryJdbcTest` / `CalculatorProfileServiceTest`: blended `getProfile` returns
  `RECENT_EXACT` from raw `calculator_runs` when `calculator_sli_daily` has no matching row, for a
  calculator with no `run_number`/dimension config (mirrors existing 3-arg/4-arg Tier-2 tests).
- Regression: existing SLA-baseline tests for thin-history calculators must still show `ungraded`/no
  deadline — confirms `hasSufficientSamples` was untouched.

## Out of scope (separate decision)

The `GLB3` dimension row is cosmetically confusing (a `NONE`-archetype calculator should only ever
show `dimension_value='ALL'`) but numerically harmless — `findProfile`/`findAllProfiles` never
filter on `dimension_value`, so it's summed into the blended total regardless. It's residual data
from before the ingestion dimension-guard existed (`RunIngestionService.java:96-106`); `region` is
immutable after insert, so the guard can't retroactively fix it. Prod self-heals as the row ages
out of the 20-day MONTHLY recompute window; `application-dev.yml`'s 400-day recompute window keeps
re-deriving it nightly from the old contaminated raw row, which is why it's still visible here.

If it should be cleaned up, a one-time manual step (not a code/config change) —

```sql
UPDATE calculator_runs
SET additional_attributes = COALESCE(additional_attributes, '{}'::jsonb) || jsonb_build_object('stray_region', region),
    region = NULL
WHERE calculator_name = 'marketriskrwacalcdev' AND region IS NOT NULL;
```

— followed by `POST /api/v1/admin/aggregation/recompute?from=<earliest backfilled date>` to rebuild
`calculator_sli_daily`. Not part of this fix; flagging for a separate decision since it touches
production data directly.
