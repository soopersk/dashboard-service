# Phase 2 Follow-up: Event-Driven State-Cache Invalidation + Remaining Review Items

## Context

The previous plan (WP1–WP12) fixed the ingestion-path findings and the independent query-path bugs. This plan closes what was deliberately deferred:

- **2.3-A (High)** — the `/batch/runs` `obs:state` cache is invalidation-free (TTL-only). Every status transition is served stale: NOT_STARTED→RUNNING for up to ~90s (60s Redis TTL + 30s HTTP `Cache-Control`), RUNNING→FAILED/SUCCESS for up to ~60s. For a live SLA dashboard this staleness lands exactly in the minutes that matter. It was deferred until the event flow stabilized — WP1/WP3 changed when and inside which transactions `RunStartedEvent`/`RunCompletedEvent`/`SlaBreachedEvent` fire; that is now settled (per-run `TransactionTemplate` in `LiveSlaBreachDetectionJob`, false-positive completions publish `RunCompletedEvent`).
- **2.3-B (Low)** — the same rows cached under both `rn=all` and `rn={n}` keys diverge within their TTL windows. Solved for free by deleting both keys per event.
- **HTTP cache layer** — flat 30s `max-age` on every response re-adds staleness the eviction removes. User chose **state-aware: 5s live / 30s settled**.
- **2.4-H1 (Low)** — when no duration baseline exists, the frozen SLA deadline is persisted as `estimatedEndTime`, making runs look "planned" to consume their whole SLA budget. User chose to fix: persist null; consumers fall back to the `sla` field.
- **Doc debt** — 1.3-A / 1.3-B tech-debt notes were never written, and CLAUDE.md still describes pre-WP1–WP12 behavior in several places.

**Explicitly excluded** (user decision): stampede single-flight lock (YAGNI), `slaBreached` serialization consistency (response-contract change).

## Work Packages

### WP-A — Event-driven `obs:state` eviction (2.3-A + 2.3-B)

**Files:** `cache/CalculatorStateCacheService.java`, new `cache/CalculatorStateCacheInvalidationListener.java`, `util/ObservabilityConstants.java`

1. **`CalculatorStateCacheService.evictEntry(String calculatorName, LocalDate reportingDate, String frequency, String runNumber)`**: deletes the deterministic keys for a run in one `redisTemplate.delete(keys)` call — always the `:all` variant (`buildKey(name, date, freq, null)`), plus the `:{runNumber}` variant when `runNumber != null`. Best-effort try/catch-warn like every other op in the class; increment a new counter `CACHE_STATE_EVICTION = "obs.cache.state.eviction"` (alongside the existing `CACHE_STATE_HIT/MISS` at `ObservabilityConstants.java:60-61`). No SCAN anywhere — the run carries every key component.
2. **New `CalculatorStateCacheInvalidationListener`** (cache package), mirroring `AnalyticsCacheService`'s listener pattern exactly (`@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async`, see `AnalyticsCacheService.java:90-106`): three handlers for `RunStartedEvent`, `RunCompletedEvent`, `SlaBreachedEvent`, each delegating to one private method:
   ```java
   stateCache.evictEntry(run.getCalculatorName(), run.getReportingDate(),
                         run.getFrequency().name(), run.getRunNumber());
   ```
   - State-cache keys use the **real** `calculator_name` (cache writes happen per real name in `CalculatorStateService`; alias re-grouping is per-request in the controller) — no alias eviction needed, unlike the analytics cache.
   - `SlaBreachedEvent` from the live job / DB sweep fires correctly: WP3 wrapped each `markSlaBreach` + publish in a per-run `TransactionTemplate`, so AFTER_COMMIT semantics hold on all three publication sites.
3. Keep all TTLs unchanged — they become the backstop, not the mechanism. Update the class javadoc in `CalculatorStateCacheService` (currently says "Invalidation is TTL-only — no event listeners").

**Tests** (`CalculatorStateCacheServiceTest` + new listener test):
- `evictEntry` deletes both keys when runNumber is set; only `:all` when null; Redis exception swallowed (no throw).
- Listener unit test: each event type triggers `evictEntry` with the run's name/date/frequency/runNumber (mock `CalculatorStateCacheService`, invoke handler methods directly).

### WP-B — State-aware HTTP `Cache-Control` (RunQueryController)

**File:** `controller/RunQueryController.java` (currently flat `CacheControl.maxAge(30, SECONDS).cachePrivate()` at line ~104)

After padding, scan the final `calculators` map: **live** = any `RunEntry` with status `RUNNING` or `NOT_STARTED`, or any calculator with an empty runs list (nothing known yet — a run may start any second). Live → `maxAge(5, SECONDS)`, all-terminal → keep `maxAge(30, SECONDS)`; `cachePrivate()` in both. Small private helper, no config knob.

**Tests** (`RunQueryControllerTest`): response containing a RUNNING entry → `Cache-Control: max-age=5, private`; all-SUCCESS response → `max-age=30, private`.

### WP-C — Stop persisting the SLA deadline as `estimatedEndTime` (2.4-H1)

**File:** `service/RunIngestionService.java` (`resolveEstimatedEnd`)

Remove the fallback branch that returns `slaResolution.deadline()` when no duration baseline exists (the block commented "No duration baseline, but we have a frozen (clock-derived) deadline — use it as estimated end"). New precedence: request value → duration baseline → profile average → **null**. The column is immutable, so today's conflation is permanent once written — null is honest; the `sla` field carries the deadline.

Downstream check (verified safe): `RunEntry.estimatedEndTime` is `@JsonInclude(NON_NULL)` → omitted; `CalculatorStateService` projection paths use `estimatedStartTime` + `expectedDurationMs`, not stored `estimatedEndTime`; synthetic grading falls back to `estEnd` only when no projected SLA exists.

**Tests:** rewrite `RunIngestionServiceTest.startRun_clockMode_estimatedEndDefaultsToDeadlineWhenNoDuration` → clock spec with no duration and empty profile now persists `estimatedEndTime == null` while `slaTime` still carries the deadline.

**PR note:** dashboards reading `estimatedEndTime` must fall back to `sla` for clock-spec calculators with no duration history (forward-only change; existing rows keep their stored values).

### WP-D — Documentation debt

**Files:** `CLAUDE.md`, `tech-spec.md` (§13, if present in repo — verify at implementation time), `tasks/todo.md`

1. **Tech-debt entries** for the two deliberately-unfixed SLA semantics (from the Phase 1 review): add to the CLAUDE.md Known Tech Debt table as TD-12/TD-13:
   - TD-12: duration-based/fallback SLA deadlines are start-anchored — a late-starting run gets a late deadline; upstream lateness is invisible to SLA grading. Mitigation: prefer `T+N@HH:mm` specs for business-cutoff calculators.
   - TD-13: MONTHLY bare-clock overnight roll grants ~24h grace to a run that starts *after* its cutoff (roll can't distinguish overnight windows from late starts). Needs business sign-off on a threshold before fixing.
2. **Refresh stale CLAUDE.md sections** to post-implementation reality: `obs:state` row in the Redis cache table (TTL-only → event-driven eviction + TTL backstop, per WP-A), SLA Detection section (add the DB fallback sweep + `db-sweep-interval-ms`), API surface notes (201-created/200-replay on `/start`, 409 on conflicting `/complete`, strict frequency, non-EOM MONTHLY rejection), `clockTimeDeadline` zone parameter.
3. Close out `tasks/todo.md` with the review section per the project convention.

## Files Touched (summary)

| File | WP |
|---|---|
| `cache/CalculatorStateCacheService.java` | A |
| `cache/CalculatorStateCacheInvalidationListener.java` (new) | A |
| `util/ObservabilityConstants.java` | A |
| `controller/RunQueryController.java` | B |
| `service/RunIngestionService.java` | C |
| `CLAUDE.md`, `tech-spec.md` (if present), `tasks/todo.md` | D |
| Tests: `CalculatorStateCacheServiceTest`, new listener test, `RunQueryControllerTest`, `RunIngestionServiceTest` | A–C |

No schema or config changes; no new properties.

## Verification

1. `SPRING_PROFILES_ACTIVE=local mvn clean test` — full suite green.
2. Targeted: `mvn test -Dtest=CalculatorStateCacheServiceTest,RunQueryControllerTest,RunIngestionServiceTest`.
3. Manual end-to-end (Swagger at `http://localhost:8080/swagger-ui.html`):
   - Query `/batch/runs` for a calculator (caches NOT_STARTED) → `POST /runs/start` → re-query **immediately**: shows RUNNING (previously stale up to 60s). `redis-cli KEYS 'obs:state:*'` confirms the key vanished after start and reappears on the next query.
   - `POST /runs/{id}/complete` → immediate re-query shows SUCCESS.
   - Inspect response headers: `max-age=5` while RUNNING entries present; `max-age=30` once everything is terminal.
   - Start a clock-spec run (`slaTime: "T+1@09:30"`, no `expectedDurationMs`, calculator with no profile history) → response/DB has `estimatedEndTime` null, `slaTime` populated (WP-C).
4. Ignore the tests that require docker

## Sequencing

WP-A first (the core fix), WP-B second (its header change only makes sense with eviction in place), WP-C independent, WP-D last (documents the final state). All four land in one PR.

## Still open after this plan (intentionally)

- Stampede single-flight lock (excluded — revisit if dashboard concurrency causes measurable DB load).
- `slaBreached` true-or-absent serialization (excluded — response-contract change needing consumer coordination).
- TD-12/TD-13 actual semantic fixes (documented only; need business sign-off).
