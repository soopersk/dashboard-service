# GitLab Epic + Issues — Run History API (On-Demand Run Support)

Copy-paste source for GitLab. The epic comes first, then eight issues with descriptions, dependencies, implementation details and acceptance criteria. Existing issue numbers are preserved; use the dependency table at the end for sequencing.

Design references (reconciled 2026-10-21):

- [API and database contract](2026-10-21-run-history-api-design.md)
- [Generation-based cache design and implementation plan](2026-10-21-run-history-cache-plan.md), including both reviews in §13. This supersedes the API plan's §6 and decision D9 to reuse `AnalyticsCacheService` for history.

The API implementation already exists; the generation-based cache is planned. These issues describe the target release scope, not verified completion status. Reconcile existing work against the acceptance criteria before closing an issue. This file is a local issue source; it does not create or update GitLab issues.

---

## EPIC

**Title:**
```
Run History API — generic last-N runs endpoint for on-demand calculator runs
```

**Description:**
```markdown
## Summary

Add `GET /api/v1/calculators/{calculatorName}/runs`: a generic, newest-first run-history
query for one calculator. The first consumer is the on-demand calculator run feature, which
looks runs up by the `odRunId` that Airflow injects into `additional_attributes` (alongside
`consRunVersion` and `calculatorRunVersion`). The endpoint also serves any future
"latest run / last N runs" need.

## Supported call shapes

- `?reportingDate=2026-10-20&frequency=DAILY&limit=1`: latest run for a date
- `?odRunId=od-123`: run(s) of one on-demand trigger (up to `limit`)
- `?limit=20`: last 20 runs, any frequency
- `?reportingDate=2026-09-30&frequency=MONTHLY`: latest 20 runs of a month-end

## Approach

- Thin controller → `RunQueryService` → dedicated `CalculatorRunHistoryCacheService.getOrLoad(..., loader)` → one PostgreSQL query on a miss
- Every history snapshot key includes the calculator's generation. Run lifecycle listeners replace that generation after commit, invalidating every filter combination in one Redis SET without scanning or tracking keys
- The request captures the generation before its database read and writes only under that captured generation. A late write after invalidation stays under the old generation
- `/executions` keeps its existing `AnalyticsCacheService` index-set cache
- New V12 indexes support queries without `reportingDate`; those queries can still visit every partition, so performance is verified on realistic data

## Key decisions

- Path key is `calculator_name` (consistent with `/batch/runs` and `/executions`), not the UUID
- Omitting `frequency` means both DAILY and MONTHLY
- Only `odRunId`, `consRunVersion`, `calculatorRunVersion` are returned, as top-level fields per run
  (`null` when absent). The rest of `additional_attributes` (which includes internal ingestion
  metadata) is never exposed.
- Every request returns at most `limit` runs (default 20, max 100); no pagination or total count
- No implicit date bound: all retained runs are searchable, and future-dated runs sort first
- `X-Tenant-Id` is optional and does not filter results (same as the other query endpoints)
- Cache scope is the exact physical calculator name, URL-encoded; no alias expansion or tenant scope
- `nocache=true` skips snapshot reads, queries PostgreSQL and best-effort refreshes the captured-generation snapshot
- `observability.run-history-cache`: enabled=true, transient-ttl=10s, terminal-ttl=5m, generation-ttl=15m. Disabling reads/writes does not disable event invalidation

## Freshness contract

- After successful invalidation, requests that subsequently resolve the generation cannot read snapshots from the previous generation
- Async invalidation lag and requests already in flight can still produce an earlier snapshot; immediate read-after-write consistency is not promised
- Missed invalidations fall back to snapshot expiry (10s empty/RUNNING, 5m all-terminal). Redis errors are metered, but a process crash or rejected async task can lose invalidation without incrementing that counter
- Existing private HTTP caching adds up to 5s for live/empty responses or 30s for settled responses. Redis invalidation does not invalidate a client's HTTP cache

## Explicitly out of scope (do not build speculatively)

- Pagination/cursors beyond `limit` (max 100)
- Exposing any other `additional_attributes` key
- Filtering by `consRunVersion` / `calculatorRunVersion`
- Promoting `odRunId` to a first-class column
- Alias resolution and split/rerun collapsing
- Miss locks, request coalescing, Lua optimizations and extra snapshot DTOs
- Longer historical TTLs without a separate review of mutation coverage, missed invalidations and acceptable staleness
- Migrating or fixing the `/executions` cache: separate issue

## Definition of done (epic)

- All four call shapes return correct, newest-first results in dev
- Valid repeat calls hit the generation-based cache without querying PostgreSQL; all three lifecycle events replace the generation after commit, invalidating every history filter variant
- Automated tests prove late-write isolation, generation loss/re-seeding, invalid snapshot fallback, Redis failure handling and transaction-bound listener wiring
- Cache disabled: requests use PostgreSQL without Redis reads/writes, while event listeners still replace generations for other enabled pods
- Malformed parameters return 400, never 500
- Performance targets in Issue 7 are met (or the gap is analysed and a design change agreed) on a realistic dataset
- Rollout/rollback choice, kill switch and the eventual-consistency limits are documented and verified
- Consumer docs published; the on-demand feature team has signed off on the contract
```

**Labels:** `~epic` `~backend` `~observability` `~on-demand`

---

## ISSUE 1 — Confirm on-demand attribute contract with Airflow

**Title:**
```
On-demand: confirm additional_attributes contract (odRunId, consRunVersion, calculatorRunVersion)
```

**Description:**
```markdown
Agree on the exact JSON keys and value types Airflow writes into `additional_attributes`
on `POST /runs/start` for on-demand triggers. The lookup is an exact, case-sensitive match
on `additional_attributes->>'odRunId'`, so a spelling drift (`od_run_id`, `ODRunId`)
silently returns empty results.

No service code change. Ingestion already persists `additionalAttributes` verbatim.

### Dependencies

None.
```

**Acceptance Criteria:**
```markdown
- [ ] Keys confirmed in writing: `odRunId`, `consRunVersion`, `calculatorRunVersion` (exact casing)
- [ ] Value types confirmed (string vs number) and documented. The API exposes them as strings by default; switch to `Long` before the first consumer release if they are numeric
- [ ] `odRunId` values carry no leading/trailing whitespace (the API trims the query value; the DB match is exact)
- [ ] Confirmed whether one `odRunId` can map to more runs than the `limit` maximum (100); the endpoint returns at most `limit` runs
- [ ] Confirmed whether any run can have a future reporting date (such runs sort first in results)
- [ ] A sample on-demand `/runs/start` payload added to `docs/consumer-api.md` or the Airflow integration doc
```

**Labels:** `~backend` `~on-demand` `~integration`
**Suggested weight:** 1

---

## ISSUE 2 — DB indexes for run-history queries (V12)

**Title:**
```
DB: add V12 indexes for run history (calculator_runs_history_idx, partial od_run_id idx)
```

**Description:**
```markdown
Queries without `reportingDate` cannot prune the ~455 daily partitions of `calculator_runs`.
Add two parent-table indexes:

1. `calculator_runs_history_idx (calculator_name, reporting_date DESC, created_at DESC)`:
   serves `?limit=N` with no frequency. The existing executions index places `frequency`
   before the ordering columns, so it cannot directly supply that ordering when frequency is unconstrained.
2. `calculator_runs_od_run_id_idx (calculator_name, (additional_attributes->>'odRunId'))
   WHERE (additional_attributes->>'odRunId') IS NOT NULL`: partial, so only rows with a
   non-null `odRunId` create index entries.

The indexes avoid scanning a calculator's rows, but do not prune partitions: sparse results
(few runs, unknown `odRunId`) can still visit every partition. Issue 7 measures this.

⚠️ `CREATE INDEX` on a partitioned parent cannot be `CONCURRENTLY`; it blocks writes while it
builds. Plan the prod rollout (quiet window, or a runbook with `ON ONLY` + per-partition
`CONCURRENTLY` + `ATTACH PARTITION`).

### Dependencies

None (can merge ahead of the endpoint).
```

**Acceptance Criteria:**
```markdown
- [ ] `V12__run_history_indexes.sql` applies cleanly on an empty DB and on a dev snapshot
- [ ] Both indexes apply to existing partitions and to a newly created partition; `PartitionManagementJob` only monitors partitions, so no partition-creation change is included
- [ ] Index build time measured on a prod-sized snapshot; rollout approach recorded on the issue
- [ ] Ingestion latency (`/runs/start` p95) before vs. after measured on the representative dataset and recorded (no overhead percentage assumed in advance)
```

**Labels:** `~backend` `~database` `~performance`
**Suggested weight:** 2

---

## ISSUE 3 — Repository query + response DTO

**Title:**
```
Run history: CalculatorRunRepository.findRunHistory + RunHistoryResponse DTO
```

**Description:**
```markdown
Add a single parameterised query: `calculator_name` is required; `frequency`, `reporting_date`
and `odRunId` are optional, AND-combined, appended to the WHERE clause.
`ORDER BY reporting_date DESC, created_at DESC LIMIT :limit`. No implicit date bound.
Use `SELECT_WITH_JSONB` / `CalculatorRunRowMapper(true)` so `additional_attributes` is loaded.

Add `RunHistoryResponse(calculatorName, runs)` with a nested `RunEntry` record
(`from(CalculatorRun)`) carrying the run fields plus three top-level on-demand fields:
`odRunId`, `consRunVersion`, `calculatorRunVersion`. They are picked from
`additional_attributes`, converted with `Objects.toString(v, null)`, and are `null` when
absent. No other attribute key is exposed. Add an `anyLive()` helper (any RUNNING, or
empty) that is not serialized.

### Dependencies

Issue 2 (for performance, not correctness).
```

**Acceptance Criteria:**
```markdown
- [ ] All values bound as named parameters (no string-concatenated input)
- [ ] The odRunId predicate text exactly matches the V12 index expression
- [ ] Testcontainers tests: newest-first order + limit; frequency and reportingDate filters combined; odRunId JSONB match; other calculators excluded; JSONB mapped; a future-dated run is returned and sorts first
- [ ] Query timer `obs.db.query.duration{query="find_run_history"}` emitted
- [ ] `RunEntry` has no `Map` field; unit tests: the three keys extracted, numeric → string, missing → `null`, other keys (e.g. a stray `region`) not exposed
```

**Labels:** `~backend` `~database`
**Suggested weight:** 2

---

## ISSUE 4 — Generation-based history cache + lifecycle invalidation

**Title:**
```
Run history: generation-based Redis cache with resilient reads and after-commit invalidation
```

**Description:**
```markdown
The current history cache can be repopulated with an old database result after index-set
eviction has finished. For example, a pre-START read of an all-terminal result can be
written after eviction and hide the new run for five minutes. Replace history caching
with generation-in-key invalidation, without changing the API or the executions cache.

Reference: `2026-10-21-run-history-cache-plan.md` §§3–8 and §10 Tasks 1–3b.

### Components and keys

- Add `RunHistoryCacheProperties` and `CalculatorRunHistoryCacheService` with
  `getOrLoad(name, frequency, reportingDate, odRunId, limit, nocache, loader)`
- Reuse `RunHistoryResponse` as the typed JSON snapshot; no separate snapshot DTO
- `scope = URLEncoder.encode(exactCalculatorName, UTF_8)`; use the same physical name
  from the query and lifecycle event. Do not resolve aliases, calculator IDs or tenants
- Generation key: `obs:calculator-runs:v2:g:{scope}`
- Snapshot key: `obs:calculator-runs:v2:r:{scope}:{gen}:{reportingDate|all}:{FREQ|all}:{limit}:{od:none|od:value:{odRunId}}`
- Generation is a fresh random 64-bit token (16 lowercase hex characters), not an INCR
  counter. Missing generations are seeded with SET NX and an expiry; a losing seeder
  reads the winning stored token. Never substitute a fixed token for a missing generation
- Calculator names are encoded; the free-form odRunId is tagged and last. Literal
  `none`, `all`, colons, spaces and percent characters cannot impersonate other key segments

### Read, miss and refresh

1. If disabled, invoke the loader directly with no Redis I/O on the request path.
2. Resolve/capture the generation before the database query. If generation resolution
   fails or still returns no token after the seed race, load from PostgreSQL without a cache write.
3. Unless nocache=true, read the snapshot for the captured generation. Count a hit only
   after deserialization and validation: matching calculator name, non-null runs and entries,
   each entry carrying a runId and a known RunStatus. Empty runs are valid and cacheable.
4. A miss, malformed JSON, incompatible/invalid snapshot or snapshot-read failure loads
   PostgreSQL. Do not delete corrupt values; overwrite best-effort after a successful load.
5. Write only under the captured generation, even if invalidation ran during the query.
   Never move that result into a newly resolved generation. nocache=true skips snapshot
   reads and follows this same load/write rule; it does not wait for a lock.
6. Cache read/write/serialization failures are logged without payload content and metered.
   Return an already obtained database response after a failed cache write; do not query
   again. Database/loader errors propagate through the existing API error handling.

### Configuration and invalidation

- Under `observability.run-history-cache`: enabled=true, transient-ttl=10s,
  terminal-ttl=5m, generation-ttl=15m. Validate duration configuration (minimum 1s).
- Empty or any RUNNING result uses transient-ttl; a non-empty all-terminal result uses
  terminal-ttl. Generation expiry is set on seeding/invalidation, not refreshed on reads.
- Register RunStartedEvent, RunCompletedEvent and SlaBreachedEvent listeners using
  `@TransactionalEventListener(phase = AFTER_COMMIT)` plus `@Async`. The completion
  failure path can publish SlaBreachedEvent instead of RunCompletedEvent.
- Each listener SETs a fresh generation token with generation-ttl for the event's exact
  calculator name. Invalidation runs even when enabled=false. No key set, SCAN or bulk DEL.
- Requests resolving the generation after successful invalidation cannot retrieve an old
  generation's snapshot. Async lag and in-flight responses remain accepted; TTL is the
  recovery bound for missed invalidations, not an immediate freshness guarantee.
- Keep miss locks/coalescing, Lua and longer historical TTLs deferred.

### Metrics

- `obs.cache.history.lookup{outcome=hit|miss|corrupt|error|bypass}`
- `obs.cache.history.write{outcome=success|error}`
- `obs.cache.history.invalidation{outcome=success|error}`
- No calculator, odRunId, generation or full-key metric tags. Invalidation errors do not
  count tasks that never executed; no claim of measured commit-to-invalidation latency.

### Dependencies

Issue 3 (DTO). Can be reviewed before switching the endpoint in Issue 5.
```

**Acceptance Criteria:**
```markdown
- [ ] Properties bind under the documented prefix with the four defaults; invalid duration values fail validation
- [ ] Unit tests cover valid hit (no loader call), miss, empty/RUNNING transient TTL, every terminal status, and nocache refresh without a snapshot GET
- [ ] Key tests distinguish omitted odRunId from literal `all`, `none` and `od:none`; IDs containing colons and encoded names containing `:`, `%`, spaces and `+` remain distinct. Date, frequency, limit and generation participate in the key
- [ ] Missing generation is seeded with NX plus expiry; a losing seeder uses the stored winner. Resolution failure or a token that disappears again causes DB-only loading without a snapshot write
- [ ] JSON round-trip preserves dates/times, all run fields and the three on-demand fields, including explicit nulls
- [ ] Malformed JSON, JSON null, missing/wrong calculator name, missing runs, null entries, missing runId and missing/unknown status each produce corrupt, never hit; the loader result replaces the value without an explicit delete
- [ ] Redis generation/read/write/invalidation failures and serialization failures follow the documented fallback, log no payload, and emit the appropriate outcome metric. A failed loader propagates and writes no snapshot
- [ ] Disabled requests invoke the loader without Redis I/O; all three event listeners still invalidate when disabled
- [ ] Real Redis tests prove repeat hits, invalidation/reload, TTLs, corrupt-value replacement and generation loss/re-seeding without resurrecting old snapshots
- [ ] Race test: invalidate during a loader call, allow its late write under the old token, then prove a subsequent request resolves the new token and reloads instead of serving that write
- [ ] Spring listener tests cover all three event types with a controlled executor: no submitted task/change before commit; exactly one task after commit, with token replacement when drained; no task/change after rollback or publishing outside a transaction
- [ ] Listener tests deterministically detect missing registration, synchronous execution and incorrect transaction phases, including AFTER_COMPLETION on rollback. They do not depend on sleeps or a race with a background worker
- [ ] Unit tests pass; real Redis and listener tests pass with Docker. Docker-dependent skips are recorded as pending verification, not as proof of integration success
```

**Labels:** `~backend` `~redis` `~performance`
**Suggested weight:** 5

---

## ISSUE 5 — RunQueryService + thin controller endpoint

**Title:**
```
Run history: GET /api/v1/calculators/{calculatorName}/runs (RunQueryService.getRunHistory)
```

**Description:**
```markdown
Wire `RunQueryService.getRunHistory(name, frequency, reportingDate, odRunId, limit, nocache)`
to `CalculatorRunHistoryCacheService.getOrLoad(...)`. Pass a loader that performs the
single repository query and maps its rows to RunHistoryResponse. The cache service owns
cache orchestration and TTL choice; RunQueryService owns the query and DTO mapping.

Replace the existing AnalyticsCacheService dependency and remove RunQueryService's
hard-coded LIVE_TTL/SETTLED_TTL constants. Remove history-only methods, key builder,
prefix and imports from AnalyticsCacheService, and migrate their relevant tests to the
new cache service. Keep executions read/write helpers, index-set tracking and existing
lifecycle eviction behavior. New history writes must not join that index set.

Old history values expire within five minutes of their last write by an old pod; old
index members are removed by normal executions eviction or the index TTL. No scan,
Redis migration or cleanup job is required. See cache plan §7.4–7.6 and §12.

Controller (in `RunQueryController`) only binds and validates:
- `frequency` optional → `Frequency.fromStrict` (unknown/blank → 400)
- `odRunId` trimmed; blank treated as absent
- `limit` default 20, `@Min(1) @Max(100)`
- `nocache=true` bypasses snapshot reads and best-effort refreshes the captured-generation snapshot; it still resolves the generation and does not bypass the client's HTTP cache
- `Cache-Control: private, max-age=5` if live, else 30
- Request log `event=run_history.request` and latency timer `obs.api.analytics.duration{endpoint="/calculators/{name}/runs"}`

### Dependencies

Issues 3 and 4.
```

**Acceptance Criteria:**
```markdown
- [ ] Controller contains no business logic; it delegates to `RunQueryService`
- [ ] Service tests prove every normalized filter, limit and nocache value reaches getOrLoad; the supplied loader calls the repository once and maps the explicit on-demand fields correctly
- [ ] Cache behavior tests live in Issue 4; service mapping tests invoke the supplied loader so they continue testing real mapping rather than returning a canned mock response
- [ ] No history methods, history key builder or history index tracking remain in AnalyticsCacheService. Existing executions cache tests pass without a behavior change
- [ ] Endpoint integration demonstrates a valid repeat hit skips PostgreSQL, nocache refreshes, and enabled=false returns the same API shape from PostgreSQL
- [ ] Controller tests: all four call shapes bind correctly; defaults; 400 for bad frequency / limit 0 / limit 101; Cache-Control values; `odRunId`/`consRunVersion`/`calculatorRunVersion` present as top-level run fields; `additionalAttributes` and `anyLive` absent from the JSON
- [ ] `/batch/runs` routing unaffected (existing controller tests green)
- [ ] Swagger describes physical calculator names, the three explicit on-demand fields and nocache refresh semantics; it does not claim the entire additionalAttributes map is exposed
```

**Labels:** `~backend` `~api`
**Suggested weight:** 3

---

## ISSUE 6 — Malformed parameters return 400, not 500 (global)

**Title:**
```
API: map MethodArgumentTypeMismatchException to 400 in GlobalExceptionHandler
```

**Description:**
```markdown
A query/path value that can't be converted (`reportingDate=not-a-date`, `limit=abc`,
`nocache=abc`) raises Spring's `MethodArgumentTypeMismatchException`. There is no handler for
it, so the catch-all returns **500** on every endpoint (`/batch/runs?reporting_date=bad` and
`/executions?days=abc` are affected too). Add one global 400 handler, following the existing
400 handlers (`API_ERROR` counter, `event=api.error` WARN, `buildErrorResponse`).

The message names the parameter and expected type, and never echoes the rejected value.

Closes the pre-existing hardening item P2.2.

### Dependencies

None (independent; can merge any time before Issue 5 ships).
```

**Acceptance Criteria:**
```markdown
- [ ] `GlobalExceptionHandlerTest`: type mismatch → 400, message names param and type, rejected value not echoed
- [ ] Run-history controller tests: `reportingDate=not-a-date`, `limit=abc`, `nocache=abc` → 400, no service call
- [ ] Regression: `/batch/runs?reporting_date=not-a-date` → 400 (was 500)
- [ ] `docs/consumer-api.md` "Common errors" updated
```

**Labels:** `~backend` `~api` `~bug`
**Suggested weight:** 1

---

## ISSUE 7 — Performance verification on a realistic dataset

**Title:**
```
Run history: outcome-based performance verification (planning/execution time, buffers)
```

**Description:**
```markdown
Prove the index and cache design on realistic data before the on-demand feature goes live.
Assert outcomes, not plan shapes: the planner may correctly seq-scan empty or tiny
partitions. An ordered unpruned scan can visit ~60 empty future partitions before
reaching current data; sparse/empty queries can visit every partition.

Validate the new cache's correctness, operational behavior and actual cost alongside
the database query. A cache hit has two sequential Redis GETs (generation + snapshot).
There is no miss coalescing: concurrent misses, refreshes, evictions and Redis outages
can each cause additional database queries. No single-query-per-key guarantee applies.

### Dataset

- ~395 days of historical partitions (created explicitly; the zero-arg
  `create_calculator_run_partitions()` only creates yesterday → +60 days) plus 60 future
- Representative per-day, per-calculator row volumes, with source and assumptions recorded
- Mostly empty future partitions plus at least one valid future-dated run
- `ANALYZE calculator_runs` afterwards

### Scenarios

S1 busy calculator `?limit=20` · S2 sparse calculator (< 20 runs) · S3 known `odRunId` ·
S4 unknown `odRunId` · S5 calculator with no runs · S6 `reportingDate` + `frequency` (DAILY and MONTHLY) ·
S7 `?limit=100` on a busy calculator

### Dependencies

Issues 2–6 deployed to dev; Issue 4 unit, real Redis and listener tests passing.
```

**Acceptance Criteria:**
```markdown
- [ ] For each scenario: repeated `EXPLAIN (ANALYZE, BUFFERS, SUMMARY)` samples with Planning Time, Execution Time and shared hit/read buffers recorded on the issue, plus API p95 over ≥ 200 calls (cache bypassed, then cached). Dataset, environment, concurrency and DB-buffer warm/cold state stated
- [ ] Provisional targets (to validate, not an SLO): DB planning + execution p95 < 50ms across S1–S7; cached API p95 < 5ms
- [ ] Buffers and rows examined compared with and without V12 on the same dataset; no substantial reads of non-matching data
- [ ] `reportingDate` queries prune to one partition; ordered early termination observed when enough matching rows exist
- [ ] If targets are missed: the measured cost (planning, sorting, I/O, application) is identified and a minimal design change agreed. No silent date cutoff
- [ ] Cache collision checks: literal odRunId `all`/`none` and unfiltered requests, warmed in both orders, return their own database-expected results; valid repeats hit without a database read
- [ ] Run the cache plan §10 Task 8 end-to-end check using a fresh isolated calculator, a completed run dated D1 and a new run dated D2 > D1. Prime both limit=1 (terminal TTL) and an empty odRunId variant; wait with a bounded timeout for generation replacement before asserting both refresh to the new RUNNING run
- [ ] Exercise START, successful completion, failed completion/breach and live SLA breach. After each successful invalidation, subsequent generation-resolving requests return the updated database state across relevant filter variants
- [ ] Record evidence that manual checks distinguish event invalidation from normal TTL expiry; the controlled race and transaction tests remain the authoritative correctness checks
- [ ] Kill switch verified on the endpoint: no new snapshot writes or request-path Redis reads, correct PostgreSQL responses, and lifecycle events still replace the generation
- [ ] Redis outage/read/write failure checks retain correct database responses; fallback latency and added database load are recorded, without assuming exception handling removes Redis timeout cost
- [ ] History lookup/write/invalidation outcome metrics are exported without high-cardinality tags; confirm available taskExecutor queue metrics and document their limits. Zero invalidation errors is not treated as proof of complete event delivery
- [ ] Staging records representative concurrent polling behavior, Redis snapshot/orphan memory and API latency; no miss lock or longer historical TTL is added without the separate evidence/review defined in the cache plan
- [ ] Rollout choice recorded: accept TTL-bounded stale snapshots when old ingestion pods remain, or deploy all new pods with caching disabled before enabling it. Rollback ignores the new namespace; old/new entries expire without a Redis migration
```

**Labels:** `~backend` `~performance` `~testing`
**Suggested weight:** 3

---

## ISSUE 8 — Documentation

**Title:**
```
Docs: Run history API in consumer-api.md and CLAUDE.md
```

**Description:**
```markdown
Publish the consumer contract and update the internal architecture and operational
references to the generation-based history cache. Clearly distinguish it from the
executions index-set cache. See cache plan §10 Task 7 and §12.

### Dependencies

Issues 1, 4–6 for the contract and implementation; Issue 7 results before final release sign-off.
```

**Acceptance Criteria:**
```markdown
- [ ] `docs/consumer-api.md`: params, response example, at-most-limit results, reporting-date-first ordering including future dates, errors and the "Choosing an endpoint" table. Confirmed Airflow sample/types from Issue 1 included
- [ ] `docs/consumer-api.md`: tenancy statement corrected — `X-Tenant-Id` is optional and does not filter results
- [ ] Consumer caching description explains nocache refresh, async/in-flight staleness and private HTTP max-age 5s/30s; it does not promise immediate read-after-write consistency or expose Redis mechanics as API parameters
- [ ] `CLAUDE.md`: API row references observability.run-history-cache; Redis table includes generation/snapshot keys, encoded exact-name scope and 10s/5m/15m TTLs. The analytics index set is described as tracking executions only
- [ ] Event-flow table lists the new CalculatorRunHistoryCacheService for all three lifecycle events; AnalyticsCacheService remains responsible for executions eviction
- [ ] API design plan §6 is marked superseded by the cache plan and its review log updated; references do not instruct implementers to keep history in AnalyticsCacheService
- [ ] Operational notes cover the enabled flag (request reads/writes only), invalidation while disabled, metrics and unobserved invalidation loss, mixed-version rollout, rollback and expiry of stranded snapshots
- [ ] Verification commands/results from Issues 4 and 7 are recorded with environment and any Docker-dependent skips. Release verification requires the real Redis, listener and JDBC tests to execute successfully
- [ ] On-demand feature team sign-off on the contract recorded on the epic
```

**Labels:** `~documentation`
**Suggested weight:** 1

---

## Suggested sequencing

| Issue | Depends on | Cache-plan coverage / release role |
|---|---|---|
| 1 — Airflow contract | None | Confirm consumer assumptions; required for sign-off |
| 2 — Indexes | None | API/database plan; rollout and write-cost evidence |
| 3 — Query + DTO | 2 for performance | Typed loader input/output; may be implemented before index rollout |
| 4 — Generation cache | 3 | Tasks 1–3b: configuration, cache, Redis tests and deterministic listener tests |
| 5 — Endpoint integration | 3, 4 | Tasks 4–6: service wiring, old history-cache removal and controller description |
| 6 — Global 400 handler | None | Must ship before the endpoint is released |
| 7 — Verification | 2–6 | Task 8 plus database performance, operational and rollout evidence |
| 8 — Documentation | 1, 4–6; final results from 7 | Task 7 plus consumer docs and release sign-off |

Issues 1, 2 and 6 can proceed independently. Documentation can be drafted alongside
implementation; finish it with the recorded verification results. Keep Issue 4's cache
correctness tests separate from Issue 7's end-to-end and performance checks.

Total suggested weight: **18**
