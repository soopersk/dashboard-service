# Effective run_number normalization for synthetic NOT_STARTED entries

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Stop `/batch/runs` dropping the synthetic NOT_STARTED entry for run-number-**agnostic** calculators when the request carries a `run_number`.

**Architecture:** `CalculatorStateService.buildNotStartedEntry` scopes its latest-run lookup by `run_number` unconditionally. It is the only run_number consumer that never asks whether the calculator is run-number-aware. Normalize to an *effective* run_number at the top of the method — `isRunNumberAware(name) ? runNumber : null` — reusing the idiom already established in `CalculatorProfileService` and `RunQueryController`. No repository change.

**Tech Stack:** Java 17, Spring Boot 3.5.9, JUnit 5 + Mockito, AssertJ, Maven.

---

## Problem statement

`GET /api/v1/calculators/batch/runs` with a `run_number` parameter returns **no run entry** for calculators that do not use run numbers, on any reporting date where the calculator has not started yet.

Reported for `marketriskrwacalcdev`, `modelledexposurecalcdev`, `geminihedgefundcalcdev`. The same request **without** `run_number` is healthy — so the trigger is a dashboard that passes `run_number` uniformly for every calculator.

The symptom presents differently by archetype, from one shared cause:

| calculator | archetype | symptom |
|---|---|---|
| `market-risk` → `marketriskrwacalcdev` | `NONE` | **entry gone** — `"runs": []`. `ExpectedRunsService.padToExpected` skips `NONE` calculators ([line 64](../../src/main/java/com/company/observability/service/ExpectedRunsService.java#L64)), so nothing backfills it. |
| `modelled-exposure`, `gemini-hedge` | `RUN_TYPE` | **entry degraded** — ETD/OTC/SFT placeholders still appear, but with `sla: null` and a meaningless `slaStatus: ON_TIME`, plus no estimates when the dimension profile is thin. |

## Root cause

`run_number` is a scoping dimension **only** for calculators declared in `observability.calculator.run-number-aware` — `capital` and `portfolio` ([application.yml:157](../../src/main/resources/application.yml#L157)). For every other calculator, ingestion nulls a stray `run_number` and stashes it in `additional_attributes` ([RunIngestionService.java:125-128](../../src/main/java/com/company/observability/service/RunIngestionService.java#L125-L128)), so **their entire history has `run_number IS NULL`**.

Four code paths consume `run_number`. Three honour that rule; one does not:

| consumer | honours awareness? |
|---|---|
| `CalculatorProfileService.getProfile` (3-arg / 4-arg) | ✅ [line 101](../../src/main/java/com/company/observability/service/CalculatorProfileService.java#L101), [line 151](../../src/main/java/com/company/observability/service/CalculatorProfileService.java#L151) |
| `RunQueryController.mergeEntries` | ✅ [line 168](../../src/main/java/com/company/observability/controller/RunQueryController.java#L168) |
| `CalculatorRunRepository.findAllRunsByDateAndDimension` | ✅ lenient `OR run_number IS NULL` ([line 459](../../src/main/java/com/company/observability/repository/CalculatorRunRepository.java#L459)) |
| **`CalculatorStateService.buildNotStartedEntry`** | ❌ **unconditional scoping** ([lines 154-163](../../src/main/java/com/company/observability/service/CalculatorStateService.java#L154-L163)) |

`CalculatorStateService` holds no `CalculatorNameResolver` reference ([lines 36-40](../../src/main/java/com/company/observability/service/CalculatorStateService.java#L36-L40)), so it structurally cannot make the distinction today.

**Failure trace** — `?run_number=1&keys=market-risk`, on a date with no run:

1. `runs.isEmpty()` → `buildNotStartedEntry(name, date, freq, "1")` ([line 107-108](../../src/main/java/com/company/observability/service/CalculatorStateService.java#L107-L108)).
2. `findLatestRunEstimatesByName(name, freq, "1", …)` → `scoped = true` → `AND run_number = '1'` → matches **0 rows**, because all history is `NULL` → `latest = null`.
3. `runNumber != null && latest == null` → returns `new CalculatorEntry(name, null, List.of())` ([lines 159-163](../../src/main/java/com/company/observability/service/CalculatorStateService.java#L159-L163)).

Step 3 is the WP11 "unknown run_number" guard, which exists for a real case — an *aware* calculator asked for a cycle it has never run (`run_number=99`) must not invent a T+99 deadline. It misfires here because it cannot tell "unknown cycle" from "calculator has no cycles at all."

The empty entry then cascades: `buildNotStartedEntry` also supplies the **template** that `ExpectedRunsService.pad` reads its `calculatorDeadline` from ([lines 105-123](../../src/main/java/com/company/observability/service/ExpectedRunsService.java#L105-L123)). No template → no deadline → the RUN_TYPE placeholders lose their `sla`. One cause, both symptoms.

## Design

Normalize the run_number to its *effective* value at the top of `buildNotStartedEntry`, and key both the lookup and the guard off it:

```java
String effRn = nameResolver.isRunNumberAware(name) ? runNumber : null;
```

| calculator | `run_number` | lookup | guard |
|---|---|---|---|
| aware (`capital`) | `1` | scoped | fires if no rn=1 history (**WP11 preserved**) |
| aware | `null` | unscoped | never |
| **agnostic (`market-risk`)** | **`1`** | **unscoped ← the fix** | **never** |
| agnostic | `null` | unscoped | never |

Why this shape:

- **No repository change.** `effRn == null` → `scoped == false` → the existing method already omits the clause and returns the latest across all run numbers. The `estimated_end_time IS NOT NULL` predicate and its `V10` partial index stay untouched.
- **Established idiom.** [CalculatorProfileService.java:151](../../src/main/java/com/company/observability/service/CalculatorProfileService.java#L151) is literally `String effRn = nameResolver.isRunNumberAware(calculatorName) ? runNumber : null;`. No new concept.
- **Fixes the guard for free.** `effRn != null && latest == null` makes WP11 fire only when the filter is real, so an agnostic calculator falls through to the profile path and can still project from a `RECENT_EXACT` profile when the raw-run lookback has nothing.
- **Matches the documented contract** — "With `run_number` on an agnostic calculator, run_number is effectively ignored" ([RunQueryController.java:56](../../src/main/java/com/company/observability/controller/RunQueryController.java#L56)) — and matches the aggregate, whose agnostic read (`findProfile`) carries no `run_number` filter at all.

`getProfile(name, freq, runNumber)` at [line 174](../../src/main/java/com/company/observability/service/CalculatorStateService.java#L174) keeps the **raw** `runNumber` — it normalizes internally, which is why the profile path never had this bug.

### Rejected: the `includeNullRunNumberWhenScoped` flag

Adding a 5th boolean parameter to `findLatestRunEstimatesByName` was considered and rejected. It reverts commit `1f614fa` (its SQL reads `expected_duration_ms IS NOT NULL`, re-opening the thin-history bug and desyncing the `V10` index); it is a boolean trap that pushes awareness policy into the repository signature; it leaves the guard half-fixed; and its `OR run_number IS NULL` makes an agnostic calculator's answer depend on legacy stray `run_number` values — a parameter its contract says is ignored.

## Scope

**In:** the latest-run lookup and the empty-entry guard in `buildNotStartedEntry`, plus the `CalculatorNameResolver` dependency that makes them possible.

**Explicitly deferred** — tracked separately, do not touch here:

- **MONTHLY anchoring.** `deriveOffsetDays(latest, freq, runNumber)` ([line 167](../../src/main/java/com/company/observability/service/CalculatorStateService.java#L167)) keeps the **raw** `runNumber`. For MONTHLY it always falls back to `parseRunNumber`, so an agnostic MONTHLY calculator's estimate still shifts a business day with the `run_number` it is meant to ignore. Agreed follow-up: anchor MONTHLY at the first business day of the following month (`offsetDays = 1`, which for an end-of-month `reporting_date` is exactly that).
- **MONTHLY projected SLA.** Runs land across the first ~10 business days, so a start-anchored projected deadline flags NOT_STARTED entries `LATE`/`VERY_LATE` well before they are due. Display-only (no alerts fire), pre-existing, unchanged by this plan.
- **`entryWithSyntheticRun(…, runNumber)` stamping.** An agnostic calculator's projection keeps reporting the requested `runNumber` while its real rows report `null`. Verified it cannot drop a row — the `numberedDims` fold in `mergeEntries` needs a synthetic and a real row under one alias, which requires a multi-name alias; every agnostic alias is single-name (`market-risk: [marketriskrwacalcdev]`), and the only multi-name alias (`capital`, prod) is run-number-**aware** and takes the `strict` branch by design. Cosmetic, and changing it is a response-body contract question.
- **`ExpectedRunsService.pad`'s raw `parseRunNumber(runNumber)`** ([line 129](../../src/main/java/com/company/observability/service/ExpectedRunsService.java#L129)) — same MONTHLY anchoring topic, second service.

**No schema change, no migration, no config change.**

---

## Task 1: Add the failing test

**Files:**
- Modify: `src/test/java/com/company/observability/service/CalculatorStateServiceTest.java:33-75` (mock + setUp)
- Modify: `src/test/java/com/company/observability/service/CalculatorStateServiceTest.java` (new test after line 400)

**Step 1: Add the `CalculatorNameResolver` mock**

After the `profileService` mock (lines 39-40):

```java
    @Mock
    CalculatorNameResolver nameResolver;
```

**Step 2: Update `setUp()`**

Update the constructor call and add the awareness default:

```java
        service = new CalculatorStateService(
                runRepository, new SlaProperties(),
                stateCache, profileService, nameResolver, clock);
```

```java
        // Every pre-existing test uses an unconfigured name ("calc") while asserting run_number-
        // scoped behaviour, so the suite's implicit contract is "aware". Default to aware to keep
        // those tests meaning what they meant; the agnostic test overrides.
        lenient().when(nameResolver.isRunNumberAware(anyString())).thenReturn(true);
```

**Step 3: Write the test**

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
        latest.setSlaTime(SLA_TIME);
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

Assert `isNotNull()` on the estimate rather than an exact instant: *whether* the entry projects is this fix's concern; *where* it anchors is the deferred MONTHLY work, and pinning it here would make that follow-up look like a regression.

**Step 4: Run it to verify it fails**

```bash
mvn test -Dtest=CalculatorStateServiceTest#notStartedEntry_agnosticCalculator_ignoresRunNumberFilter
```

Expected: **compile error** — `CalculatorStateService` has no 6-arg constructor. That is the honest first failure; it confirms the missing dependency. Task 2 turns it into a real assertion failure.

**Step 5: Commit**

```bash
git add src/test/java/com/company/observability/service/CalculatorStateServiceTest.java
git commit -m "test: agnostic calculator must ignore run_number filter on NOT_STARTED projection"
```

---

## Task 2: Inject `CalculatorNameResolver`

**Files:**
- Modify: `src/main/java/com/company/observability/service/CalculatorStateService.java:36-40`

**Step 1: Add the field**

`@RequiredArgsConstructor` generates the constructor in declaration order — place `nameResolver` after `profileService` so the signature matches Task 1's call:

```java
    private final CalculatorRunRepository runRepository;
    private final SlaProperties slaProperties;
    private final CalculatorStateCacheService stateCache;
    private final CalculatorProfileService profileService;
    private final CalculatorNameResolver nameResolver;
    private final Clock clock;
```

No import needed — `CalculatorNameResolver` is in the same package. No circular-dependency risk: it depends only on `CalculatorProperties`, and `RunQueryController` already injects both together.

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

## Task 3: Apply the normalization

**Files:**
- Modify: `src/main/java/com/company/observability/service/CalculatorStateService.java:151-163`

**Step 1: Write the implementation**

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

Everything from line 164 onward is unchanged — including `deriveOffsetDays(latest, freq, runNumber)`, which deliberately keeps the raw value (see §Scope).

**Step 2: Run the new test**

```bash
mvn test -Dtest=CalculatorStateServiceTest#notStartedEntry_agnosticCalculator_ignoresRunNumberFilter
```

Expected: **PASS**

**Step 3: Run the whole class**

```bash
mvn test -Dtest=CalculatorStateServiceTest
```

Expected: PASS, all tests. These three encode behaviour that must **not** change:
- `unknownRunNumber…` (line ~444) — aware + rn=99, no history → still an empty entry (WP11).
- the run_number-scoped latest-run test (line ~430) — RUN1 must not borrow RUN2's deadline.
- `notStartedEntry_anchorsEstimatesToNextBusinessDay` (line ~298) — unchanged T+1 anchoring.

If any fail, the `isRunNumberAware → true` default from Task 1 is missing or mis-stubbed. Fix the stub; **do not** relax the assertion.

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

## Task 4: Cover the degraded RUN_TYPE symptom

The unit test proves the entry survives. It cannot prove the second symptom — that the restored template gives `ExpectedRunsService.pad` its `calculatorDeadline` back — because padding happens in the controller.

**Files:**
- Modify: `src/test/java/com/company/observability/controller/RunQueryControllerTest.java`

**Step 1: Write the test**

Following that file's existing mocking conventions: `keys=modelled-exposure&run_number=1`, **DAILY**, no runs on the date. Assert the ETD/OTC/SFT placeholders come back with a non-null `sla`.

Use DAILY, not MONTHLY — the MONTHLY projected-SLA behaviour is deferred (see §Scope) and would make this test assert something we have already agreed is wrong.

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

## Task 5: Full suite + manual verification

**Step 1: Full suite**

```bash
docker compose up -d
SPRING_PROFILES_ACTIVE=local mvn clean test
```

Expected: BUILD SUCCESS. Watch `CalculatorProfileServiceTest`, `ExpectedRunsServiceTest`, and `RunQueryControllerTest` — the suites touching the same run_number semantics.

**Step 2: Drive the endpoint**

REQUIRED SUB-SKILL: use the `verify` skill.

```bash
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

The check that matters — the request that returned `"runs": []`:

```bash
curl -u admin:admin -H "X-Tenant-Id: t1" \
  "http://localhost:8080/api/v1/calculators/batch/runs?reporting_date=2026-04-30&frequency=MONTHLY&run_number=1&keys=market-risk&nocache=true"
```

Expected: a `NOT_STARTED` entry with non-null `estimatedStartTime`/`estimatedEndTime` — **not** `"runs": []`. `nocache=true` is required: `CalculatorStateCacheService` holds the empty entry for 60s and would mask the fix.

The RUN_TYPE symptom — ETD/OTC/SFT placeholders must now carry a non-null `sla`:

```bash
curl -u admin:admin -H "X-Tenant-Id: t1" \
  "http://localhost:8080/api/v1/calculators/batch/runs?reporting_date=2026-04-30&frequency=DAILY&run_number=1&keys=modelled-exposure&nocache=true"
```

The regression that must **not** change — an aware calculator with an unknown cycle stays honestly empty:

```bash
curl -u admin:admin -H "X-Tenant-Id: t1" \
  "http://localhost:8080/api/v1/calculators/batch/runs?reporting_date=2026-04-30&frequency=DAILY&run_number=99&keys=capital&nocache=true"
```

Expected: `"runs": []` for `capital` — unchanged.

**Step 3: Record the outcome**

Add a review section to `tasks/todo.md` per CLAUDE.md §Task Management. If a correction was needed along the way, append it to `tasks/lessons.md`.

---

## Rollout notes

- **Blast radius:** run-number-aware calculators (`capital`, `portfolio`) take the identical path as before — `effRn == runNumber` for them. Agnostic calculators queried without `run_number` are also unchanged — `effRn == null == runNumber`. The only behaviour that moves is agnostic + `run_number`, which is the bug.
- **Cache:** `obs:state:*` entries written before the deploy can serve a stale empty entry for up to 60s (NOT_STARTED/empty TTL). No flush needed; note it if verifying within the first minute.
- **A MONTHLY agnostic calculator's resurrected entry will still carry a start-anchored projected SLA** that can read `LATE`/`VERY_LATE` before the run is due — pre-existing, display-only, and the subject of the deferred MONTHLY work. Expect it in verification; it is not a regression from this change.
