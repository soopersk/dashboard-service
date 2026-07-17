# Run-number scoping fix for NOT_STARTED projections Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Stop `/batch/runs` returning an empty entry for run-number-**agnostic** calculators (`marketriskrwacalcdev`, `modelledexposurecalcdev`, `geminihedgefundcalcdev`) when the query carries a `run_number`.

**Architecture:** `CalculatorStateService.buildNotStartedEntry` is the only one of four run_number-consuming code paths that applies the filter unconditionally, without asking whether the calculator is run-number-aware. The fix normalizes the run_number to an *effective* run_number at the top of that method — `isRunNumberAware(name) ? runNumber : null` — reusing the exact idiom already present in `CalculatorProfileService` and `RunQueryController`. The repository is not touched.

**Tech Stack:** Java 17, Spring Boot 3.5.9, JUnit 5 + Mockito, AssertJ, Maven.

---

## Final review — how the three plans relate

Three defects sit on the same `/batch/runs` → `buildNotStartedEntry` → `findLatestRunEstimatesByName` path. They are **independent root causes**; none subsumes another.

| Plan | Layer | Root cause | Status |
|---|---|---|---|
| `ingestion-dimension-archetype-guard.md` | write | stray `region` wins as dimension key on a `RUN_TYPE` calc | ✅ landed (`134babb`) |
| `thin-history-estimate-fallback-fix.md` | read — sample-count gate | display reused the strict `minSampleSize` grading gate | ✅ landed (`1f614fa`) |
| **this plan** | read — run_number filter | filter applied without a run-number-awareness check | ❌ open |

Both prior plans are fully implemented and verified against the code (`hasAnySample()` at [CalculatorProfile.java:98](../../src/main/java/com/company/observability/domain/CalculatorProfile.java#L98); `estimated_end_time IS NOT NULL` at [CalculatorRunRepository.java:526](../../src/main/java/com/company/observability/repository/CalculatorRunRepository.java#L526) + `V10__latest_estimate_index.sql`; Tier-2 blended fallback at [CalculatorProfileService.java:71-75](../../src/main/java/com/company/observability/service/CalculatorProfileService.java#L71-L75)). No follow-up needed on either.

### The invariant this fix restores

`run_number` is a scoping dimension **only** for calculators declared in `observability.calculator.run-number-aware` ([application.yml:157](../../src/main/resources/application.yml#L157) — `capital`, `portfolio`). For every other calculator, ingestion nulls a stray `run_number` and stashes it in `additional_attributes` ([RunIngestionService.java:125-128](../../src/main/java/com/company/observability/service/RunIngestionService.java#L125-L128)) — so **their entire history has `run_number IS NULL`**. Three of four consumers already honour this; one does not:

| Consumer | Honours awareness? |
|---|---|
| `CalculatorProfileService.getProfile` (3-arg / 4-arg) | ✅ [line 101](../../src/main/java/com/company/observability/service/CalculatorProfileService.java#L101), [line 151](../../src/main/java/com/company/observability/service/CalculatorProfileService.java#L151) |
| `RunQueryController.mergeEntries` | ✅ [line 168](../../src/main/java/com/company/observability/controller/RunQueryController.java#L168) |
| `CalculatorRunRepository.findAllRunsByDateAndDimension` | ✅ lenient `OR run_number IS NULL` ([line 459](../../src/main/java/com/company/observability/repository/CalculatorRunRepository.java#L459)) |
| `CalculatorStateService.buildNotStartedEntry` | ❌ **unconditional scoping** ([lines 154-163](../../src/main/java/com/company/observability/service/CalculatorStateService.java#L154-L163)) |

`CalculatorStateService` does not even hold a `CalculatorNameResolver` reference ([lines 36-40](../../src/main/java/com/company/observability/service/CalculatorStateService.java#L36-L40)), so it structurally *cannot* make the distinction today.

### Confirmed failure trace — `GET /batch/runs?run_number=1&keys=market-risk`

1. No run today → `buildEntry` delegates to `buildNotStartedEntry(name, date, freq, "1")` ([line 107-108](../../src/main/java/com/company/observability/service/CalculatorStateService.java#L107-L108)).
2. `findLatestRunEstimatesByName(name, freq, "1", …)` → `scoped=true` → `AND run_number = '1'` → matches **0 rows** (all history is NULL) → `latest = null`.
3. `runNumber != null && latest == null` → returns `new CalculatorEntry(name, null, List.of())` ([line 159-163](../../src/main/java/com/company/observability/service/CalculatorStateService.java#L159-L163)) — the WP11 "unknown run_number" guard, misfiring.
4. `market-risk` is `Dimension.NONE`, so `ExpectedRunsService.padToExpected` skips it entirely (`continue`, [line 64](../../src/main/java/com/company/observability/service/ExpectedRunsService.java#L64)) → **response contains `runs: []`**.

**Severity differs by archetype** — `market-risk` (NONE) is worst: nothing pads it, the entry is simply gone. `modelled-exposure` / `gemini-hedge` (RUN_TYPE) get ETD/OTC/SFT placeholders from `padToExpected`, but because step 3 destroyed the template, `calculatorDeadline` resolves to `null` ([ExpectedRunsService.java:105-123](../../src/main/java/com/company/observability/service/ExpectedRunsService.java#L105-L123)) → placeholders come back with **`sla: null` and a meaningless `slaStatus: ON_TIME`**, plus no estimates when the dimension profile is thin. Degraded rather than absent, from the same single cause.

Without `run_number` on the query, all three are healthy — the already-landed thin-history fix covers that path. **The bug requires a `run_number` param**, which is consistent with a dashboard that passes it globally.

### Correction to my earlier answer on `marketriskrwacalcdev`

I previously said the snippet would not help `marketriskrwacalcdev`, reasoning it is "never queried with a meaningful run_number." That was an assumption about caller behaviour, not a fact about the code — and it was wrong to state it that confidently. The defect is keyed on **run-number-awareness**, not on archetype, and `market-risk` is agnostic exactly like `modelled-exposure`. When queried with `run_number`, it hits the identical guard and is the most severely affected of the three. **Yes — the snippet's intent fixes `marketriskrwacalcdev`.** (The snippet's *SQL as pasted* would still break it — see below.)

---

## Design evaluation — pasted snippet vs. the chosen approach

### Option A — the pasted snippet (`includeNullRunNumberWhenScoped` flag) — rejected

```java
findLatestRunEstimatesByName(name, freq, runNumber, lookbackDays,
        runNumber != null && !nameResolver.isRunNumberAware(name))   // ← 5th param
```

It identifies the right root cause, but four problems:

1. **It reverts a landed fix.** The snippet's SQL reads `AND expected_duration_ms IS NOT NULL`. Commit `1f614fa` deliberately changed that to `estimated_end_time IS NOT NULL`. Applying it re-opens the thin-history bug for `marketriskrwacalcdev` *and* desyncs the query from the `V10` partial index (built `WHERE estimated_end_time IS NOT NULL`), so Postgres silently stops using it.
2. **Boolean-trap parameter.** `findLatestRunEstimatesByName(name, freq, "1", 30, true)` is unreadable at the call site, and it pushes a *policy* decision (what run-number-awareness means) into the repository's signature. Every future caller must re-derive the flag correctly or reintroduce the bug.
3. **It leaves the guard half-fixed.** The `if (runNumber != null && latest == null) → empty` check is untouched. The OR-NULL query masks it only while raw history exists in the lookback window. A calculator whose only run is its first-ever (`estimated_end_time` still null) has `latest == null` → still an empty entry, even though the Tier-2 `RECENT_EXACT` profile could have produced estimates.
4. **It makes an agnostic calc's answer depend on an ignored parameter.** `(run_number = :runNumber OR run_number IS NULL)` against *legacy* rows written before the agnostic guard (pre-`0fb5cf1`, which may still carry a stray `run_number`) returns a different row for `run_number=1` than for `run_number=2` — on a calculator whose documented contract is "`run_number` is effectively ignored" ([RunQueryController.java:56](../../src/main/java/com/company/observability/controller/RunQueryController.java#L56)).

### Option B — effective-run_number normalization — **chosen**

```java
String effRn = nameResolver.isRunNumberAware(name) ? runNumber : null;
```

- **Zero repository change.** `effRn == null` → `scoped == false` → the existing method already omits the clause and returns the latest across all run numbers. No signature change, no SQL edit, no chance of clobbering the `estimated_end_time` predicate or the `V10` index.
- **Verbatim the established idiom** — `CalculatorProfileService.java:151` is literally `String effRn = nameResolver.isRunNumberAware(calculatorName) ? runNumber : null;`. Introduces no new concept.
- **Fixes the guard for free.** `effRn != null && latest == null` makes the WP11 guard fire only when the filter is real, so an agnostic calc falls through to the profile path and can still project from a `RECENT_EXACT` profile.
- **Coherent with the contract.** An agnostic calculator ignores `run_number` completely, including legacy contaminated rows.

Truth table (identical to Option A on clean data, better on legacy/thin data):

| calculator | `run_number` | lookup | guard |
|---|---|---|---|
| aware (`capital`) | `1` | scoped | fires if no rn=1 history (**WP11 preserved**) |
| aware | `null` | unscoped | never |
| **agnostic (`market-risk`)** | **`1`** | **unscoped ← the fix** | **never** |
| agnostic | `null` | unscoped | never |

**Net diff: one field + ~4 lines in one file.** Nothing else in the pipeline needs to change — `getProfile(name, freq, runNumber)` at [line 174](../../src/main/java/com/company/observability/service/CalculatorStateService.java#L174) already normalizes internally, which is why it was never broken.

### Deliberately out of scope (verified non-issues, do not "fix")

- **`entryWithSyntheticRun(…, runNumber)` keeps stamping the raw `runNumber`.** Changing only this side would make the template (`runNumber=null`) disagree with `ExpectedRunsService.placeholder`'s stamp ([line 198](../../src/main/java/com/company/observability/service/ExpectedRunsService.java#L198)) — worse than the current consistent-but-cosmetic oddity. Verified it cannot drop a real row: the `numberedDims` fold in `mergeEntries` needs a synthetic and a real row under one alias, which requires a multi-name alias; every agnostic alias is single-name (`market-risk: [marketriskrwacalcdev]`), and the only multi-name alias (`capital`, prod) is run-number-**aware** → takes the `strict` branch by design.
- **The `GLB3` cleanup SQL** from `thin-history-estimate-fallback-fix.md` §Out of scope — still a separate production-data decision.

---

## Aggregation impact: none — and the aggregation confirms the design

**The fix is read-only.** It writes nothing to `calculator_runs`, nothing to `calculator_sli_daily`, and nothing to the profile cache. `DailyAggregationJob` / `recomputeForDateRange` are untouched.

More usefully, the aggregation **independently corroborates the fix's core assumption**. The recompute buckets by `COALESCE(run_number, 'ALL')` ([DailyAggregateRepository.java:66](../../src/main/java/com/company/observability/repository/DailyAggregateRepository.java#L66)), so an agnostic calculator's history lands entirely in the `'ALL'` bucket — and the matching read, `findProfile`, carries **no `run_number` filter at all** ([lines 186-197](../../src/main/java/com/company/observability/repository/DailyAggregateRepository.java#L186-L197)), summing every bucket. The aggregate's read path for an agnostic calculator already ignores `run_number` completely. `effRn` makes the latest-run lookup do exactly the same thing. The pasted snippet's `OR run_number IS NULL` would instead half-filter — matching neither the aggregate's behaviour nor the endpoint's contract.

Two aggregation-side observations found while tracing (**neither caused by nor blocking this fix**):

- **Tier-1 and Tier-2 disagree on `run_number IS NULL` for the blended profile.** `findProfile` (Tier 1) ignores `run_number`; `findRecentExactBlended` → `findRecentExact(…, null, null)` (Tier 2) resolves to `AND run_number IS NULL` ([line 454](../../src/main/java/com/company/observability/repository/DailyAggregateRepository.java#L454)), so it *excludes* legacy rows carrying a stray `run_number`. Latent, not live: Tier 2 only runs when Tier 1 returns zero samples, and Tier 1 would have found those rows. The one real hole — a calculator whose only history predates the agnostic ingestion guard (`0fb5cf1`) and has no aggregate row yet — is **closed by this fix anyway**, via the 1b latest-run fallback, which the empty-entry guard used to skip.
- **`findAllProfilesByRunNumber` excludes `run_number = 'ALL'`** by design ([line 264](../../src/main/java/com/company/observability/repository/DailyAggregateRepository.java#L264)), so the nightly job never warms a run_number-scoped cache key for an agnostic calculator. Correct, and consistent: the routed read never looks one up.

## Estimate handling: sourcing is correct, anchoring is not

**Sourcing — correct, and now reachable.** With `latest` no longer null, both estimate paths work for an agnostic calculator:
1. `profileService.getProfile(name, freq, runNumber)` ([line 174](../../src/main/java/com/company/observability/service/CalculatorStateService.java#L174)) passes the **raw** `runNumber` deliberately — it normalizes internally, which is why the profile path never had this bug — and returns the blended profile. `hasAnySample()` (post-`1f614fa`) means 1 sample is enough → `estStart = instantFromUtcMinuteOfDay(executionDate, avgStartMinUtc)`, `estEnd = estStart + avgDurationMs`.
2. When the profile is empty, the 1b fallback reads `latest.getEstimatedStartTime()/getEstimatedEndTime()` ([lines 199-213](../../src/main/java/com/company/observability/service/CalculatorStateService.java#L199-L213)) — leniently populated on every run. Previously unreachable for these calculators, because the guard returned before it.

**Anchoring — a real gap this plan originally missed.** Both estimates are anchored on `executionDate = nextBusinessDay(date, deriveOffsetDays(latest, freq, runNumber))`, and `deriveOffsetDays` takes the **raw** `runNumber`. For **MONTHLY it always falls back to `parseRunNumber(runNumber)`** ([line 241](../../src/main/java/com/company/observability/service/CalculatorStateService.java#L241)); for DAILY it falls back whenever `latest.slaTime` is null (a thin-history calculator with a blank SLA spec is ungraded → `sla_time` null). So `marketriskrwacalcdev` (MONTHLY, agnostic), reporting date 2026-04-30 (a Thursday):

| query | `parseRunNumber` | `executionDate` | `estimatedStartTime` |
|---|---|---|---|
| `?run_number=1` | 1 | Fri 2026-05-01 | anchored Fri |
| `?run_number=2` | 2 | Mon 2026-05-04 | anchored Mon |
| *(no `run_number`)* | 2 (default) | Mon 2026-05-04 | anchored Mon |

Same calculator, same reporting date, **estimate moves 3 calendar days** on a parameter the contract calls "effectively ignored" — and it cascades into `projectSlaTime`, which for MONTHLY derives the deadline from `estStart` ([line 272](../../src/main/java/com/company/observability/service/CalculatorStateService.java#L272)). Fixing the lookup while leaving the anchor raw would resurrect the entry with a run_number-dependent estimate: a subtler version of the same bug.

**The original "out of scope" reasoning was wrong.** It argued that passing `effRn` "changes behaviour beyond the bug" — but for the agnostic + `run_number` path there *is* no prior behaviour to preserve: the entry was **empty**. The only case that changes is agnostic + `run_number` matching a legacy contaminated row, which is degenerate data and arguably improved. The decisive test is the contract itself: with `effRn`, the `?run_number=1` answer becomes **identical** to the no-`run_number` answer. That is what "effectively ignored" means. Hence **Task 4**.

**Known limitation, not fixed here:** for MONTHLY the T+N anchor is a heuristic that does not model reality — `application.yml` itself notes MONTHLY runs execute "Day 1-15 of next month", so neither T+1 nor T+2 is truly right. Pre-existing, unaffected by this fix. Worth a tech-debt entry, not a change in this plan.

---

## Task 1: Add the failing test for the agnostic-calculator regression

**Files:**
- Modify: `src/test/java/com/company/observability/service/CalculatorStateServiceTest.java:33-75` (mock + setUp)
- Modify: `src/test/java/com/company/observability/service/CalculatorStateServiceTest.java` (new test at end of the not-started section, after line 400)

**Step 1: Add the `CalculatorNameResolver` mock and its setUp default**

Add the mock field after the `profileService` mock (line 39-40):

```java
    @Mock
    CalculatorNameResolver nameResolver;
```

In `setUp()`, update the constructor call and add the awareness default:

```java
        service = new CalculatorStateService(
                runRepository, new SlaProperties(),
                stateCache, profileService, nameResolver, clock);
```

```java
        // Every pre-existing test uses an unconfigured calculator name ("calc") while asserting
        // run_number-scoped behaviour, so the suite's implicit contract is "aware". Default to
        // aware here to keep those tests meaning what they meant; the agnostic tests override.
        lenient().when(nameResolver.isRunNumberAware(anyString())).thenReturn(true);
```

**Step 2: Write the failing test**

Append after `notStartedEntry_noProfileNoLatestRun_returnsEmptyEntry` (line 400):

```java
    // ── run-number-agnostic calculators ignore the run_number filter ─────────

    /**
     * A run-number-agnostic calculator (market-risk, modelled-exposure, gemini-hedge) queried with
     * an explicit run_number must ignore the filter: ingestion nulls a stray run_number, so its
     * entire history is un-numbered and a scoped lookup matches zero rows — which made the WP11
     * unknown-run-number guard wrongly return an empty entry. Regression for the missing-entry bug
     * on marketriskrwacalcdev / modelledexposurecalcdev / geminihedgefundcalcdev.
     */
    @Test
    void notStartedEntry_agnosticCalculator_ignoresRunNumberFilter() {
        when(nameResolver.isRunNumberAware("agnostic-calc")).thenReturn(false);

        CalculatorProfile profile =
                new CalculatorProfile("agnostic-calc", "DAILY", null, null, 3_600_000L, 540, 600, 3);
        when(profileService.getProfile(eq("agnostic-calc"), eq(FREQ), eq("1"))).thenReturn(profile);

        // All real history is un-numbered → only the UNSCOPED lookup can find it.
        CalculatorRun latest = new CalculatorRun();
        latest.setCalculatorId("mr-id");
        latest.setCalculatorName("agnostic-calc");
        latest.setReportingDate(DATE);
        latest.setSlaTime(Instant.parse("2026-03-10T15:00:00Z"));
        when(runRepository.findLatestRunEstimatesByName(eq("agnostic-calc"), eq(FREQ), isNull(), anyInt()))
                .thenReturn(Optional.of(latest));
        when(runRepository.findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), eq("1"), any()))
                .thenReturn(List.of());

        var entries = service.getState(DATE, FREQ, "1", List.of("agnostic-calc"), false)
                .get("agnostic-calc").runs();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).status()).isEqualTo("NOT_STARTED");
        assertThat(entries.get(0).estimatedStartTime()).isNotNull();
        assertThat(entries.get(0).sla()).isNotNull();
        // The scoped lookup must never be issued for an agnostic calculator.
        verify(runRepository, never())
                .findLatestRunEstimatesByName(anyString(), any(Frequency.class), eq("1"), anyInt());
    }
```

**Step 3: Run the test to verify it fails**

```bash
mvn test -Dtest=CalculatorStateServiceTest#notStartedEntry_agnosticCalculator_ignoresRunNumberFilter
```

Expected: **compile error** first — `CalculatorStateService` has no 6-arg constructor. That is the honest first failure; it confirms the missing dependency. After Task 2's field is added, the expected failure becomes `AssertionError: Expected size: 1 but was: 0` (empty entry from the misfiring guard).

**Step 4: Commit the failing test**

```bash
git add src/test/java/com/company/observability/service/CalculatorStateServiceTest.java
git commit -m "test: agnostic calculator must ignore run_number filter on NOT_STARTED projection"
```

---

## Task 2: Inject `CalculatorNameResolver` into `CalculatorStateService`

**Files:**
- Modify: `src/main/java/com/company/observability/service/CalculatorStateService.java:36-40`

**Step 1: Add the field**

`@RequiredArgsConstructor` generates the constructor in declaration order — place `nameResolver` after `profileService` so the generated signature matches Task 1's test call `(runRepository, slaProperties, stateCache, profileService, nameResolver, clock)`:

```java
    private final CalculatorRunRepository runRepository;
    private final SlaProperties slaProperties;
    private final CalculatorStateCacheService stateCache;
    private final CalculatorProfileService profileService;
    private final CalculatorNameResolver nameResolver;
    private final Clock clock;
```

No import needed — `CalculatorNameResolver` is in the same package (`com.company.observability.service`). No circular-dependency risk: `CalculatorNameResolver` depends only on `CalculatorProperties`, and `RunQueryController` already injects both together.

**Step 2: Run the test to confirm the failure has moved to the real assertion**

```bash
mvn test -Dtest=CalculatorStateServiceTest#notStartedEntry_agnosticCalculator_ignoresRunNumberFilter
```

Expected: compiles; FAILS with `Expected size: 1 but was: 0` — the guard still misfires. This is the bug, now pinned by a test.

**Step 3: Commit**

```bash
git add src/main/java/com/company/observability/service/CalculatorStateService.java
git commit -m "refactor: inject CalculatorNameResolver into CalculatorStateService"
```

---

## Task 3: Apply the effective-run_number normalization

**Files:**
- Modify: `src/main/java/com/company/observability/service/CalculatorStateService.java:151-163`

**Step 1: Write the minimal implementation**

Replace lines 151-163 with:

```java
    private CalculatorEntry buildNotStartedEntry(String name, LocalDate date, Frequency freq,
                                                  String runNumber) {
        // run_number is a real scoping dimension only for run-number-aware calculators. An agnostic
        // calculator's history is entirely un-numbered (ingestion nulls a stray run_number), so the
        // un-scoped latest run IS its exact slice — scoping it would match zero rows and strand the
        // calculator on the guard below. Mirrors CalculatorProfileService.getProfile and
        // RunQueryController.mergeEntries.
        String effRn = nameResolver.isRunNumberAware(name) ? runNumber : null;

        // ── Latest run (run_number-scoped: a RUN1 projection must not borrow RUN2's frozen deadline) ──
        CalculatorRun latest = runRepository.findLatestRunEstimatesByName(
                name, freq, effRn, slaProperties.lookbackDays(freq)).orElse(null);

        // A real run_number filter with zero scoped history is not an expected run — projecting one
        // would invent a bucket (e.g. run_number=99 → a confident T+99 deadline). Empty is honest.
        // Keyed off effRn, not runNumber: an agnostic calculator has no cycle to be unknown.
        if (effRn != null && latest == null) {
            log.debug("event=batch_runs.not_started source=none reason=unknown_run_number calculator={} runNumber={}",
                    name, runNumber);
            return new CalculatorEntry(name, null, List.of());
        }
```

Everything below line 163 stays untouched. `getProfile(name, freq, runNumber)` at line 174 deliberately keeps the **raw** `runNumber` — it normalizes internally ([CalculatorProfileService.java:101](../../src/main/java/com/company/observability/service/CalculatorProfileService.java#L101)), which is exactly why the profile path never had this bug.

**Step 2: Run the new test**

```bash
mvn test -Dtest=CalculatorStateServiceTest#notStartedEntry_agnosticCalculator_ignoresRunNumberFilter
```

Expected: **PASS**

**Step 3: Run the whole class — WP11 and the aware-calculator tests must be untouched**

```bash
mvn test -Dtest=CalculatorStateServiceTest
```

Expected: PASS, all tests. Specifically verify these still pass (they encode the behaviour that must NOT change):
- `unknownRunNumber…` (line ~444) — aware + rn=99, no history → still an empty entry (WP11).
- `notStartedEntry_…scopedByRunNumber` (line ~430) — RUN1 must not borrow RUN2's deadline.
- `notStartedEntry_anchorsEstimatesToNextBusinessDay` (line ~298) — unchanged T+1 anchoring.

If any fail, the `isRunNumberAware → true` default from Task 1 Step 1 is missing or mis-stubbed — fix the stub, **do not** relax the assertion.

**Step 4: Commit**

```bash
git add src/main/java/com/company/observability/service/CalculatorStateService.java
git commit -m "fix: ignore run_number filter for agnostic calculators in NOT_STARTED projection

An agnostic calculator's history is entirely un-numbered, so scoping the
latest-run lookup by a client-supplied run_number matched zero rows and the
unknown-run-number guard returned an empty entry — dropping market-risk
entirely and stripping the SLA deadline from modelled-exposure/gemini-hedge
placeholders. Normalize to an effective run_number, as CalculatorProfileService
and RunQueryController already do."
```

---

## Task 4: Anchor the projection on the effective run_number

Without this, Task 3 resurrects the entry but hands back an estimate whose date depends on the `run_number` it is supposed to ignore. See §"Estimate handling" above.

**Files:**
- Modify: `src/test/java/com/company/observability/service/CalculatorStateServiceTest.java` (new test)
- Modify: `src/main/java/com/company/observability/service/CalculatorStateService.java:167` (call site) and `:239-249` (javadoc)

**Step 1: Write the failing test**

The assertion *is* the contract: for an agnostic calculator, the `run_number` query must equal the no-`run_number` query.

```java
    /**
     * An agnostic calculator's projection must anchor identically whether or not run_number is
     * supplied — "effectively ignored" means the answer cannot move. MONTHLY is the sharp case:
     * deriveOffsetDays always falls back to parseRunNumber, so a raw run_number=1 would anchor
     * T+1 while the same query without run_number anchors T+2 (parseRunNumber's null default).
     */
    @Test
    void notStartedEntry_agnosticCalculator_anchorIsIndependentOfRunNumber() {
        when(nameResolver.isRunNumberAware("agnostic-calc")).thenReturn(false);
        LocalDate eom = LocalDate.of(2026, 4, 30); // Thursday → T+1 = Fri 05-01, T+2 = Mon 05-04

        CalculatorProfile profile =
                new CalculatorProfile("agnostic-calc", "MONTHLY", null, null, 3_600_000L, 540, 600, 3);
        lenient().when(profileService.getProfile(eq("agnostic-calc"), eq(Frequency.MONTHLY), any()))
                .thenReturn(profile);

        CalculatorRun latest = new CalculatorRun();
        latest.setCalculatorName("agnostic-calc");
        latest.setReportingDate(eom);
        lenient().when(runRepository.findLatestRunEstimatesByName(
                        eq("agnostic-calc"), eq(Frequency.MONTHLY), isNull(), anyInt()))
                .thenReturn(Optional.of(latest));
        lenient().when(runRepository.findAllRunsByDateAndDimension(
                        eq(eom), eq(Frequency.MONTHLY), any(), any()))
                .thenReturn(List.of());

        var withRn = service.getState(eom, Frequency.MONTHLY, "1", List.of("agnostic-calc"), false)
                .get("agnostic-calc").runs();
        var withoutRn = service.getState(eom, Frequency.MONTHLY, null, List.of("agnostic-calc"), false)
                .get("agnostic-calc").runs();

        assertThat(withRn).hasSize(1);
        assertThat(withoutRn).hasSize(1);
        assertThat(withRn.get(0).estimatedStartTime())
                .isEqualTo(withoutRn.get(0).estimatedStartTime());
        // Both must land on the T+2 default anchor (Mon 2026-05-04 09:00Z), not run_number=1's T+1.
        assertThat(withRn.get(0).estimatedStartTime())
                .isEqualTo(Instant.parse("2026-05-04T09:00:00Z"));
    }
```

**Step 2: Run it to verify it fails**

```bash
mvn test -Dtest=CalculatorStateServiceTest#notStartedEntry_agnosticCalculator_anchorIsIndependentOfRunNumber
```

Expected: FAIL — `expected 2026-05-04T09:00:00Z but was 2026-05-01T09:00:00Z`. That 3-calendar-day gap is the bug.

**Step 3: Pass `effRn` to `deriveOffsetDays`**

At line 167:

```java
        int offsetDays = deriveOffsetDays(latest, freq, effRn);
```

Update the javadoc at lines 239-249, replacing the `parseRunNumber(runNumber)` sentence:

```java
     * {@code parseRunNumber(effRn)} when no usable latest run exists — the EFFECTIVE run_number, so
     * an agnostic calculator (whose run_number is ignored everywhere else) cannot have its anchor
     * shifted a business day by a parameter that means nothing to it.
```

The parameter is already named `runNumber` inside `deriveOffsetDays`; leave the method signature alone — the caller decides what is effective, exactly as it does for the lookup.

**Step 4: Run the test, then the class**

```bash
mvn test -Dtest=CalculatorStateServiceTest#notStartedEntry_agnosticCalculator_anchorIsIndependentOfRunNumber
mvn test -Dtest=CalculatorStateServiceTest
```

Expected: both PASS. `derivesOffsetFromLatestRunSlaDistance` (line ~407) must still pass — it uses an aware-by-default name, so `effRn == runNumber` and the DAILY derived-offset path is unchanged.

**Step 5: Commit**

```bash
git add src/main/java/com/company/observability/service/CalculatorStateService.java \
        src/test/java/com/company/observability/service/CalculatorStateServiceTest.java
git commit -m "fix: anchor agnostic calculators' NOT_STARTED projection on the effective run_number

deriveOffsetDays fell back to parseRunNumber(rawRunNumber), so an agnostic
MONTHLY calculator queried with run_number=1 anchored its estimate T+1 while
the same query without run_number anchored T+2 — a 3-calendar-day swing driven
by a parameter the endpoint contract calls 'effectively ignored', cascading
into the MONTHLY projected SLA."
```

---

## Task 5: Cover the degraded RUN_TYPE case end-to-end

**Files:**
- Modify: `src/test/java/com/company/observability/controller/RunQueryControllerTest.java`

**Step 1: Write the test**

This proves the *second* symptom — that an agnostic `RUN_TYPE` calculator's padded placeholders regain their SLA deadline, which the `CalculatorStateService` unit test cannot show (padding happens in the controller). Follow the existing mocking conventions in that file; assert that for `keys=modelled-exposure&run_number=1` with no runs on the date, the ETD/OTC/SFT placeholders come back with a **non-null `sla`**.

**Step 2: Run it**

```bash
mvn test -Dtest=RunQueryControllerTest
```

Expected: PASS.

**Step 3: Commit**

```bash
git add src/test/java/com/company/observability/controller/RunQueryControllerTest.java
git commit -m "test: RUN_TYPE placeholders keep their SLA deadline when queried with run_number"
```

---

## Task 6: Full suite + manual verification

**Step 1: Run the full suite**

```bash
docker compose up -d
SPRING_PROFILES_ACTIVE=local mvn clean test
```

Expected: BUILD SUCCESS. Pay attention to `CalculatorProfileServiceTest`, `ExpectedRunsServiceTest`, and `RunQueryControllerTest` — the three suites that touch the same run_number semantics.

**Step 2: Verify against the real endpoint**

REQUIRED SUB-SKILL: use the `verify` skill to drive the running app.

```bash
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

The **before/after** check that matters — the same request that returned `runs: []`:

```bash
curl -u admin:admin -H "X-Tenant-Id: t1" \
  "http://localhost:8080/api/v1/calculators/batch/runs?reporting_date=2026-04-30&frequency=MONTHLY&run_number=1&keys=market-risk&nocache=true"
```

Expected after the fix: a `NOT_STARTED` entry with non-null `estimatedStartTime`/`estimatedEndTime` — **not** `"runs": []`. Use `nocache=true`: `CalculatorStateCacheService` may still hold the empty entry (60s TTL for NOT_STARTED/empty), which would mask the fix.

Then the Task 4 contract check — drop `run_number` from that same URL and diff the two responses:

```bash
curl -u admin:admin -H "X-Tenant-Id: t1" \
  "http://localhost:8080/api/v1/calculators/batch/runs?reporting_date=2026-04-30&frequency=MONTHLY&keys=market-risk&nocache=true"
```

Expected: `estimatedStartTime`, `estimatedEndTime` and `sla` **identical** to the `run_number=1` response. Any difference means the anchor is still run_number-dependent.

Then the RUN_TYPE case — ETD/OTC/SFT placeholders must now carry a non-null `sla`:

```bash
curl -u admin:admin -H "X-Tenant-Id: t1" \
  "http://localhost:8080/api/v1/calculators/batch/runs?reporting_date=2026-04-30&frequency=DAILY&run_number=1&keys=modelled-exposure&nocache=true"
```

And the regression that must **not** change — an aware calculator with an unknown cycle stays honestly empty:

```bash
curl -u admin:admin -H "X-Tenant-Id: t1" \
  "http://localhost:8080/api/v1/calculators/batch/runs?reporting_date=2026-04-30&frequency=DAILY&run_number=99&keys=capital&nocache=true"
```

Expected: `"runs": []` for `capital` — unchanged.

**Step 3: Record the outcome**

Add a review section to `tasks/todo.md` per CLAUDE.md §Task Management. If any correction was needed along the way, append it to `tasks/lessons.md`.

---

## Open decision — `ExpectedRunsService` has the same raw-`runNumber` anchor

`ExpectedRunsService.pad` computes `int offsetDays = SlaBaselineResolver.parseRunNumber(runNumber)` from the raw value ([line 129](../../src/main/java/com/company/observability/service/ExpectedRunsService.java#L129)) — the identical gap Task 4 closes in `CalculatorStateService`. It is **mostly masked**: lines 130-137 override the offset from `calculatorDeadline` for DAILY, and Task 3 restores that deadline by resurrecting the template. The exposure is narrow — **MONTHLY `modelled-exposure`/`gemini-hedge` placeholders**, where the override never runs.

Not folded into this plan because it needs a decision, not just a patch: `ExpectedRunsService` has no `CalculatorNameResolver` (fields: `props`, `profileService`, `slaProps`, `clock`), so closing it means either injecting the resolver or reaching through `props.getRunNumberAware()`. Adjacent and worth doing — but it is a second service, and this plan's diff is currently one field plus ~5 lines. **Recommendation: land Tasks 1-6, verify against real data, then decide.**

Related and deliberately *not* bundled: `placeholder` stamps `.runNumber(runNumber)` raw ([line 198](../../src/main/java/com/company/observability/service/ExpectedRunsService.java#L198)), so an agnostic calculator's placeholders report `runNumber: "1"` while its real rows report `null`. Cosmetic only (verified it cannot drop a row — see §Out of scope), but changing it alters the response body for the dashboard, which is a UI contract question rather than a bug fix.

## Rollout notes

- **No schema change, no migration, no config change.** `V10__latest_estimate_index.sql` stays as-is and keeps serving the unchanged query predicate.
- **Cache:** `obs:state:*` entries written before the deploy can serve a stale empty entry for up to 60s (NOT_STARTED/empty TTL). No flush needed; note it if someone verifies within the first minute.
- **Blast radius:** run-number-aware calculators (`capital`, `portfolio`) take the identical code path as before — `effRn == runNumber` for them.
