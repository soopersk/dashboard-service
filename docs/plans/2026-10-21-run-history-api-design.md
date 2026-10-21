# Run History API (`GET /calculators/{name}/runs`): Design & Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a generic "last N runs" query endpoint for one calculator. Its first consumer is the **on-demand calculator run** feature, which looks runs up by an Airflow-injected `odRunId`. Any future "latest run / recent runs" need can use it too.

**Architecture:** Thin controller → `RunQueryService.getRunHistory(...)` → Redis (Redis-first) → one parameterised SQL query on `calculator_runs`. Existing run-lifecycle events invalidate cache entries, with TTLs bounding missed invalidations. Two new parent-table indexes (V12) support queries without `reportingDate`; these queries can still visit every partition.

**Tech Stack:** Java 17, Spring Boot 3.5.9, `NamedParameterJdbcTemplate`, Redis (`StringRedisTemplate` + Jackson), PostgreSQL 17 (range-partitioned `calculator_runs`), Flyway, JUnit 5 + Mockito + Testcontainers.

**Status:** Tasks 1–7 and 9 implemented on `main` (uncommitted as of 2026-10-21), including the D5 contract revision and the §13 design-review fixes. Unit and controller tests are green. Pending, both blocked on Docker:
- the Testcontainers JDBC tests (Task 3, written but skipped without Docker);
- Task 8 performance verification on a realistic dataset.

---

## 1. Requirement

Airflow's on-demand trigger injects three attributes into `calculator_runs.additional_attributes` (JSONB):

| Attribute | Meaning |
|---|---|
| `odRunId` | On-demand run identifier (the lookup key) |
| `consRunVersion` | Consolidation run version |
| `calculatorRunVersion` | Calculator run version |

The endpoint must support these call shapes:

```
/api/v1/calculators/{calculator}/runs?reportingDate=xx&frequency=DAILY&limit=1   # latest run for a date
/api/v1/calculators/{calculator}/runs?odRunId=xxx                                # run(s) of an on-demand trigger
/api/v1/calculators/{calculator}/runs?limit=20                                   # last 20 runs
/api/v1/calculators/{calculator}/runs?reportingDate=xx&frequency=MONTHLY         # latest 20 runs of a month-end
```

Constraints:
- The controller stays thin and delegates to `RunQueryService.getRunHistory(...)`.
- Use Redis to cut DB load.
- Evaluate and add the DB indexes these queries need.
- Keep the design simple, with no speculative abstraction.

## 2. Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | The path variable is **`calculator_name`**, not the upstream `calculator_id` UUID. | Existing read endpoints (`/batch/runs`, `/executions`) query by name; UUIDs are not resolved to names. The relevant queries have name-based indexes, and cache eviction is keyed by name. *(Confirmed with the product owner.)* |
| D2 | Omitting `frequency` means **no frequency filter** (DAILY + MONTHLY). | This keeps the endpoint generic: `?odRunId=` finds a MONTHLY on-demand run without the caller knowing its frequency. It costs one extra index (§5.2). *(Confirmed.)* |
| D3 | All filters are optional and **AND-combined**. There is no mutually exclusive "mode" parameter. | One SQL shape with appended predicates, no branching API. |
| D4 | Query params use **camelCase** (`reportingDate`, `odRunId`) as in the requirement. | This differs from `/batch/runs` (`reporting_date`, `run_number`). The requirement spelled the names out explicitly, so they follow it. |
| D5 | Only **`odRunId`, `consRunVersion`, `calculatorRunVersion`** are returned, as top-level fields on each run. They are `null` when absent. The rest of `additional_attributes` is **never** exposed. | `additional_attributes` is open-ended, and it also holds internal data: the ingestion guard moves stray `region`/`run_type`/`run_number` values there. Passing the map through would leak that and make every key an implicit API contract. Explicit fields give a documented, stable schema and smaller payloads and Redis entries. Exposing a new attribute is a deliberate ~2-line contract change. *(Revised 2026-10-21; replaces "return the map verbatim".)* |
| D6 | There is **no lookback window or implicit date bound** when `reportingDate` is absent. | The query searches all retained rows, including future reporting dates. An ordered partition scan can stop once LIMIT is satisfied, but sparse or empty results can visit all partitions (§5.2). Task 8 measures the cost. Any later date restriction requires an explicit contract decision because it can change results. |
| D7 | **No alias resolution.** The path name is used as given. | This is a per-physical-calculator view. Alias fan-out belongs to `/batch/runs` and `/executions`. |
| D8 | **No split/rerun collapsing.** Each physical row is one entry. | On-demand tracking needs every physical attempt. Collapsing is a `/batch/runs` concern. |
| D9 | The Redis cache **reuses `AnalyticsCacheService`** rather than adding a new cache class. | Its per-calculator index-set eviction is already wired to `RunStarted`/`RunCompleted`/`SlaBreached`. A new class would duplicate about 60 lines of eviction code (DRY). |

## 3. API contract

### Request

`GET /api/v1/calculators/{calculatorName}/runs`. HTTP Basic auth is required. `X-Tenant-Id` is accepted but optional and does not filter results, matching existing query endpoints.

| Param | Type | Required | Default | Validation / semantics |
|---|---|---|---|---|
| `calculatorName` (path) | string | yes | — | Exact `calculator_name` match |
| `reportingDate` | ISO date | no | — | Exact `reporting_date` match. Prunes to one partition. |
| `frequency` | string | no | both | `Frequency.fromStrict`: `DAILY`/`D`/`MONTHLY`/`M`. Anything else (including blank) → **400** |
| `odRunId` | string | no | — | Trimmed. Blank is treated as absent. Exact match on `additional_attributes->>'odRunId'` |
| `limit` | int | no | `20` | `@Min(1) @Max(100)`, else **400** |
| `nocache` | boolean | no | `false` | Skip the Redis read. The DB result is still written back. |

Order: `reporting_date DESC, created_at DESC`, so `limit=1` returns the most recently created run for the newest matching date. Future-dated runs, if any, sort first (D6: no implicit date bound).

Every request returns **at most `limit` runs**, including `odRunId` and date-filtered requests. This is recent history, not a guarantee that every physical run for a trigger or month-end is returned. There is no pagination or total count in this version.

### Response `200 OK`

```json
{
  "calculatorName": "capitalcalc",
  "runs": [
    {
      "runId": "run-123",
      "calculatorId": "6f1c…",
      "frequency": "DAILY",
      "reportingDate": "2026-10-20",
      "runNumber": "1",
      "region": null,
      "runType": null,
      "status": "SUCCESS",
      "startTime": "2026-10-21T05:00:00Z",
      "endTime": "2026-10-21T05:42:00Z",
      "durationMs": 2520000,
      "expectedDurationMs": 2400000,
      "slaTime": "2026-10-21T07:00:00Z",
      "slaBand": "ON_TIME",
      "slaBreached": false,
      "odRunId": "od-123",
      "consRunVersion": "3",
      "calculatorRunVersion": "7"
    }
  ]
}
```

- `odRunId` / `consRunVersion` / `calculatorRunVersion` come from `additional_attributes`. Each is `null` when that key is absent (for example, on scheduled runs). They are always present in the JSON as explicit `null`, not omitted, so the schema is the same for every run, matching the other nullable fields such as `endTime`. Values are strings: a numeric value sent by Airflow is converted with `Objects.toString`. If Airflow confirms numeric types (epic Issue 1), they can switch to `Long`.
- No other `additional_attributes` key is ever returned.
- No match → `200` with `runs: []` (never 404, consistent with the other query endpoints).
- `Cache-Control: private, max-age=5` when the response is *live* (any `RUNNING` entry, or `runs` empty), otherwise `private, max-age=30`. This mirrors `/batch/runs`.

### Errors

| Status | Cause |
|---|---|
| 400 | Unknown/blank `frequency` (`IllegalArgumentException`) |
| 400 | `limit` outside 1–100 (`ConstraintViolationException`) |
| 400 | Malformed `reportingDate` (e.g. `not-a-date`), non-integer `limit` (`abc`), non-boolean `nocache` (`abc`). These raise `MethodArgumentTypeMismatchException`. **This requires the new global handler in §7.1.** Without it they fall through to the catch-all handler and return **500** (reproduced in design review). |
| 401 | Missing/invalid Basic auth |

Error bodies use the existing `buildErrorResponse` shape. The message names the parameter and the expected type. It never echoes a stack trace.

## 4. Component design

```
RunQueryController.getRunHistory         (bind + validate + Cache-Control + timer — no logic)
        │
        ▼
RunQueryService.getRunHistory            (cache-aside orchestration + TTL choice)
        │                    │
        ▼                    ▼
AnalyticsCacheService        CalculatorRunRepository.findRunHistory
  getRunHistory / putRunHistory      (single parameterised SELECT … LIMIT)
        ▲
        │ evict (AFTER_COMMIT, @Async)
RunStartedEvent / RunCompletedEvent / SlaBreachedEvent
```

| File | Change |
|---|---|
| `db/migration/V12__run_history_indexes.sql` | **New.** Two indexes (§5) |
| `dto/response/RunHistoryResponse.java` | **New.** Response record plus nested `RunEntry` with `from(CalculatorRun)`, which picks the three on-demand keys out of `additionalAttributes` |
| `repository/CalculatorRunRepository.java` | **Add** `findRunHistory(...)` |
| `service/RunQueryService.java` | **New.** `getRunHistory(...)` |
| `cache/AnalyticsCacheService.java` | **Add** `getRunHistory`/`putRunHistory`. Extract shared `read`/`write` helpers. `onRunStarted` now evicts all keys. Remove the orphaned prefix-eviction helpers. |
| `controller/RunQueryController.java` | **Add** `GET /{calculatorName}/runs` |
| `exception/GlobalExceptionHandler.java` | **Add** a `MethodArgumentTypeMismatchException` → 400 handler (§7.1) |
| Tests | `RunQueryServiceTest` (new), `RunQueryControllerTest`, `AnalyticsCacheServiceTest`, `CalculatorRunRepositoryJdbcTest`, `GlobalExceptionHandlerTest` |
| Docs | `CLAUDE.md` (API + Redis tables, listener table), `docs/consumer-api.md` §3 |

Route note: `/batch/runs` is a literal path, so Spring MVC ranks it above `/{calculatorName}/runs`. A calculator literally named `batch` would be shadowed. That is accepted; no such calculator exists.

## 5. Database design

### 5.1 Query

A single SQL shape. Optional predicates are appended (same idiom as `findAllRunsByDateAndDimension`):

```sql
SELECT <CORE_COLUMNS>, run_parameters, additional_attributes   -- SELECT_WITH_JSONB
FROM calculator_runs
WHERE calculator_name = :calculatorName
  [AND frequency = :frequency]
  [AND reporting_date = :reportingDate]
  [AND (additional_attributes ->> 'odRunId') = :odRunId]
ORDER BY reporting_date DESC, created_at DESC
LIMIT :limit
```

`SELECT_WITH_JSONB` / `CalculatorRunRowMapper(true)` is required because the on-demand attributes live in `additional_attributes`. Fetching the JSONB columns costs little here: rows are capped at 100 and are usually ≤ 20. The three keys are picked out in `RunEntry.from`, not in SQL. Extracting them in SQL (`additional_attributes->>'odRunId' AS od_run_id`, …) would need new `CalculatorRun` fields or a dedicated row mapper: more code for no measurable gain at this row count.

### 5.2 Index evaluation

`calculator_runs` is range-partitioned daily on `reporting_date` and holds ~455 partitions (395-day retention plus 60 future days). Without `reportingDate`, partition pruning is impossible. Two costs follow:

- **Planning** considers the full partition set when producing an unpruned plan. Task 8 measures planning and execution separately rather than assuming either dominates.
- **Execution** can probe partitions newest-first with an ordered plan. The schema provisions partitions **60 days into the future**; when those partitions are empty, they are visited before today's data. Future partitions can also contain valid runs. Their count and occupancy belong in the verification dataset.

The planner picks index scan vs. seq scan **per partition by estimated cost**. For empty or tiny partitions a seq scan is often the cheapest choice. Verification therefore measures outcomes (time, buffers), allowing the cost-based plans described in [PostgreSQL 17's EXPLAIN guide](https://www.postgresql.org/docs/17/using-explain.html) (Task 8).

| Call shape | Pruning | Index used | Why |
|---|---|---|---|
| `reportingDate + frequency + limit=1` | 1 partition | existing `calculator_runs_consumer_batch_idx` `(reporting_date, frequency, calculator_name, run_number, created_at)` or new `calculator_runs_history_idx` | Single partition. Either index is a cheap lookup. |
| `reportingDate + frequency=MONTHLY` | 1 partition | same as above | — |
| `limit=20` (no frequency) | none | **new `calculator_runs_history_idx`** | The existing `consumer_executions_idx` leads `(calculator_name, frequency, …)` and cannot supply this ordering with frequency unconstrained. The new index supports an ordered partition scan that stops once LIMIT is satisfied. With empty future partitions and sufficient recent data, expect ~60 empty probes plus the newest data partitions. A sparse calculator with fewer than `limit` runs can visit all ~455 partitions. |
| `limit=20&frequency=DAILY` | none | existing `consumer_executions_idx` (+ incremental sort on `created_at`) or new history idx with a filter | Already covered. |
| `odRunId=…` | none | **new partial `calculator_runs_od_run_id_idx`** | Avoids scanning all of a calculator's rows to evaluate the JSONB predicate. It does not prune partitions or supply the complete requested sort order. With fewer than `limit` matches, all partitions can be visited. An **unknown `odRunId`** exercises this empty-result path. |

### 5.3 Migration — `V12__run_history_indexes.sql`

```sql
-- Last-N runs with no frequency filter: ORDER BY reporting_date DESC, created_at DESC LIMIT n.
CREATE INDEX IF NOT EXISTS calculator_runs_history_idx
    ON calculator_runs (calculator_name, reporting_date DESC, created_at DESC);

-- On-demand lookup by additional_attributes->>'odRunId'. Partial: only on-demand runs carry the
-- attribute, so only rows with a non-null odRunId create index entries.
CREATE INDEX IF NOT EXISTS calculator_runs_od_run_id_idx
    ON calculator_runs (calculator_name, (additional_attributes ->> 'odRunId'))
    WHERE (additional_attributes ->> 'odRunId') IS NOT NULL;
```

Notes:
- **Partial index matching.** The query predicate `(additional_attributes ->> 'odRunId') = :odRunId` uses a strict operator, so the planner proves it implies `IS NOT NULL` and matches the partial index. The expression text in the query must stay identical to the index expression.
- **Write cost.** The history index adds an entry per inserted run. The partial index evaluates its predicate for every inserted row but creates no entry when `odRunId` is null. Measure ingestion latency before/after on the representative dataset; no percentage overhead is assumed.
- **Partitions.** Indexes on the partitioned parent apply to existing partitions and partitions created later. This endpoint adds no partition-management changes.
- **Rollout risk.** `CREATE INDEX` on a partitioned parent **cannot be `CONCURRENTLY`**, so it takes a `SHARE` lock that blocks inserts/updates while all partitions are built. Run V12 in a low-traffic window. For a zero-downtime alternative, create the index on the parent with `ON ONLY`, build each partition's index `CONCURRENTLY`, then `ALTER INDEX … ATTACH PARTITION`. That needs a non-Flyway runbook and is not done by default.
- **Not promoting `odRunId` to a column.** A promoted column (like `run_number`) needs ingestion changes and an ON CONFLICT update. The expression index gives the same lookup performance with zero ingestion change.

### 5.4 Repository method

```java
public List<CalculatorRun> findRunHistory(String calculatorName, Frequency frequency,
                                          LocalDate reportingDate, String odRunId, int limit) {
    StringBuilder sql = new StringBuilder(SELECT_WITH_JSONB + "WHERE calculator_name = :calculatorName\n");
    MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("calculatorName", calculatorName)
            .addValue("limit", limit);
    if (frequency != null) {
        sql.append("  AND frequency = :frequency\n");
        params.addValue("frequency", frequency.name());
    }
    if (reportingDate != null) {
        sql.append("  AND reporting_date = :reportingDate\n");
        params.addValue("reportingDate", reportingDate);
    }
    if (odRunId != null) {
        sql.append("  AND (additional_attributes ->> 'odRunId') = :odRunId\n");
        params.addValue("odRunId", odRunId);
    }
    sql.append("ORDER BY reporting_date DESC, created_at DESC\nLIMIT :limit");

    Timer.Sample sample = Timer.start(meterRegistry);
    List<CalculatorRun> results = jdbcTemplate.query(sql.toString(), params, new CalculatorRunRowMapper(true));
    sample.stop(Timer.builder(DB_QUERY_DURATION).tag("query", "find_run_history").register(meterRegistry));
    return results;
}
```

Every value is bound as a parameter, so there is no SQL injection surface. `NamedParameterJdbcTemplate` skips the quoted `'odRunId'` literal when it parses parameters.

## 6. Redis cache design

### 6.1 Pattern

**Cache-aside, Redis-first, event-invalidated, TTL as backstop.** This is the same pattern `/executions` uses, and it lives in the same service (`AnalyticsCacheService`).

### 6.2 Keys

| Key | Type | Content | TTL |
|---|---|---|---|
| `obs:analytics:history:{encodedName}:{FREQ\|all}:{reportingDate\|all}:{limit}:{od:none\|od:value:{odRunId}}` | String (JSON) | Serialized `RunHistoryResponse` | 30 s if live, 5 min otherwise |
| `obs:analytics:index:{name}` | Set | Every analytics key (`executions:*` + `history:*`) written for that calculator | 1 h, refreshed on each write |

Examples:

```
obs:analytics:history:capitalcalc:DAILY:2026-10-20:1:od:none          # ?reportingDate=2026-10-20&frequency=DAILY&limit=1
obs:analytics:history:capitalcalc:all:all:20:od:value:od-123          # ?odRunId=od-123
obs:analytics:history:capitalcalc:all:all:20:od:none                  # ?limit=20
obs:analytics:history:capitalcalc:all:all:20:od:value:all             # ?odRunId=all  (distinct from ?limit=20)
obs:analytics:history:capitalcalc:MONTHLY:2026-09-30:20:od:none       # ?reportingDate=2026-09-30&frequency=MONTHLY
```

```java
private String buildRunHistoryKey(String calculatorName, Frequency frequency,
                                  LocalDate reportingDate, String odRunId, int limit) {
    String encodedName = URLEncoder.encode(calculatorName, StandardCharsets.UTF_8);
    return ANALYTICS_PREFIX + RUN_HISTORY_PREFIX + ":" + encodedName
            + ":" + (frequency == null ? "all" : frequency.name())
            + ":" + (reportingDate == null ? "all" : reportingDate)
            + ":" + limit
            + ":" + (odRunId == null ? "od:none" : "od:value:" + odRunId);
}
```

Each distinct filter combination must map to exactly one key, and distinct combinations must never share a key:

- **The calculator name is encoded** with JDK `URLEncoder` and UTF-8 (`java.net.URLEncoder`, `java.nio.charset.StandardCharsets`). Ordinary names such as `capitalcalc` are unchanged; `:` and `%` are escaped. Ingestion accepts free-form names, so encoding prevents a name from impersonating later key segments. The shared index set remains keyed by the original name, matching existing event eviction.
- **Fixed-shape segments** can't collide with their `all` sentinel. Frequency is an enum name (`D` and `DAILY` share a key), the date is ISO `yyyy-MM-dd`, and `limit` is an integer.
- **`odRunId` is free-form caller input**, so no plain sentinel is safe. The first draft used `all` for "absent", and `?odRunId=all` collided with the unfiltered request: whichever ran first poisoned the other's cache (design review, reproduced). The segment is now **tagged**: `od:none` for absent and `od:value:{id}` for present. No value can produce `od:none`, because every present value carries the `od:value:` prefix.
- The free-form segment is **last**, so a `:` inside an `odRunId` can't shift the fixed segments before it.
- The controller normalises a blank `odRunId` to absent before the key is built, so `?odRunId=` and no param share `od:none` by design.
- *Known adjacent issue, out of scope:* the executions key (`AnalyticsCacheService.buildKeyWithRunNumber`) uses an untagged `all` sentinel for `run_number` and an unencoded calculator name. Both are tracked separately.

### 6.3 Read path (`RunQueryService.getRunHistory`)

```
1. nocache=false → GET key
     hit  → deserialize → return                   (obs.cache.analytics.hit{prefix=history})
     miss → continue                               (obs.cache.analytics.miss{prefix=history})
     Redis error → WARN log, treat as miss         (never fails the request)
2. SELECT … LIMIT n  (findRunHistory)
3. map rows → RunHistoryResponse
4. SET key json EX ttl ; SADD index key ; EXPIRE index 1h
     ttl = anyLive() ? 30s : 5m
     Redis error → WARN log, response still returned
5. return
```

```java
public RunHistoryResponse getRunHistory(String calculatorName, Frequency frequency, LocalDate reportingDate,
                                        String odRunId, int limit, boolean nocache) {
    if (!nocache) {
        RunHistoryResponse cached = cacheService.getRunHistory(calculatorName, frequency, reportingDate, odRunId, limit);
        if (cached != null) {
            return cached;
        }
    }
    RunHistoryResponse response = new RunHistoryResponse(calculatorName,
            runRepository.findRunHistory(calculatorName, frequency, reportingDate, odRunId, limit).stream()
                    .map(RunHistoryResponse.RunEntry::from)
                    .toList());
    cacheService.putRunHistory(calculatorName, frequency, reportingDate, odRunId, limit, response,
            response.anyLive() ? LIVE_TTL : SETTLED_TTL);
    return response;
}
```

`anyLive()` is `runs.isEmpty() || any status == RUNNING`. Empty counts as live because an on-demand caller usually polls `?odRunId=` *before* the run starts. That result must expire quickly in case the eviction is ever missed. The method name is deliberately not a bean getter (`is…`/`get…`), so Jackson does not serialize it.

### 6.4 Invalidation (write path)

The listeners in `AnalyticsCacheService` run `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`:

| Event | Fired by | Action |
|---|---|---|
| `RunStartedEvent` | `startRun` | `SMEMBERS obs:analytics:index:{name}` → `DEL` every member plus the index (and the same for the calculator's UI alias index, if any) |
| `RunCompletedEvent` | non-breach `completeRun` | same |
| `SlaBreachedEvent` | breach on completion, or live detection | same |

**Behaviour change:** `onRunStarted` used to evict only `executions:*` keys (prefix filter). It now also evicts history keys by clearing the whole index, matching the other two listeners. `/executions` was already evicted on run start. Remove the unused prefix-filtered helpers (`evictForCalculatorByPrefix`, `evictIndexByPrefix`, `RUN_EXECUTIONS_CACHE_PREFIX`).

Eviction is coarse (per calculator, all filter combinations). This reuses the existing mechanism without teaching events about query filters. Observe cache hit/miss rates under on-demand traffic before considering finer-grained invalidation.

### 6.5 Consistency & staleness analysis

Invalidation is best-effort and asynchronous; it does not guarantee that the next HTTP request sees a committed change. The following TTLs bound server-cache staleness after a stale entry is written.

| Scenario | Server-cache behaviour |
|---|---|
| Normal transition (start/complete/breach) | Eviction follows commit once the async listener runs. Measure the delay; there is no fixed millisecond guarantee. |
| Race: request reads DB *before* commit, writes cache *after* eviction | Bounded by that entry's TTL. The stale entry reflects the *pre-transition* state. For start→complete/breach, the pre-state contains `RUNNING`, so its TTL is **30 s**. For "not yet started" polling, the pre-state is empty, so also **30 s**. |
| Race: a new run starts while the cached result is all-terminal | A pre-start read written after eviction can receive the **5 min** TTL. For example, an unfiltered `?limit=N` entry that predates a new on-demand run can omit it for up to 5 min. (The `?odRunId=` entry itself was empty, so its TTL is 30 s.) `nocache=true` bypasses the stale Redis entry. Accepted. |
| Redis unavailable | Every request falls through to the DB, with WARN logs and miss counters. Correct, just slower. |
| Redis flushed | Cold misses repopulate entries and their index set. |
| Index set lost independently, partial write failure, or concurrent index deletion | A value may remain without an index member and miss later event eviction. Recreating the set does not recover older memberships; entry TTL (**30 s or 5 min**) is the backstop. |
| Index set expires normally while an entry lives | With successful tracking writes, entry TTL (≤ 5 min) is shorter than index TTL (1 h), which is refreshed on every write. |

### 6.6 Sizing

- One `RunEntry` serializes to ~0.5 KB (fixed field set; the open-ended attribute map is not cached). At the default `limit=20` that is ~10 KB per key; the `limit=100` maximum is ~50 KB.
- Key count per calculator is the number of distinct filter combinations requested in the last ≤ 5 min. For on-demand polling that is one key per active `odRunId`. Expected total: low hundreds of keys, under ~5 MB.
- The index set also retains names of expired values until the set expires or an event deletes it. Its refreshed 1 h TTL does not bound membership during continuous traffic. Include set cardinality in sizing measurements; no new cleanup mechanism is added without evidence that it is needed.

### 6.7 HTTP caching

`Cache-Control: private, max-age=5|30` is the client-side layer on top of Redis: 5 s when live, so pollers converge quickly, and 30 s when settled. It is `private` because responses are behind auth.

A client can retain a stale server response for another 5 s or 30 s, respectively. The Redis TTLs in §6.5 are therefore not end-to-end freshness guarantees. `nocache` bypasses Redis reads only; callers requiring a fresh HTTP request must also bypass their client cache.

## 7. Controller

```java
@GetMapping("/{calculatorName}/runs")
public ResponseEntity<RunHistoryResponse> getRunHistory(
        @PathVariable String calculatorName,
        @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reportingDate,
        @RequestParam(required = false) String frequency,
        @RequestParam(required = false) String odRunId,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
        @RequestParam(value = "nocache", defaultValue = "false") boolean nocache) {

    Frequency freq = frequency == null ? null : Frequency.fromStrict(frequency);
    String od = (odRunId == null || odRunId.isBlank()) ? null : odRunId.trim();

    log.info("event=run_history.request outcome=accepted calculatorName={} reportingDate={} frequency={} odRunId={} limit={}",
            calculatorName, reportingDate, freq, od, limit);

    Timer.Sample sample = Timer.start(meterRegistry);
    try {
        RunHistoryResponse response = runQueryService.getRunHistory(calculatorName, freq, reportingDate, od, limit, nocache);
        int maxAgeSeconds = response.anyLive() ? 5 : 30;
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(maxAgeSeconds, TimeUnit.SECONDS).cachePrivate())
                .body(response);
    } finally {
        sample.stop(meterRegistry.timer(ObservabilityConstants.API_ANALYTICS_DURATION,
                "endpoint", "/calculators/{name}/runs"));
    }
}
```

`IllegalArgumentException` (from `fromStrict`) and `ConstraintViolationException` (`@Min`/`@Max`) already map to 400 in `GlobalExceptionHandler`. **Type-conversion failures do not.** See §7.1.

### 7.1 Type-mismatch → 400 (`GlobalExceptionHandler`)

Spring raises `MethodArgumentTypeMismatchException` when a `@RequestParam`/`@PathVariable` value can't be converted (`reportingDate=not-a-date`, `limit=abc`, `nocache=abc`). `GlobalExceptionHandler` has no handler for it today, so the catch-all `Exception` handler returns **500**. This is a pre-existing gap across every endpoint (`/batch/runs?reporting_date=bad` and `/executions?days=abc` behave the same), already listed as **P2.2** in `tasks/todo.md`. Fixing it globally closes P2.2.

```java
@ExceptionHandler(MethodArgumentTypeMismatchException.class)
public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
    HttpStatus status = HttpStatus.BAD_REQUEST;
    String expected = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "valid value";
    String message = "Invalid value for parameter '" + ex.getName() + "': expected " + expected;
    meterRegistry.counter(API_ERROR, "exception", ex.getClass().getSimpleName(), "status", String.valueOf(status.value())).increment();
    log.warn("event=api.error status={} exception={} param={}", status.value(), ex.getClass().getSimpleName(), ex.getName());
    return buildErrorResponse(status, message);
}
```

- This follows the existing 400 handlers (`API_ERROR` counter, `event=api.error` WARN, `buildErrorResponse`).
- The message names the parameter and the target type. It deliberately does **not** echo the rejected value: that is caller input, and it keeps log/response injection off the table.

## 8. Observability

| Signal | Name / tag |
|---|---|
| Endpoint latency | `obs.api.analytics.duration{endpoint="/calculators/{name}/runs"}` |
| DB latency | `obs.db.query.duration{query="find_run_history"}` |
| Cache hit/miss | `obs.cache.analytics.hit|miss{prefix="history"}` |
| Evictions | `obs.cache.analytics.eviction` (shared) |
| Request log | `event=run_history.request outcome=accepted calculatorName=… reportingDate=… frequency=… odRunId=… limit=…` |

## 9. Implementation plan (task-by-task)

Each task is TDD: write the failing test, implement, then make it green.

### Task 1: Migration V12
- Create `src/main/resources/db/migration/V12__run_history_indexes.sql` (§5.3).
- Verify: the app starts on the `local` profile and Flyway applies V12; `\d calculator_runs` lists both indexes.

### Task 2: Response DTO
- Create `dto/response/RunHistoryResponse.java`: record `(calculatorName, List<RunEntry> runs)`, the nested `RunEntry` record (fields per §3), `RunEntry.from(CalculatorRun)`, and `anyLive()`.
- `RunEntry` carries `String odRunId, String consRunVersion, String calculatorRunVersion`, **not** a `Map`. Extraction:

```java
public static RunEntry from(CalculatorRun run) {
    Map<String, Object> attrs = run.getAdditionalAttributes() != null ? run.getAdditionalAttributes() : Map.of();
    return new RunEntry(
            /* … run fields as in §3 … */,
            attr(attrs, "odRunId"),
            attr(attrs, "consRunVersion"),
            attr(attrs, "calculatorRunVersion"));
}

private static String attr(Map<String, Object> attrs, String key) {
    return Objects.toString(attrs.get(key), null);   // numeric → "3"; absent → null
}
```

- **Revision from the current code:** it returns `Map<String, Object> additionalAttributes`. Replace it with the three fields above and update the tests listed in Tasks 4–6.

### Task 3: Repository query
- Test first (`CalculatorRunRepositoryJdbcTest`, Testcontainers; use `LocalDate.now()`/`minusDays(1)` because only partitions from yesterday to +60 days exist):
  - `findRunHistory_noFilters_returnsNewestFirstAcrossDatesAndFrequencies_limited`: the limit applies, the order is date DESC then created_at DESC, and other calculators are excluded.
  - `findRunHistory_reportingDateAndFrequency_filterCombined`
  - `findRunHistory_odRunId_matchesAdditionalAttributesAndLoadsJsonb`: the JSONB filter works and `additionalAttributes` is mapped.
  - `findRunHistory_includesFutureDatedRun_sortedFirst`: a run dated after today is returned and sorts ahead of today's (no implicit date bound, D6).
- Implement `findRunHistory` (§5.4).

### Task 4: Cache support
- Test first (`AnalyticsCacheServiceTest`):
  - `putRunHistory_writesKeyWithTtl_andTracksInNameIndex`: exact key format (`…:{limit}:od:none` / `…:od:value:{id}`), the TTL is passed through, and the key is added to the index.
  - `runHistoryKey_absentOdRunId_differsFromLiteralAll`: `odRunId=null` and `odRunId="all"` produce **different** keys (regression for the design-review collision). Also assert `"none"` ≠ null, and that an id containing `:` yields a distinct key.
  - `runHistoryKey_encodesCalculatorName`: names containing `:` and `%` stay distinct, including a name containing a full key-like suffix paired with a different `odRunId`.
  - `getRunHistory_roundTripsOnDemandFields`: JSON round-trip of the record, including `odRunId`/`consRunVersion`/`calculatorRunVersion` and their `null`s.
  - `onRunStarted_evictsExecutionsAndHistoryKeys_fromNameIndex`: replaces the old prefix-only test.
- Implement:
  - Extract `read(key, prefixTag, type)` / `write(calculatorKey, key, value, ttl)` and make `getFromCache`/`putInCache` delegate to them (no behaviour change).
  - Add `getRunHistory` / `putRunHistory` / `buildRunHistoryKey` (encoded calculator name and tagged `od:` segment, last; §6.2).
  - `onRunStarted` → `evictForCalculator(run)`. Delete the orphaned prefix-eviction helpers and constant.

### Task 5: Service
- Test first (`RunQueryServiceTest`, Mockito): cache hit → no DB call; miss → DB → mapped (the three on-demand fields extracted, a numeric value converted to a string, other attribute keys such as a stray `region` **not** exposed, a missing key → `null`) → cached with `SETTLED_TTL`; RUNNING or empty → `LIVE_TTL`; `nocache` → no cache read, but the cache is still written.
- Implement `RunQueryService` (§6.3).

### Task 6: Controller
- Test first (`RunQueryControllerTest`, `@WebMvcTest`): all filters bound and normalised (`M` → MONTHLY, `odRunId` trimmed); defaults (`null, null, null, 20`); `$.runs[0].odRunId` present and `$.runs[0].additionalAttributes` absent; `anyLive` not serialized; Cache-Control 5/30; 400 for `frequency=WEEKLY`, `limit=0`, `limit=101` with no service call.
- Implement the endpoint (§7). Inject `RunQueryService` into `RunQueryController`.

### Task 7: Type-mismatch → 400 (global; closes P2.2)
- Test first:
  - `GlobalExceptionHandlerTest`: `MethodArgumentTypeMismatchException` → 400, body names the parameter and the expected type, and does not contain the rejected value.
  - `RunQueryControllerTest`: `reportingDate=not-a-date`, `limit=abc`, `nocache=abc` → **400** each, with no service call.
  - Regression on an existing endpoint: `RunQueryControllerTest` `/batch/runs?reporting_date=not-a-date` → 400 (was 500).
- Implement the handler (§7.1). Mark P2.2 done in `tasks/todo.md`.

### Task 8: Verification (outcome-based, realistic data)
- `SPRING_PROFILES_ACTIVE=local mvn clean test` with Docker running: all green, including the JDBC tests.
- **Dataset.** Build a representative local or dev dataset:
  - full partition set: approximately 395 days back plus 60 future. Create historical daily partitions explicitly in test setup; the existing zero-argument `create_calculator_run_partitions()` creates only yesterday through +60 days;
  - representative row volumes per day and per calculator, recording the source and assumptions. `get_partition_statistics()` alone does not provide per-calculator counts;
  - mostly empty future partitions, plus at least one valid future-dated run to verify that no implicit date bound drops it;
  - `ANALYZE calculator_runs` afterwards, so plans use real statistics.
- **Scenarios** (each run cold and warm; `nocache=true` for the DB path):

  | # | Scenario | Why |
  |---|---|---|
  | S1 | Busy calculator, `?limit=20` | Common case: ~60 empty future probes plus newest data |
  | S2 | **Sparse** calculator (fewer than 20 runs in retention), `?limit=20` | Worst case: LIMIT never satisfied, all ~455 partitions probed |
  | S3 | Known `odRunId` | On-demand happy path |
  | S4 | **Unknown** `odRunId` | Worst case: all partitions probed, empty result |
  | S5 | Calculator with **no runs at all** | Empty result across every partition |
  | S6 | `reportingDate` + `frequency` (DAILY and MONTHLY) | Pruned path: one partition |
  | S7 | `?limit=100` on a busy calculator | Largest result / payload |

- **Record for each:** repeated `EXPLAIN (ANALYZE, BUFFERS, SUMMARY)` samples with **Planning Time**, **Execution Time**, and shared hit/read buffers, plus end-to-end API p95 over ≥ 200 calls (cache bypassed, then cached). State the dataset, environment, concurrency, and database-buffer warm/cold conditions; Redis bypass does not make database buffers cold.
- **Pass criteria (outcomes, not plan shapes):**
  - Provisional validation targets: DB planning + execution p95 **< 50 ms** across S1–S7; cached API p95 **< 5 ms** in the stated test environment. These are targets to validate, not measured results or an established production SLO.
  - Compare buffers and rows examined with and without V12 indexes on the same dataset. Investigate scans of substantial non-matching data; allow inexpensive heap access and scans of tiny partitions.
  - Inspect partition pruning for `reportingDate` and ordered early termination when sufficient matching rows exist. Do not require a particular node type or generated child-index name.
- **If targets are missed:** identify the measured planning, sorting, I/O, or application cost and revise the smallest relevant part of the design. Keep the all-retained-rows contract; do not silently add a date cutoff.
- **Cache behaviour:** seed both a run with `odRunId="all"` and an unrelated run; warm filtered and unfiltered requests in both orders and verify each response matches its own DB result. The results need not differ for arbitrary datasets. Confirm repeat calls hit, then publish a lifecycle event, wait for its async eviction, and verify a fresh request misses. Cover start, completion, and breach in listener tests.

### Task 9: Docs
- `CLAUDE.md`: API table row, Redis key table row (`obs:analytics:history:*`), index-set description, `AnalyticsCacheService` listener row.
- `docs/consumer-api.md`: §3 for the new endpoint, plus the "Choosing an endpoint" table. "Common errors": malformed parameters → 400 (all endpoints, after Task 7). Correct the tenancy statement: `X-Tenant-Id` is optional and does not filter results (it previously claimed "required" and "tenant-scoped", contradicting §3 and the code).
- Reconcile `docs/plans/run-history-api-epic.md` with this plan before copying issues: encoded-name/tagged-`od` keys, global 400 handling, bounded result counts, eventual invalidation, and outcome-based performance criteria.

## 10. Test matrix

| Layer | Test | Covers |
|---|---|---|
| Repo (Testcontainers) | 4 tests in `CalculatorRunRepositoryJdbcTest` | SQL correctness, order (incl. future-dated runs), limit, each filter, JSONB mapping |
| Cache | Focused cases in `AnalyticsCacheServiceTest` | key format, **null vs `"all"` odRunId separation**, encoded names, TTL, index tracking, JSON round-trip, eviction on start/completion/breach |
| Service | 4 tests in `RunQueryServiceTest` | cache-aside flow, TTL selection, nocache |
| Controller | 4 tests in `RunQueryControllerTest` (+1 `/batch/runs` regression) | binding, defaults, normalisation, validation 400s **incl. type mismatch**, Cache-Control, no `anyLive` leak |
| Exception handling | 1 test in `GlobalExceptionHandlerTest` | type mismatch → 400, message shape, no echoed value |
| Performance | Task 8 scenarios S1–S7 | planning + execution time, buffers on realistic data |

## 11. Risks & open items

| Risk | Mitigation |
|---|---|
| V12 blocks writes during the index build in prod | Low-traffic window, or the `ON ONLY` + per-partition `CONCURRENTLY` runbook (§5.3) |
| Airflow sends numeric versions | Converted to strings (`"3"`); switch the fields to `Long` if Issue 1 confirms numeric types (before first consumer release, since it changes the contract) |
| Airflow sends a different key spelling (e.g. `od_run_id`) | Contract agreed as `odRunId`. The lookup is case-sensitive and exact, so a mismatch returns empty, not an error. Confirm with the Airflow team before go-live. |
| Unpruned queries (sparse calculator, unknown `odRunId`, empty calculator) can plan and probe all ~455 partitions, including future ones | Measure in Task 8 (S2/S4/S5). Investigate measured cost before changing the design, preserving all retained dates. Each unique query still pays the first cache miss. |
| Cache-key collision between filter combinations | Encoded calculator name plus tagged `od:` segment, placed last (§6.2), with regression tests (Task 4) |
| Malformed params returning 500 | Global type-mismatch handler (§7.1, Task 7) |
| Coarse eviction reduces cache reuse as on-demand traffic grows | Reuse the existing index-set mechanism and observe hit/miss rates before adding complexity. `/executions` already evicts on start. |

## 12. Out of scope (YAGNI)

- Pagination or cursors beyond `limit` (max 100)
- Filtering by `consRunVersion` / `calculatorRunVersion` (add an expression index only when a real query needs it)
- Exposing any other `additional_attributes` key (each one is a deliberate contract addition to `RunEntry`)
- Alias resolution, split/rerun collapsing
- Promoting `odRunId` to a first-class column
- Tenant filtering (`tenantId` is not used for filtering anywhere in the service)

## 13. Design review log

| Date | Finding | Severity | Resolution |
|---|---|---|---|
| 2026-10-21 | Contract: returning the whole `additionalAttributes` map leaks internal keys and makes every key an implicit contract | — | D5 revised: three explicit fields only (§3, Task 2) |
| 2026-10-21 | Cache keys collide for absent `odRunId` vs literal `"all"`; an unfiltered request could be served filtered results and vice versa | P2 | Tagged `od:none` / `od:value:{id}` segment, moved last (§6.2); regression test (Task 4) |
| 2026-10-21 | Malformed `reportingDate` / `limit` / `nocache` return 500, contradicting the 400 error contract (`MethodArgumentTypeMismatchException` reaches the catch-all) | P2 | Global 400 handler (§7.1) with controller and handler tests (Task 7); closes P2.2 |
| 2026-10-21 | Performance verification asserted plan node types (incl. "no Seq Scan") on unrealistic data, and "typically 1–2 partitions" ignored empty future partitions and planning cost | P2 | §5.2 corrected; Task 8 measures realistic datasets, worst cases, planning/execution time, buffers, and provisional latency targets |
| 2026-10-21 | Re-evaluation: implicit date bounds can hide valid runs; free-form calculator names can impersonate cache-key segments | P2 | Removed speculative date-bound fallback (D6); encode names using the JDK and test collisions (§6.2, Task 4). Also clarified result limits and best-effort cache freshness. |
| 2026-10-21 | Consistency pass: future-dated ordering undocumented/untested; consumer doc contradicts tenancy; status, §7 snippet and adjacent-issue note incomplete; §6.5 race wording ambiguous | — | §3 ordering note + Task 3 test; Task 9 tenancy correction; status, §7, §6.2 and §6.5 updated |
