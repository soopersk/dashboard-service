# Fix midnight wraparound in `calculator_sli_daily` start/end time averaging (circular mean)

**Labels:** `bug`, `backend`, `observability`, `data-quality`

## Summary

`calculator_sli_daily.sum_start_min_utc` / `sum_end_min_utc` average a run's start/end
time by linearly summing minute-of-day (0–1439) and dividing by `total_runs` at read
time (`CalculatorProfile.fromSums`). Minute-of-day is a **circular** quantity, not a
linear one, so this average is wrong for any calculator whose runs cluster near UTC
midnight.

**Example:** two runs at `23:50` (minute 1430) and `00:10` (minute 10):

```
linear mean   = (1430 + 10) / 2 = 720  →  12:00 (noon)      ✗ wrong
circular mean = atan2(Σsin, Σcos)      →  00:00 (midnight)  ✓ correct
```

Off by 12 hours. Feeds directly into `AnalyticsService.resolveReferenceLines`,
`ExpectedRunsService.placeholder`, and `CalculatorStateService` — any calculator whose
typical start/end time is near `00:00 UTC` currently gets a badly wrong estimate
everywhere.

Previously spec'd as Task P2.1 in `docs/plans/2026-07-13-production-hardening-plan.md`,
never implemented. Full design:
`docs/plans/2026-08-04-daily-aggregation-circular-mean-and-outlier-filtering.md`.

## Root cause

`calculator_sli_daily` stores sums only (single nightly writer, additive rollup —
TD-3). Linearly averaging an angle is invalid across the wraparound point. Fix:
vector-average via SIN/COS component sums + `atan2`, which stay linearly additive
across the nightly rollup, so the sums-only architecture is preserved.

## Example: linear mean vs. circular mean, and why circular mean works

**Setup:** Run A at `23:50` (minute 1430), Run B at `00:10` (minute 10). These are
only 20 minutes apart on a clock, straddling midnight.

### Linear mean (current, buggy)

```
(1430 + 10) / 2 = 720  →  12:00 (noon)
```

This treats minute-of-day as a plain number line, `0 … 1439`. On that line, `1430`
and `10` look *far apart* (a difference of `1420`), because the line has no idea that
`1439` wraps back around to `0`. Averaging two numbers that are actually close
together but sit near opposite ends of the line's range lands the result at the
number line's midpoint — `720` — which happens to be the clock position
**diametrically opposite** where the two runs actually are.

### Circular mean (the fix): treat minute-of-day as an angle on a 24-hour clock face

Map each minute `m` to an angle `θ = 2π · m / 1440` (`m=0` → `θ=0`, wrapping back to
`2π` at the next midnight), and represent each run as a **unit vector**
`(cos θ, sin θ)` — a point on the rim of the clock face — instead of a number on a
line:

```
Run A: m=1430 → θ_A = 2π·1430/1440 = 6.2395 rad  (≈357.5°, just before 12 o'clock)
        sin(θ_A) = -0.0436     cos(θ_A) = 0.99905

Run B: m=10   → θ_B = 2π·10/1440   = 0.0436 rad  (≈2.5°, just after 12 o'clock)
        sin(θ_B) =  0.0436     cos(θ_B) = 0.99905
```

Sum the vector components — this is the step that's valid to do with plain addition
across the nightly rollup, unlike summing raw minutes:

```
Σsin = -0.0436 + 0.0436  = 0
Σcos =  0.99905 + 0.99905 = 1.998
```

The resultant vector `(0, 1.998)` points almost straight at 12 o'clock. Its
*direction*, not its raw components, is the answer — recovered with `atan2`:

```
θ_mean = atan2(0, 1.998) = 0 rad  →  minute = 0  →  00:00 (midnight)   ✓ correct
```

### Why it works

Run A and Run B both sit close to the 12-o'clock mark on the clock face — one just
before it, one just after. Averaging their **positions on the rim** (as vectors)
naturally lands back near 12, because that is where they actually cluster. Averaging
their **raw minute numbers** ignores that the number line wraps, and instead finds
the arithmetic midpoint of `10` and `1430` on an *open* line — which is the point
diametrically opposite the true cluster. This isn't a rounding error; it's a category
error: minute-of-day is a position on a circle, not a point on a line, and only
vector (SIN/COS) averaging respects that.

This also explains why the fix is safe for the ordinary case: for runs clustered
*away* from midnight (e.g. all near `10:00`), the circular mean and linear mean
agree — there's no wraparound to get wrong, so the vector directions and the raw
numbers point the same way either way.

## Proposed fix (summary — full SQL/Java in the linked plan doc)

1. **`V11__sli_daily_circular_minutes.sql`** — additive only: `sum_start_sin/cos`,
   `sum_end_sin/cos` (`DOUBLE PRECISION DEFAULT 0`), no backfill. Existing linear
   columns are kept, not repurposed (repurposing would silently corrupt already-stale
   rows outside the recompute window — up to ~13 months for MONTHLY — with no rebuild
   to fix them).
2. `recomputeForDateRange` adds the 4 SIN/COS sums to the existing
   `INSERT...SELECT`.
3. `CalculatorProfile.fromSums` gains 4 params, computes averages via new
   `circularMeanMinute(sumSin, sumCos, linearSum, totalRuns)` — falls back to the
   linear average when `sumSin==0 && sumCos==0` (legacy row, or the genuine 12h-apart
   cancellation edge case).
4. New `CalculatorProfile.empty(...)` factory replaces ~10 zero-sentinel call sites
   (avoids an 8-trailing-zero transposition risk).
5. `findRecentExact` gets a surgical fix only — duration rounding untouched.
6. 6 profile-read methods add the 4 columns to their `SELECT`.

**Rejected alternatives:** reporting-date-relative minutes (double-counts MONTHLY's
day offset); repurposing the 2 existing columns as SIN sums (breaks the legacy
fallback — silently wrong, not gracefully degraded); Postgres `point` type (no
JdbcTemplate support, doesn't solve the repurposing problem anyway).

## Scope

| File | Change |
|---|---|
| `db/migration/V11__sli_daily_circular_minutes.sql` | New |
| `domain/CalculatorProfile.java` | `fromSums` +4 params, `circularMeanMinute()`, `empty()` |
| `repository/DailyAggregateRepository.java` | `recomputeForDateRange` + 6 read methods + `findRecentExact` |
| `service/CalculatorProfileService.java` | 3 zero-sentinel calls → `empty(...)` |
| `DailyAggregateRepositoryJdbcTest.java` | New scenarios |

**Unaffected:** `DailyAggregationJob`/`DailyAggregationJobTest`, profile-construction
tests that bypass `fromSums`, `DailyAggregate`/`findRecentAggregates`/`findByReportingDates`
(confirmed dead code). Outlier/baseline data-quality filtering is a separate issue.

## Acceptance Criteria

- [ ] `V11` migration adds the 4 columns as `DOUBLE PRECISION NOT NULL DEFAULT 0`; existing linear columns/data untouched
- [ ] Nightly rebuild writes all 4 new sums for every aggregate group, alongside the existing linear sums
- [ ] **Midnight-straddling fix**: runs at 23:50 + 00:10 → `avgStartMinUtc == 0` (not 720)
- [ ] **Non-wraparound regression check**: mid-day clustered runs produce the same average as before
- [ ] **Zero-vector edge case**: two runs exactly 12h apart → falls back to the linear average, no exception, no garbage from `atan2(0,0)`
- [ ] **Legacy rows**: pre-migration row (SIN/COS at `DEFAULT 0`) still returns a valid average via the linear fallback
- [ ] All 6 profile-read methods return circularly-correct averages
- [ ] `findRecentExact` returns a circularly-correct average; `avgDurationMs` rounding (`Math.round`) provably unchanged
- [ ] No zero-sentinel call site left as an 8-trailing-zero `fromSums(...)` call — all converted to `CalculatorProfile.empty(...)`
- [ ] `DailyAggregationJob`/`DailyAggregationJobTest` require no changes, pass unmodified
- [ ] `mvn test -Dtest=DailyAggregateRepositoryJdbcTest,CalculatorProfileServiceTest,DailyAggregationJobTest` passes
- [ ] `SPRING_PROFILES_ACTIVE=local mvn clean test` — full suite green
- [ ] Manual check: trigger `POST /api/v1/admin/aggregation/recompute`, inspect `calculator_sli_daily` via psql, confirm SIN/COS populated and `estimatedStartTime` on `/batch/runs` reflects the corrected time

## Related

- `docs/daily-aggregation-spec.md`
- `docs/plans/2026-07-13-production-hardening-plan.md` (Task P2.1, superseded)
- `docs/plans/2026-08-04-daily-aggregation-circular-mean-and-outlier-filtering.md`
