# Plan: Test-Authoring Spec for `/batch/runs` and `/executions`

## Context

The two read endpoints — `GET /api/v1/calculators/batch/runs` (dashboard feed) and
`GET /api/v1/analytics/calculators/{name}/executions` (raw history) — carry the bulk of the
service's non-obvious logic: alias expansion/re-grouping, dimension handling (REGION / RUN_TYPE /
NONE), run-number-aware vs run-number-agnostic suppression, `correlation_id` split-group collapsing,
sequential-rerun dedup, NOT_STARTED projection, and `padToExpected` placeholder synthesis. The exact
behavior is spread across a controller, three services, a name resolver, and `application*.yml` config,
which makes it hard to author tests with confidence that every combination is covered.

**Goal:** produce one self-contained Markdown spec that an AI model can read and turn into thorough
**service-level unit tests** — an explicit, numbered case matrix covering edge cases and combinations,
plus the behavior rules and config inputs each case depends on. The document describes *current
implemented behavior* (reverse-engineered from code), not desired changes. **No production code changes.**

Deliverable decisions (confirmed with user):
- **Format:** behavior rules + **explicit numbered case matrix** (TC-IDs, inputs → expected output).
- **Scope:** both endpoints' full behavior **plus the shared helpers** they depend on
  (`RunNumbers.normalize`, `Frequency.fromStrict`/`from`, `SlaBaselineResolver.parseRunNumber`,
  alias/dimension/run-number config, and `ExpectedRunsService.evaluateSlaStatus` SLA grading).
  Caching internals, profile tiers, and `TimeUtils` date math are referenced as black boxes, not
  re-specified as testable units.
- **Test layer:** **service-level unit tests** (Mockito), matching existing patterns in
  `CalculatorStateServiceTest`, `AnalyticsServiceTest`, `ExpectedRunsServiceTest`,
  `CalculatorNameResolverTest`.

## Deliverable

A single new file: **`docs/query-endpoints-test-spec.md`** (sits alongside the existing
`docs/plans/*.md`, e.g. `run_number_enhancement.md`, `duplicate_region_fix.md`, `simplify_query_path.md`,
which are the design history this spec consolidates the *behavior* of). No code is touched.

## Source-of-truth references (what each section is derived from)

| Behavior area | Primary source |
|---|---|
| `/batch/runs` request contract, alias re-grouping, dedup/merge, Cache-Control | `controller/RunQueryController.java` |
| `/batch/runs` per-name state, split-group collapse, rerun dedup, NOT_STARTED projection | `service/CalculatorStateService.java` |
| Padding to declared dimension set, placeholders, placeholder SLA grading | `service/ExpectedRunsService.java` |
| Alias expand/reverse, `dimensionOf`, `isRunNumberAware` | `service/CalculatorNameResolver.java` |
| `/executions` request contract, no-grouping, run-number suppression, envelope/aggregates, reference lines | `controller/AnalyticsController.java`, `service/AnalyticsService.java` |
| SQL run-number/null filtering, ordering, name-keyed reads | `repository/CalculatorRunRepository.java` (`findAllRunsByDateAndDimension`, `findRunsByName`, `findLatestRunEstimatesByName`) |
| Config: aliases / regions / run-types / run-number-aware | `config/CalculatorProperties.java` + `application.yml` (regions/run-types/run-number-aware, env-invariant) + `application-dev.yml` / `application-prod.yml` (aliases, env-specific) |
| Shared helpers | `util/RunNumbers.java`, `domain/enums/Frequency.java`, `service/SlaBaselineResolver.java#parseRunNumber`, `config/SlaProperties.java` |
| DTOs | `dto/response/CalculatorBatchRunsResponse.java`, `dto/response/RunPerformanceData.java`, `domain/RunWithSlaStatus.java` |

## Document structure

### 1. Overview & how to use this spec
- Purpose; the two endpoints in one paragraph each; the convention used for the case matrix
  (TC-ID, preconditions/config, input, expected output, source ref).
- Note the contrast that drives most tests: **`/batch/runs` collapses/dedups; `/executions` does not group at all.**

### 2. Config reference (the inputs every test must set up)
Tabulate the env-invariant config from `application.yml` and the env-specific aliases:
- `regions`: `capital → [WMAP, WMDE, ASIA, WMUS, AUNZ, WMCH, ZURI, LDNL, AMER, EURO]`
- `run-types`: `modelled-exposure → [ETD, OTC, SFT]`, `gemini-hedge → [ETD, OTC, SFT]`
- `run-number-aware`: `[capital, portfolio]` (declared by **alias**, not real name)
- `aliases` prod vs dev (note `capital` is **multi-alias** in prod:
  `[capitalcalc, capitalcalcmedium, capitalcalcsmall, capitalcalcextrasmall]`).
- Spell out the three calculator archetypes the code reasons about: REGION-dimensioned,
  RUN_TYPE-dimensioned, NONE (dimensionless); and the run-number-aware vs agnostic axis (orthogonal).
- State explicitly that unit tests inject `CalculatorProperties` directly (no Spring context needed).

### 3. Shared helper semantics (black-box contracts + their own cases)
- `RunNumbers.normalize`: blank/null → null; trim; `"01"→"1"`; non-numeric returned as-is.
- `Frequency.fromStrict`: `D/DAILY/M/MONTHLY` (case-insensitive) else **throws** (→ 400);
  vs `Frequency.from` lenient default DAILY (DB row mapper only).
- `SlaBaselineResolver.parseRunNumber`: null/blank/non-numeric/≤0 → **2**; else the int.
- `SlaProperties.bandGapMs()` = `(veryLate−late)·60_000` (default 15min); `lateBandMs()`, `lookbackDays(freq)`.
- `ExpectedRunsService.evaluateSlaStatus(deadline, bandGapMs, now)`:
  null deadline → `(ON_TIME,false)`; now ≤ deadline → `(ON_TIME,false)`;
  ≤ deadline+gap → `(LATE,true)`; beyond → `(VERY_LATE,true)`.

### 4. `/batch/runs` — behavior rules + case matrix
Sub-sections, each with rules then a numbered matrix:
- **4.1 Request binding & validation** — `keys` pipe-split/trim/empty-filter; empty → `IllegalArgumentException`;
  `frequency` strict 400; `run_number` normalized once; `nocache`; optional `X-Tenant-Id`.
- **4.2 Alias expansion & re-grouping** — alias → real names; unknown passthrough; response re-keyed by
  alias; `mergeEntries` across multiple real names; `mergedId` (single id else null);
  `RunEntry.calculatorName` populated only on cross-name merge (`toRunEntry`).
- **4.3 Dimension handling** — `dimensionOf` (REGION/RUN_TYPE/NONE); one entry per region / per run-type;
  `dimensionKey` (REGION→region, RUN_TYPE→runType, NONE→`""`); incidental region/run_type must not split a NONE slot.
- **4.4 `correlation_id` split grouping** (`CalculatorStateService.buildEntry` Phase 1 +
  `collapseSplitGroup`) — runs sharing a non-null `correlationId` collapse to one RunEntry:
  worst-status via `STATUS_PRECEDENCE` (RUNNING>FAILED>TIMEOUT>CANCELLED>SUCCESS), min start, max end
  (null end while RUNNING), duration recompute, worst SLA band, joined breach reasons, `isRerun=false`.
- **4.5 Sequential rerun dedup** (Phase 2) — null-`correlationId` runs grouped by
  `(region,runType,runNumber)`, latest-by-`createdAt` wins, `isRerun = group.size()>1`. RUN1/RUN2 are
  distinct cycles, **not** reruns of each other.
- **4.6 Controller-level dedup/merge** (`mergeEntries`) — strict null suppression when
  `isRunNumberAware(alias) && runNumber != null`; otherwise **seahorse fold** (drop a null-run_number
  row only when a numbered sibling shares its `dimensionKey`); final dedup key
  `dimensionKey|runNumber`, latest selection (NOT_STARTED de-prioritized → startTime → endTime),
  `isRerun` = realAttempts>1 OR any child isRerun.
- **4.7 Run-number-aware vs agnostic** — matrix crossing {aware, agnostic} × {run_number set, absent} ×
  {has null rows, has numbered rows, mixed}. Include: strict suppression of null rows; projection/placeholder
  stamping with requested run_number; agnostic ignores run_number for suppression.
- **4.8 NOT_STARTED projection** (`buildNotStartedEntry`) — empty list when `run_number` set but zero
  scoped history (no invented bucket); offset via `deriveOffsetDays` (DAILY: reportingDate→slaTime
  business-day distance; MONTHLY/fallback: `parseRunNumber`); estimates profile→latest-run→none;
  projected SLA (DAILY re-anchor time-of-day on executionDate; MONTHLY clock from estStart, null if no
  estStart); empty entry when no estStart AND no projectedSla (brand-new calculator); synthetic
  RunEntry graded by `evaluateSlaStatus`.
- **4.9 `padToExpected`** (`ExpectedRunsService`) — only for aliases in `regions`/`runTypes`;
  non-synthetic real runs grouped by dimension; uncovered (undeclared/null-dimension) runs always kept;
  `allDeclaredCovered` short-circuit (declared order + uncovered); placeholders for missing declared
  dims with estimates dim-profile→template→none and calculator-level deadline (sibling SLA→template
  SLA→template estEnd); placeholder execution-date offset derivation; placeholder run_number stamping.
- **4.10 Cache-Control & cache interaction** — `isLive` (any RUNNING/NOT_STARTED/empty → `max-age=5`,
  else `30`, always `private`); `nocache` bypasses read but refreshes; partial cache hits;
  not-started entries cached so absent names don't re-hit DB. (Cache TTL internals referenced, not re-specified.)

### 5. `/executions` — behavior rules + case matrix
- **5.1 Request binding & validation** — `days` `@Min(1)@Max(365)` (→400 out of range), default 30;
  `frequency` strict; `run_number` normalized; `data_as_of_date` default today; `nocache`.
- **5.2 Alias resolution & flat-merge** — `resolve(name)` → real names; runs flat-mapped and sorted by
  `reportingDate` then `startTime` (nulls first).
- **5.3 No grouping (the defining contract)** — every physical run is an independent `RunDataPoint`;
  splits sharing `correlationId` appear as **separate** rows (`subRunIds` always null on this path).
- **5.4 Run-number filtering (two-stage)** — DB includes `(run_number = X OR run_number IS NULL)` when
  set; service then drops null-run_number rows only when `isRunNumberAware(name) && rn != null`
  (agnostic keeps nulls).
- **5.5 Per-run mapping** — RUNNING runs null out `endTime`/`durationMs`; `slaStatus` via
  `classifySlaStatusForRun` (RUNNING or null band → ON_TIME, else band name).
- **5.6 Envelope aggregates** — `meanDurationMs` (terminal & duration>0 only), `totalRuns` (terminal),
  `runningRuns`, `slaMetCount`/`lateCount`/`veryLateCount` (terminal only); empty-input envelope shape.
- **5.7 Reference lines** (`resolveReferenceLines`) — profile with sufficient samples → estStart from
  profile + frozen `slaTime` (or synthesized buffered deadline when slaTime null); else latest run's
  `estimatedStartTime` + `slaTime`.
- **5.8 Cache** — key `obs:analytics:executions:{name}:{freq}:{days}:{rn|all}:{asOfDate}`, 5-min TTL,
  name-index; `nocache` bypass.

### 6. Cross-endpoint contrast table
One table summarizing where the two endpoints diverge (grouping, dedup, run-number null handling,
projection/padding, dimension awareness) so an AI doesn't carry one endpoint's rules into the other.

### 7. Existing coverage map (so generated tests complement, not duplicate)
Brief table: which existing test class already covers each rule area (e.g.
`CalculatorStateServiceTest`, `ExpectedRunsServiceTest`, `RunQueryControllerTest`,
`AnalyticsServiceTest`, `CalculatorNameResolverTest`, `RunNumbersTest`, `FrequencyTest`), with
explicit "gaps / under-covered combinations" callouts to prioritize.

### 8. Test fixture guidance
- Point at `util/TestFixtures.java` and the Mockito setup pattern in `CalculatorStateServiceTest`
  (mock `CalculatorRunRepository`, `CalculatorStateCacheService`, `CalculatorProfileService`, fixed
  `Clock`; real `SlaProperties`/`CalculatorProperties`).
- Note the `Clock` injection point for deterministic SLA-grading tests and the
  `CalculatorProfile` zero-sample sentinel pattern.

## Verification

This is a documentation deliverable (no code change), so verification is correctness-of-spec, not tests-pass:
1. **Trace-back check:** every numbered case in §4–§5 cites the exact method/branch in the source table
   above; spot-check 8–10 cases by re-reading the cited code to confirm the expected output matches the
   implementation (not the docstring, which can drift).
2. **Cross-check against existing tests:** for rule areas with existing tests, confirm the spec's expected
   outputs agree with what those tests already assert (any disagreement = a spec error or a real bug to flag).
3. **Combination completeness:** confirm the §4.7 matrix covers all {aware/agnostic} × {run_number
   present/absent} × {null/numbered/mixed rows} cells, and §5.4 the two-stage filter cells.
4. **Build sanity (optional):** the doc names real symbols; if desired, `grep` each cited method name to
   confirm it still exists before publishing.

## Out of scope
- No changes to controllers, services, repository, config, or existing tests.
- Not writing the actual test classes (the spec is the input for that, as a follow-up).
- Not re-specifying cache TTL internals, profile-tier selection, or `TimeUtils` math as standalone test units.
