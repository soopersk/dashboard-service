# GitLab Epic + Issues — Run History API (On-Demand Run Support)

Copy-paste source for GitLab. The epic comes first, then the issues in implementation order. Each issue is a shippable, reviewable slice; build them top to bottom.

Design reference: `docs/plans/2026-10-21-run-history-api-design.md` (reconciled with design-review log §13, 2026-10-21)

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
- Thin controller → `RunQueryService.getRunHistory(...)` → Redis (cache-aside) → one SQL query
- Redis entries are evicted (best-effort, async) by the existing run lifecycle events; TTLs bound staleness
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

## Explicitly out of scope (do not build speculatively)
- Pagination/cursors beyond `limit` (max 100)
- Exposing any other `additional_attributes` key
- Filtering by `consRunVersion` / `calculatorRunVersion`
- Promoting `odRunId` to a first-class column
- Alias resolution and split/rerun collapsing
- Fixing the analogous key issues in the `/executions` cache (untagged `run_number` sentinel, unencoded name): separate issue

## Definition of done (epic)
- All four call shapes return correct, newest-first results in dev
- Repeat calls are served from Redis; after a run start/complete/breach, a fresh request reflects the change once async eviction has run (TTL bounds any race)
- Malformed parameters return 400, never 500
- Performance targets in Issue 7 are met (or the gap is analysed and a design change agreed) on a realistic dataset
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
   serves `?limit=N` with no frequency. The existing executions index leads with `frequency`,
   and PG17 has no skip scan.
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
- [ ] Both indexes apply to existing partitions and to partitions created later by `PartitionManagementJob`
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

## ISSUE 4 — Redis caching + event-driven invalidation

**Title:**
```
Run history: Redis cache-aside in AnalyticsCacheService with run-event eviction
```

**Description:**
```markdown
Reuse `AnalyticsCacheService` (index-set eviction is already wired to run events) instead of
adding a new cache class.

- Key: `obs:analytics:history:{urlEncodedName}:{FREQ|all}:{reportingDate|all}:{limit}:{od:none|od:value:{odRunId}}`
  - The calculator name is URL-encoded (JDK `URLEncoder`, UTF-8): free-form names can't impersonate later segments
  - The `odRunId` segment is tagged and last: `?odRunId=all` can't collide with "no odRunId", and a `:` in an id can't shift segments
- Tracked in the existing `obs:analytics:index:{name}` set (raw name, 1h TTL refreshed per write)
- Entry TTL: 30s when live (any RUNNING, or empty), 5m otherwise. Eviction is best-effort and async; TTL bounds staleness
- Extract shared `read`/`write` helpers; executions get/put delegate to them (no behaviour change)
- `onRunStarted` now evicts the whole calculator index (executions + history), because a new run
  must appear. Remove the orphaned prefix-eviction helpers.
- Redis failures are logged at WARN and fall through to the DB. They never fail the request.

### Dependencies
Issue 3 (DTO).
```

**Acceptance Criteria:**
```markdown
- [ ] Key format, TTL and index tracking verified by unit tests
- [ ] Collision tests: `odRunId=null` vs `"all"` vs `"none"` give distinct keys; an id containing `:` gives a distinct key; names containing `:`/`%` (incl. a name mimicking a key suffix) give distinct keys
- [ ] JSON round-trip of `RunHistoryResponse`, including the three on-demand fields and their `null`s
- [ ] RunStarted / RunCompleted / SlaBreached each evict history keys (and alias index if present)
- [ ] Existing `/executions` caching tests still pass; the behaviour change on RunStarted is noted in the MR
- [ ] Hit/miss counters tagged `prefix=history`
```

**Labels:** `~backend` `~redis` `~performance`
**Suggested weight:** 3

---

## ISSUE 5 — RunQueryService + thin controller endpoint

**Title:**
```
Run history: GET /api/v1/calculators/{calculatorName}/runs (RunQueryService.getRunHistory)
```

**Description:**
```markdown
`RunQueryService.getRunHistory(name, frequency, reportingDate, odRunId, limit, nocache)`:
cache → DB on miss → write back with a state-aware TTL.

Controller (in `RunQueryController`) only binds and validates:
- `frequency` optional → `Frequency.fromStrict` (unknown/blank → 400)
- `odRunId` trimmed; blank treated as absent
- `limit` default 20, `@Min(1) @Max(100)`
- `nocache` bypasses the Redis read (not the client's HTTP cache)
- `Cache-Control: private, max-age=5` if live, else 30
- Request log `event=run_history.request` and latency timer `obs.api.analytics.duration{endpoint="/calculators/{name}/runs"}`

### Dependencies
Issues 3 and 4.
```

**Acceptance Criteria:**
```markdown
- [ ] Controller contains no business logic; it delegates to `RunQueryService`
- [ ] Service tests: cache hit skips DB; miss → DB → cache with correct TTL; nocache skips the read but refreshes the cache
- [ ] Controller tests: all four call shapes bind correctly; defaults; 400 for bad frequency / limit 0 / limit 101; Cache-Control values; `odRunId`/`consRunVersion`/`calculatorRunVersion` present as top-level run fields; `additionalAttributes` and `anyLive` absent from the JSON
- [ ] `/batch/runs` routing unaffected (existing controller tests green)
- [ ] Swagger shows the endpoint with parameter descriptions
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
partitions, and unpruned queries visit the ~60 empty future partitions first.

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
Issues 2–5 deployed to dev.
```

**Acceptance Criteria:**
```markdown
- [ ] For each scenario: repeated `EXPLAIN (ANALYZE, BUFFERS, SUMMARY)` samples with Planning Time, Execution Time and shared hit/read buffers recorded on the issue, plus API p95 over ≥ 200 calls (cache bypassed, then cached). Dataset, environment, concurrency and DB-buffer warm/cold state stated
- [ ] Provisional targets (to validate, not an SLO): DB planning + execution p95 < 50ms across S1–S7; cached API p95 < 5ms
- [ ] Buffers and rows examined compared with and without V12 on the same dataset; no substantial reads of non-matching data
- [ ] `reportingDate` queries prune to one partition; ordered early termination observed when enough matching rows exist
- [ ] If targets are missed: the measured cost (planning, sorting, I/O, application) is identified and a minimal design change agreed. No silent date cutoff
- [ ] Cache: `?odRunId=all` and unfiltered requests, warmed in both orders, each return their own DB result; repeat calls hit; after a lifecycle event and its async eviction, a fresh request misses
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
Publish the consumer contract and update the internal architecture references.

### Dependencies
Issues 5 and 6.
```

**Acceptance Criteria:**
```markdown
- [ ] `docs/consumer-api.md`: new section (params, response example, at-most-`limit` results, future-dated ordering, caching, errors) plus the "Choosing an endpoint" table
- [ ] `docs/consumer-api.md`: tenancy statement corrected — `X-Tenant-Id` is optional and does not filter results
- [ ] `CLAUDE.md`: API table row, Redis key table row (encoded name, tagged `od:` segment), index-set description, AnalyticsCacheService listener row
- [ ] On-demand feature team sign-off on the contract recorded on the epic
```

**Labels:** `~documentation`
**Suggested weight:** 1

---

## Suggested sequencing

```
Issue 1 (contract) ─┐
Issue 2 (V12) ──────┼─► Issue 3 (repo+DTO) ─► Issue 4 (cache) ─► Issue 5 (endpoint) ─► Issue 7 (perf) ─► Issue 8 (docs)
Issue 6 (400s) ─────┘                                              ▲ (must ship before 5 goes live)
```

Total suggested weight: **16**
