# Fix duplicate RunEntry rows in `GET /api/v1/calculators/batch/runs`

## Context

The dashboard feed returns **duplicate rows for the same logical dimension** (e.g. `region=AMER`)
for `capital`, `MONTHLY`, `run_number=2`. The user wants **one entry per configured dimension per
run_number** — the latest attempt (by `startTime`) picked, marked `isRerun=true`.

There are **three distinct causes**:

1. **`runType` over-segmentation (primary).** `capital` is region-dimensioned in config
   (`observability.calculator.regions`), but its runs **also** populate `runType` with `BATCH`/`INTRA`.
   The internal dedup key is `region:runType:runNumber`, so `AMER:BATCH:2` and `AMER:INTRA:2` become
   **two** AMER rows. For a region-dimensioned calculator, `runType` is a sub-variant that must collapse.
2. **Cross-phase gap in `buildEntry`.** It collapses split runs (by `correlationId`) and standalone
   runs (by `region:runType:runNumber`) in two independent phases, then concatenates
   ([CalculatorStateService.java:139](src/main/java/com/company/observability/service/CalculatorStateService.java#L139))
   with no cross-phase dedup — so two split groups, or a split group + a standalone, for the same
   dimension survive as separate rows.
3. **Multi-real-name alias fan-out.** A `capital` alias expands to multiple real `calculator_name`s
   (`capital: [capitalcalcdev, …]`); if more than one real calculator emits the same region,
   `mergeEntries` ([RunQueryController.java:132-162](src/main/java/com/company/observability/controller/RunQueryController.java#L132-L162))
   concatenates them with no dedup.

**Decisions (confirmed with user):**
- Fix duplicates only. **Do not** add strict declared-dimension enforcement in `ExpectedRunsService`
  — its "declared set is a floor, not a whitelist" behavior is intentional, documented, and tested.
- `runType` variants (`BATCH`/`INTRA`) on a region calculator collapse into the one region bucket.
- "Latest" = latest **`startTime`** (a started run beats a NOT_STARTED projection; tie-break `endTime`).

## Critique of `docs/plans/duplicate_region_fix.md`

- Correctly localizes intent (latest-wins, `isRerun`) but **only addresses cause 2** (multiple split
  groups). It keeps `runType` in the logical key, so it would **not** fix `capital`'s `BATCH`/`INTRA`
  duplication, and it never considers multi-real-name fan-out.
- It would introduce an NPE (deduping `collapseSplitGroup` output by `createdAt`, which that method
  never sets). Moot in the approach below — `buildEntry` is left untouched.
- Its Part 2 (strict `ExpectedRunsService` whitelist) is rejected.
- Minor: package paths (`com.ubs.cf.observability`) are wrong; real package is `com.company.observability`.

## Approach: one dimension-aware dedup pass in `mergeEntries`

`mergeEntries` is the single point that sees an alias's **entire** run set (all real names, all
dimensions). Deduping there subsumes **all three causes** at once. `buildEntry`, `collapseSplitGroup`,
`ExpectedRunsService`, and the Redis cache are **untouched** (buildEntry still pre-collapses splits via
`collapseSplitGroup` and standalone reruns; any residual duplicates are mopped up at merge).

### 1. `RunEntry` DTO — add `runNumber` (additive)

[CalculatorBatchRunsResponse.java](src/main/java/com/company/observability/dto/response/CalculatorBatchRunsResponse.java):
add `String runNumber` to the `RunEntry` record (`@JsonInclude(NON_NULL)` already on the record →
null cycles are omitted). Populate it in
[CalculatorStateService.toRunEntry](src/main/java/com/company/observability/service/CalculatorStateService.java#L341-L366)
(`.runNumber(run.getRunNumber())`). Needed because the merge-level dedup key must keep distinct cycles
(RUN1 vs RUN2, and `"2"` vs the NULL bucket) apart, and `RunEntry` currently drops `runNumber`.

### 2. `CalculatorNameResolver` — expose the configured dimension

[CalculatorNameResolver.java](src/main/java/com/company/observability/service/CalculatorNameResolver.java)
already wraps `CalculatorProperties` and has `findAliasFor`. Add:

```java
public enum Dimension { REGION, RUN_TYPE, NONE }

public Dimension dimensionOf(String nameOrAlias) {
    String alias = findAliasFor(nameOrAlias).orElse(nameOrAlias); // alias passed in resolves to itself
    if (calculatorProperties.getRegions().containsKey(alias))  return Dimension.REGION;
    if (calculatorProperties.getRunTypes().containsKey(alias)) return Dimension.RUN_TYPE;
    return Dimension.NONE;
}
```

### 3. `RunQueryController.mergeEntries` — dimension + runNumber dedup

After collecting all runs across the alias's real names, group by a dimension-aware key and collapse
each bucket to the latest by `startTime`:

```java
Dimension dim = nameResolver.dimensionOf(alias);
Function<RunEntry, String> keyFn = r -> switch (dim) {
    case REGION   -> Objects.toString(r.region(), "")  + "|" + Objects.toString(r.runNumber(), "");
    case RUN_TYPE -> Objects.toString(r.runType(), "") + "|" + Objects.toString(r.runNumber(), "");
    case NONE     -> Objects.toString(r.region(), "")  + "|" + Objects.toString(r.runType(), "")
                                                        + "|" + Objects.toString(r.runNumber(), "");
};

List<RunEntry> deduped = allRuns.stream()
    .collect(Collectors.groupingBy(keyFn, LinkedHashMap::new, Collectors.toList()))
    .values().stream()
    .map(bucket -> {
        RunEntry latest = bucket.stream()
            .max(Comparator
                .comparing((RunEntry r) -> !"NOT_STARTED".equals(r.status()))   // started beats projection
                .thenComparing(r -> r.startTime(), Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(r -> r.endTime(),   Comparator.nullsFirst(Comparator.naturalOrder())))
            .orElseThrow();
        long realAttempts = bucket.stream().filter(r -> !"NOT_STARTED".equals(r.status())).count();
        boolean rerun = realAttempts > 1 || bucket.stream().anyMatch(RunEntry::isRerun);
        return latest.toBuilder().isRerun(rerun).build();   // RunEntry has @Builder(toBuilder = true)
    })
    .toList();
```

- `dim == NONE` (unconfigured alias) keeps the current `region|runType|runNumber` behavior.
- `dim == REGION` (e.g. `capital`) ignores `runType`, so `BATCH`/`INTRA` collapse into one `AMER`.
- Selection prefers a started run over a NOT_STARTED projection, then latest `startTime`.
- `isRerun=true` when >1 real attempt shares the bucket, or any constituent was already a rerun.

### Behavior after the change

| Case | Before | After |
|---|---|---|
| `capital AMER BATCH` + `capital AMER INTRA` (the bug) | 2 rows | 1 `AMER` row, latest `startTime`, `isRerun=true` |
| Two split groups, same region+run_number | 2 rows | 1 row, `isRerun=true` |
| Split group + standalone, same dimension | 2 rows | 1 row, `isRerun=true` |
| Multi-real-name alias, both emit `AMER` (same run_number) | 2 rows | 1 row, `isRerun=true` |
| Same region, different run_number (no `run_number` param) | 2 rows | 2 rows (kept — distinct cycles) |
| `run_number=2` bucket + NULL-run_number bucket, same region | 2 rows | 2 rows (kept — distinct cycles) |
| `modelled-exposure` ETD/OTC/SFT | per runType | unchanged (keyed by runType) |
| Unconfigured calculator | combined key | unchanged |

## Tests

- **`CalculatorNameResolverTest`** (new or existing): `dimensionOf` → REGION for `capital`, RUN_TYPE
  for `modelled-exposure`, NONE for an unknown name; resolves correctly for both alias and real-name input.
- **`RunQueryControllerTest`** ([…/controller/RunQueryControllerTest.java](src/test/java/com/company/observability/controller/RunQueryControllerTest.java),
  `@WebMvcTest`, mocked services): feed `calculatorStateService.getState` a per-real-name map containing
  duplicate-dimension `RunEntry`s and stub `nameResolver.dimensionOf(...)`. Assert:
  - `capital` `AMER/BATCH` + `AMER/INTRA` → one `AMER`, `isRerun=true`, fields from the latest-`startTime` run.
  - Two real names both emitting `AMER` (same run_number) → one `AMER`, `isRerun=true`.
  - Same region, different run_number → two rows (no over-collapse).
  - runType-dimensioned alias keeps ETD/OTC/SFT separate.
  - Response JSON now carries `runNumber`.
  - **Stub `dimensionOf` (default NONE) in existing tests** so the new switch doesn't NPE on an unstubbed mock.
- `CalculatorStateServiceTest` and `ExpectedRunsServiceTest` are untouched (their services don't change),
  beyond adding the `runNumber` field to any `RunEntry` builders they assert on.

## Verification

1. `mvn test -Dtest=RunQueryControllerTest,CalculatorNameResolverTest`
2. End-to-end — the `/batch/runs` cache stores per-real-name `CalculatorEntry`s (pre-merge), so stale
   rows persist until TTL/event eviction; the merge dedup runs per request, but bypass cache to be sure
   with `nocache=true`:
   `GET /api/v1/calculators/batch/runs?reporting_date=<eom>&frequency=MONTHLY&run_number=2&keys=capital&nocache=true`
   → confirm `AMER` appears exactly once with `isRerun=true`, `capital` returns its 10 configured regions,
   and each row carries `runNumber`.
3. `SPRING_PROFILES_ACTIVE=local mvn clean test` for the full suite.
```

---

## Fixes applied:

1. CalculatorBatchRunsResponse.java
Added String runNumber field to the RunEntry record (between runType and status). Covered by @JsonInclude(NON_NULL) so null values are omitted from JSON.

2. CalculatorStateService.java
In toRunEntry (~line 348), added .runNumber(run.getRunNumber()) to the RunEntry builder — so every RunEntry now carries the run's cycle identifier.

3. CalculatorNameResolver.java
Two additions:

Dimension enum (REGION, RUN_TYPE, NONE) nested inside the class.
dimensionOf(String nameOrAlias) method — checks calculatorProperties.getRegions() and getRunTypes() to return the primary grouping dimension for an alias.
4. RunQueryController.java
mergeEntries was rewritten to add a dimension-aware dedup pass:

Calls nameResolver.dimensionOf(alias) to get the dimension.
Builds a keyFn: region|runNumber for REGION, runType|runNumber for RUN_TYPE, region|runType|runNumber for NONE (unchanged behavior for unconfigured aliases).
Groups all runs by that key, selects the latest per bucket (started beats NOT_STARTED, then latest startTime, then latest endTime).
Sets isRerun=true when >1 real attempt shares a bucket, or any constituent was already a rerun.
The single-entry fast-path was removed (it still works correctly through the general path).
Added imports: Dimension and java.util.function.Function.
5. RunQueryControllerTest.java
Added import Instant and import Dimension.
In @BeforeEach configurePassthroughResolver(): added lenient().when(nameResolver.dimensionOf(any())).thenReturn(Dimension.NONE) so existing tests don't NPE on the new dimensionOf call.
6 new tests added:
mergeEntries_regionCalculator_collapsesBatchAndIntraForSameRegion — the primary bug case: AMER/BATCH + AMER/INTRA → 1 row, latest runId wins, isRerun=true, runNumber in response.
mergeEntries_regionCalculator_differentRunNumbers_keepsBothRows — same region, different runNumber → 2 rows kept.
mergeEntries_multiRealName_sameRegionAndRunNumber_collapsesToOne — two real calculators both emitting AMER/run_number=2 → 1 row.
mergeEntries_runTypeCalculator_keepsDistinctRunTypes — ETD/OTC remain separate rows.
mergeEntries_startedRunBeatsNotStartedInSameBucket — RUNNING wins over NOT_STARTED projection in same bucket.
batchRuns_runEntryCarriesRunNumber — runNumber appears in response JSON.
6. CalculatorNameResolverTest.java
Added import Dimension.
Extended @BeforeEach setUp() to also set regions (capital → [AMER, EMEA, APAC]) and runTypes (modelled-exposure → [ETD, OTC, SFT]) on CalculatorProperties.
3 new tests added:
dimensionOf_regionAlias_returnsRegion
dimensionOf_runTypeAlias_returnsRunType
dimensionOf_unknownAlias_returnsNone