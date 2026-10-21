# Run History Cache v2 (generation-invalidated): Design & Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the index-set cache behind `GET /api/v1/calculators/{calculatorName}/runs` with a per-calculator **generation** cache. After a successful invalidation, requests that subsequently resolve the generation cannot retrieve snapshots from the previous generation. In-flight requests may still return an earlier snapshot (eventual consistency, §4.4).

**Supersedes:** §6 (Redis cache design) of [2026-10-21-run-history-api-design.md](2026-10-21-run-history-api-design.md). The API contract, SQL, indexes (V12), controller validation and HTTP `Cache-Control` from that document are **unchanged**.

**Architecture:** `RunQueryService` → `CalculatorRunHistoryCacheService.getOrLoad(…, loader)`. The cache service captures the calculator's generation, reads the snapshot, and on a miss runs the loader (Postgres) and writes the snapshot under the **captured** generation. Lifecycle events (AFTER_COMMIT, async) replace the generation with a new random token, which invalidates every filter combination for that calculator in one `SET`.

**Tech Stack:** Java 17, Spring Boot 3.5.9, `StringRedisTemplate` (Lettuce) + Jackson, Micrometer, JUnit 5 + Mockito + Testcontainers (`redis:7-alpine`).

**Status:** Tasks 1–7 implemented on `main` (uncommitted, 2026-10-21). Unit, properties, service and controller tests are green. **Pending, blocked on Docker:** the Redis integration test (Task 3), the listener wiring test (Task 3b), and Task 8 steps 2–4. Revised after two design reviews (§13).

---

## 1. Why replace the current cache

The current implementation uses `AnalyticsCacheService`: snapshot keys `obs:analytics:history:*`, tracked in the `obs:analytics:index:{name}` set, and evicted with `SMEMBERS` + `DEL` on lifecycle events. It has two defects.

| # | Defect | Concrete failure |
|---|---|---|
| 1 | **Stale write after eviction.** Eviction deletes the keys that exist *at eviction time*. A request whose DB read happened *before* the commit writes its snapshot *after* the eviction and re-registers it in the index. Nothing evicts it again. | `?limit=1`: yesterday's run is SUCCESS, today's run STARTs. A request that read before the START commit writes "yesterday, SUCCESS". That snapshot is all-terminal, so it gets the **5 min** TTL. The dashboard misses today's RUNNING run for up to 5 min. |
| 2 | **Index cost grows with traffic.** The index set keeps members whose values have already expired. Eviction cost is O(members). | Under steady polling the index set's cardinality grows until the next event or its 1 h TTL. |

Generation-in-key fixes both:
- A late write lands under a generation nobody reads any more.
- Invalidation is one O(1) `SET`.

## 2. Assumptions

1. Run history is not tenant-filtered. `X-Tenant-Id` is optional and is not part of the query (CLAUDE.md, *Multi-tenancy*). The cache scope is therefore the calculator name only.
2. The history endpoint queries the **exact** `calculator_name` from the path. It does not expand UI aliases (no `CalculatorNameResolver` in the path). Invalidation therefore targets the run's `calculator_name` only.
3. Every `calculator_runs` mutation that affects history results **publishes** one of `RunStartedEvent`, `RunCompletedEvent`, `SlaBreachedEvent` **inside its transaction**, and the listeners **handle** it **after commit**. A publish outside a transaction is silently ignored by `@TransactionalEventListener`, because no listener sets `fallbackExecution`. Task 3b pins this wiring. This was verified on 2026-10-21:
   - publish sites: `RunIngestionService.java:181`, `:302`, `:304` and `LiveSlaBreachDetectionJob.java:233`;
   - `startRun` and `completeRun` are `@Transactional` (`RunIngestionService.java:59`, `:193`), and the live job publishes inside `transactionTemplate.execute` (`LiveSlaBreachDetectionJob.java:225-235`);
   - the only `UPDATE` statement, `markSlaBreach` (`CalculatorRunRepository.java:286`), is always followed by `SlaBreachedEvent`;
   - partition retention drops publish nothing. Those rows are older than 395 days, and a stale snapshot is bounded by its TTL.
4. `findRunHistory` is a **single SQL statement** under READ COMMITTED. Its snapshot is taken at statement start. The correctness argument in §4 relies on this.
5. Azure Cache for Redis may evict keys that carry a TTL under memory pressure (default policy `volatile-lru`). The design must stay correct when **any** key disappears at any time.

## 3. Decisions

| ID | Decision | Rejected alternative and why |
|---|---|---|
| C1 | **Generation per calculator, embedded in every snapshot key.** | Index set + `DEL` (current): has the stale-write race and grows unbounded. `SCAN` + `DEL`: O(keyspace). |
| C2 | **Generation = random 64-bit token, replaced by `SET` on invalidation.** It is not an `INCR` counter. | `INCR`: if the generation key is evicted or expires, the counter restarts at 1. A snapshot written under an old `1` becomes reachable again (ABA, §4.3). |
| C3 | **Capture the generation once, before the DB read, and always write under it.** There is no re-check after the read. | "Re-resolve after the query and write under the new generation": that caches pre-mutation data under the post-mutation generation, which is exactly the staleness C1 removes. A re-check also can't close the window between itself and the `SET`. |
| C4 | **Scope = URL-encoded `calculatorName`** (no tenant). | Tenant-scoped keys: the query isn't tenant-filtered, and an event can only invalidate the run's own tenant scope. Readers sending another header, or none, would keep stale snapshots. Identical data would also be duplicated per tenant. |
| C5 | **No miss-lock in v1.** | `SET NX PX` miss-lock: adds one round trip to every miss and up to 100 ms waits for non-holders, against an index-served `LIMIT ≤ 100` query. Deferred until telemetry justifies it (§11). |
| C6 | **A malformed, incompatible or invalid snapshot is a miss** (`lookup=corrupt`). Invalid means it fails `isValid` (§7.3): wrong or missing calculator name, a null entry, or an entry without `runId` or a known `status`. **It is never deleted**; the write after the load overwrites it. Incompatible schema changes **bump the key version** (`v2` → `v3`). | Delete-on-corrupt: races with a valid concurrent write from another pod and thrashes during rolling deploys. |
| C7 | **`nocache=true` = refresh.** Skip the snapshot read, load from the DB, and overwrite the snapshot under the captured generation. Every `nocache` request costs one DB query. It is authenticated and capped at `limit ≤ 100`, so it is documented but not rate-limited. | "Disable cache": would leave a known-stale snapshot in place for other readers. |
| C8 | **`enabled` gates reads and writes only. Invalidation always runs.** | Gating invalidation too: in a mixed fleet (rollout/rollback, per-pod config drift), a pod with the cache off would stop invalidating for pods with it on. |
| C9 | **Explicit service, not `@Cacheable`.** | `@Cacheable` can't express capture-generation-then-write, nocache-refresh, state-aware TTL, or swallowing Redis errors on read. |
| C10 | **The snapshot type is `RunHistoryResponse` itself.** Jackson returns a new immutable record on every hit, so no outer response needs rebuilding. | A separate snapshot DTO: no per-request fields exist today. Add one only when the response gains a per-request field (e.g. `generatedAt`). |
| C11 | **`/executions` stays on the index-set mechanism.** | Migrating it too: out of scope. Two mechanisms coexist, so CLAUDE.md documents both. |

## 4. Correctness

### 4.1 Read / miss / nocache algorithm

```
getOrLoad(name, freq, date, odRunId, limit, nocache, loader):
  if !enabled                         → return loader()                        (no Redis I/O)
  scope = urlEncode(name)
  gen   = GET  g:{scope}
          missing → SET g:{scope} <random> NX PX generationTtl ; use stored value
          Redis error / still missing → return loader()                        (DB only, no write)
  key   = r:{scope}:{gen}:{date|all}:{FREQ|all}:{limit}:{od:none|od:value:{odRunId}}
  if !nocache:
      json = GET key
          hit, isValid  → return deserialized                                  (lookup=hit)
          absent        → continue                                             (lookup=miss)
          malformed / !isValid → continue, NOT deleted                         (lookup=corrupt)
          Redis error   → continue                                             (lookup=error)
  else                                                                         (lookup=bypass)
  resp = loader()                                     ← DB errors propagate (500 via global handler)
  SET key json PX (resp.anyLive() ? transientTtl : terminalTtl)   ← captured gen; errors swallowed
  return resp

on RunStarted / RunCompleted / SlaBreached  (AFTER_COMMIT, @Async):
  SET g:{urlEncode(run.calculatorName)} <random> PX generationTtl       (errors swallowed + metered)
```

### 4.2 Why writing under the captured generation is always safe

Take a mutation M that commits at time `c`, with its invalidation `I` executing at time `i > c`. AFTER_COMMIT guarantees `i > c`. A request captures generation `g` at time `t_g`, then runs the DB statement with snapshot time `t_s > t_g`.

- **The DB read missed M** (`t_s < c`): then `t_g < t_s < c < i`. So the generation was captured *before* `I`, and `I` replaces `g` with `g'`. The snapshot written under `g` is unreachable to every reader that resolves the generation after `i`. ✔
- **`I` happened before the capture** (`i < t_g`): then `c < i < t_g < t_s`. The DB read sees M, and the snapshot under `g'` is correct. ✔
- **`c < t_s` and `t_g < i`**: the read saw M but is written under the old `g`. It becomes unreachable after `I`, which costs one extra miss. ✔

**Scope of the guarantee:** it covers readers that **resolve the generation after `i`**. Two windows remain, and both are accepted under the eventual-consistency model:
- **Invalidation lag** `c → i` (async executor queueing plus one Redis round trip). Readers still resolve `g` and can be served pre-M snapshots.
- **In-flight readers.** A request that resolved `g` before `i` and reads its snapshot after `i` still returns the `g` snapshot.

Neither is eliminated. Neither has a measured bound in production (§8).

### 4.3 Why a random token rather than `INCR` (ABA)

With `INCR`, if the generation key is missing, readers use `0` and an invalidation `INCR` creates `1`.
1. Readers write snapshot `S` under `0`.
2. A START triggers `INCR`, so the generation is `1`.
3. Redis evicts `g:{scope}` (it has a TTL, so it is eligible under `volatile-lru`).
4. Readers find no key, use `0`, and are **served `S`, the pre-START snapshot**.

With random tokens, a missing key is seeded with a new token that matches no live snapshot. The probability of a match is 2⁻⁶⁴ per seed, against at most a few hundred live tokens per calculator. ✔

Consequences:
- Correctness no longer depends on `generationTtl > terminalTtl`. That relation now only avoids needless re-seeding on quiet calculators, which wastes a cold miss.
- Losing the generation key costs one miss per key, never a stale read.

### 4.4 Staleness after this change

| Scenario | Server-side staleness |
|---|---|
| Normal start / complete / breach | Invalidation lag (commit → async `SET`). It is **not measured in production**; §8 gives only a rough dev estimate. |
| Request in flight across the invalidation | It may return the previous generation's snapshot once. Later requests resolve the new generation. |
| DB read before commit, write after invalidation (defect #1) | **None** for requests resolving the generation after invalidation. The write lands under the dead generation. |
| Invalidation `SET` fails (Redis error at that moment) | Bounded by the snapshot TTL: 10 s live/empty, **5 min** all-terminal. Visible as `obs.cache.history.invalidation{outcome=error}`. |
| Invalidation never runs: pod dies between commit and the async task, or the `taskExecutor` rejects the task (bounded queue of 100, abort policy) | Bounded by the snapshot TTL. **Not visible in `invalidation{outcome=error}`**, because the listener never executes. Zero error counts therefore do not prove every mutation was invalidated. |
| Generation key evicted or expired | None. It is re-seeded, and every key misses once. |
| Snapshot evicted or expired | None. It is a miss. |
| Redis down | None. Every request goes to the DB (lookup `error`), and no writes happen. |
| Rolling deploy, old pods still ingesting | Old pods don't replace generations. New-pod snapshots can be stale up to their TTL for mutations handled by old pods, **during the rollout window only**. Accepted. |
| Client HTTP cache | Unchanged: up to `max-age` 5 s (live) or 30 s (settled) on top of the above (design doc §6.7). |

## 5. Keys

| Key | Type | Value | TTL |
|---|---|---|---|
| `obs:calculator-runs:v2:g:{scope}` | String | Generation token, 16 lowercase hex chars | `generation-ttl` (15 min), set on every invalidation and seed. **Reads do not refresh it.** |
| `obs:calculator-runs:v2:r:{scope}:{gen}:{reportingDate\|all}:{FREQ\|all}:{limit}:{od:none\|od:value:{odRunId}}` | String (JSON) | `RunHistoryResponse` | `transient-ttl` (10 s) if empty or any RUNNING, else `terminal-ttl` (5 min) |

`{scope}` = `URLEncoder.encode(calculatorName, UTF_8)`.

Examples, with `gen = 9f3c0a1b2c3d4e5f`:

```
obs:calculator-runs:v2:g:capitalcalc
obs:calculator-runs:v2:r:capitalcalc:9f3c0a1b2c3d4e5f:2026-10-20:DAILY:1:od:none     # ?reportingDate=2026-10-20&frequency=DAILY&limit=1
obs:calculator-runs:v2:r:capitalcalc:9f3c0a1b2c3d4e5f:all:all:20:od:value:od-123     # ?odRunId=od-123
obs:calculator-runs:v2:r:capitalcalc:9f3c0a1b2c3d4e5f:all:all:20:od:none             # ?limit=20
obs:calculator-runs:v2:r:capitalcalc:9f3c0a1b2c3d4e5f:all:all:20:od:value:none       # ?odRunId=none  (≠ ?limit=20)
obs:calculator-runs:v2:r:capitalcalc:9f3c0a1b2c3d4e5f:2026-09-30:MONTHLY:20:od:none  # ?reportingDate=2026-09-30&frequency=MONTHLY
```

Injectivity rules (each filter combination maps to exactly one key, and no two combinations share one):
- **`scope` is URL-encoded**, so a name can't contain `:` and can't impersonate later segments. `URLEncoder` is injective: a space becomes `+` and a literal `+` becomes `%2B`.
- **`gen`, date, frequency and limit are fixed-shape**: hex, ISO `yyyy-MM-dd`, an enum name (`D` and `DAILY` share a key by design), and an integer. None of them can equal the `all` sentinel.
- **`odRunId` is free-form**, so it is **tagged** and **last**:
  - `od:none` cannot be produced by any present value, because every present value carries `od:value:`;
  - a `:` inside the id can't shift any segment before it;
  - a blank `odRunId` is normalised to absent by the controller (existing behaviour).
- **The version segment `v2`** must be bumped on any change to `RunHistoryResponse` that an older pod can't read correctly (C6).
  - Spring Boot's `ObjectMapper` ignores unknown properties, so **adding** a nullable field is safe without a bump.
  - **Removing, renaming or re-typing** a field requires one.

## 6. Configuration

`application.yml`, under the existing `observability:` block:

```yaml
  # Run-history cache for GET /calculators/{name}/runs. Generation-invalidated by run lifecycle events;
  # TTLs are a safety bound, not the freshness mechanism. See docs/plans/2026-10-21-run-history-cache-plan.md.
  run-history-cache:
    enabled: true          # false → DB-only reads (invalidation still runs)
    transient-ttl: 10s     # snapshot is empty or contains a RUNNING run
    terminal-ttl: 5m       # all runs terminal (SUCCESS / FAILED / TIMEOUT / CANCELLED)
    generation-ttl: 15m    # token lifetime since last invalidation/seed (reads don't refresh); keep > terminal-ttl (efficiency, not correctness)
```

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Kill switch for reads and writes. `false` keeps the API contract and serves from the DB. |
| `transient-ttl` | `10s` | Snapshot with no runs, or with any `RUNNING` run. (`RUNNING` is the only non-terminal `RunStatus`; `NOT_STARTED` exists only as a `/batch/runs` projection.) |
| `terminal-ttl` | `5m` | Non-empty snapshot whose runs are all terminal. |
| `generation-ttl` | `15m` | Lifetime of a calculator's generation token since its last invalidation or seed. Reads don't refresh it, so a calculator with no lifecycle events is re-seeded every 15 min, and each of its keys takes one cold miss. |

**Do not add a long-lived historical TTL** (e.g. hours for past reporting dates) on the strength of metrics. A zero `invalidation{outcome=error}` count can't show that every mutation was invalidated: a pod crash after commit and an executor rejection are both invisible to it (§8). A longer tier needs a separate review that covers:
1. **Mutation coverage.** Every write path to `calculator_runs` publishes a lifecycle event inside its transaction (assumption 3, Task 3b), including any paths added since this plan.
2. **Missed-event behaviour.** What bounds staleness when an invalidation is lost? Today it is only the snapshot TTL.
3. **Acceptable staleness.** An explicit sign-off that the proposed TTL is an acceptable worst case for settled past dates.

Until that review, the initial TTLs (10 s / 5 min) stand.

## 7. Implementation

### 7.1 Metrics constants: `util/ObservabilityConstants.java`

Add next to the other `CACHE_*` constants:

```java
    /** Run-history cache lookups; tag outcome = hit | miss | corrupt | error | bypass. */
    public static final String CACHE_HISTORY_LOOKUP = "obs.cache.history.lookup";
    /** Run-history snapshot writes; tag outcome = success | error. */
    public static final String CACHE_HISTORY_WRITE = "obs.cache.history.write";
    /** Run-history generation invalidations; tag outcome = success | error. */
    public static final String CACHE_HISTORY_INVALIDATION = "obs.cache.history.invalidation";
```

### 7.2 Properties: `config/RunHistoryCacheProperties.java` (new)

This follows the `AggregationProperties` pattern. `spring-boot-starter-validation` is already on the classpath because the controllers use `jakarta.validation`.

```java
package com.company.observability.config;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Run-history cache ({@code GET /calculators/{name}/runs}). Snapshots are generation-invalidated
 * by run lifecycle events; the TTLs only bound staleness if an invalidation is lost.
 */
@Component
@ConfigurationProperties(prefix = "observability.run-history-cache")
@Validated
@Getter
@Setter
public class RunHistoryCacheProperties {

    /** false → reads go straight to the DB and nothing is written. Invalidation still runs. */
    private boolean enabled = true;

    /** Snapshot that is empty or contains a RUNNING run. */
    @DurationMin(seconds = 1)
    private Duration transientTtl = Duration.ofSeconds(10);

    /** Non-empty snapshot whose runs are all terminal. */
    @DurationMin(seconds = 1)
    private Duration terminalTtl = Duration.ofMinutes(5);

    /** Token lifetime since its last invalidation or seed (reads don't refresh it). Keep above terminalTtl (efficiency only). */
    @DurationMin(seconds = 1)
    private Duration generationTtl = Duration.ofMinutes(15);
}
```

### 7.3 Cache service: `cache/CalculatorRunHistoryCacheService.java` (new)

```java
package com.company.observability.cache;

import com.company.observability.config.RunHistoryCacheProperties;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.dto.response.RunHistoryResponse;
import com.company.observability.event.RunCompletedEvent;
import com.company.observability.event.RunStartedEvent;
import com.company.observability.event.SlaBreachedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.company.observability.util.ObservabilityConstants.*;

/**
 * Generation-invalidated cache for {@code GET /calculators/{name}/runs}.
 *
 * <p>Every snapshot key embeds the calculator's current generation token. A run lifecycle event
 * (after commit) replaces the token, which makes every snapshot of that calculator unreachable in
 * one {@code SET}. A request captures the token <em>before</em> its DB read and writes under that
 * token, so a read that raced a commit lands under a dead generation: requests that resolve the
 * generation after the invalidation never see it. (A request already in flight across the
 * invalidation may still return the previous snapshot once — accepted eventual consistency.)
 * Tokens are random, not counters, so a lost/evicted generation key can't resurrect old snapshots.
 *
 * <p>Redis is best-effort throughout: any Redis or serialization failure degrades to a DB read.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CalculatorRunHistoryCacheService {

    static final String KEY_PREFIX = "obs:calculator-runs:v2:";

    private static final Set<String> KNOWN_STATUSES = Arrays.stream(RunStatus.values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final RunHistoryCacheProperties properties;

    /**
     * Returns the cached snapshot for this filter combination, or runs {@code loader} (the DB read),
     * caches its result and returns it. {@code nocache=true} skips the read but still refreshes the
     * snapshot. Loader exceptions propagate; cache failures never do.
     */
    public RunHistoryResponse getOrLoad(String calculatorName, Frequency frequency, LocalDate reportingDate,
                                        String odRunId, int limit, boolean nocache,
                                        Supplier<RunHistoryResponse> loader) {
        if (!properties.isEnabled()) {
            return loader.get();
        }
        String scope = scope(calculatorName);
        String generation = resolveGeneration(scope);
        if (generation == null) {
            return loader.get();                     // Redis unavailable: DB only, nothing safe to write under
        }
        String key = snapshotKey(scope, generation, reportingDate, frequency, limit, odRunId);

        if (nocache) {
            countLookup("bypass");
        } else {
            RunHistoryResponse cached = read(key, calculatorName);
            if (cached != null) {
                return cached;
            }
        }

        RunHistoryResponse response = loader.get();
        write(key, response);                        // captured generation, never re-resolved (plan §4.2)
        return response;
    }

    // ================================================================
    // Invalidation — runs regardless of `enabled` (a pod with the cache off must still
    // invalidate for pods that have it on).
    // ================================================================

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onRunStarted(RunStartedEvent event) {
        invalidate(event.getRun().getCalculatorName(), "run_started");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onRunCompleted(RunCompletedEvent event) {
        invalidate(event.getRun().getCalculatorName(), "run_completed");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onSlaBreached(SlaBreachedEvent event) {
        invalidate(event.getRun().getCalculatorName(), "sla_breached");
    }

    void invalidate(String calculatorName, String trigger) {
        try {
            redisTemplate.opsForValue().set(generationKey(scope(calculatorName)), newGeneration(),
                    properties.getGenerationTtl());
            meterRegistry.counter(CACHE_HISTORY_INVALIDATION, "outcome", "success").increment();
            log.debug("event=history_cache.invalidate outcome=success calculator={} trigger={}", calculatorName, trigger);
        } catch (Exception e) {
            meterRegistry.counter(CACHE_HISTORY_INVALIDATION, "outcome", "error").increment();
            log.warn("event=history_cache.invalidate outcome=failure calculator={} trigger={} error={}",
                    calculatorName, trigger, e.getClass().getSimpleName());
        }
    }

    // ================================================================
    // Internals
    // ================================================================

    /** Current token; seeds one with NX when missing. Null when Redis is unavailable. */
    private String resolveGeneration(String scope) {
        String generationKey = generationKey(scope);
        try {
            ValueOperations<String, String> ops = redisTemplate.opsForValue();
            String generation = ops.get(generationKey);
            if (generation != null) {
                return generation;
            }
            String seeded = newGeneration();
            if (Boolean.TRUE.equals(ops.setIfAbsent(generationKey, seeded, properties.getGenerationTtl()))) {
                return seeded;
            }
            return ops.get(generationKey);           // lost the seeding race; null only if it vanished again
        } catch (Exception e) {
            countLookup("error");
            log.warn("event=history_cache.generation outcome=failure scope={} error={}",
                    scope, e.getClass().getSimpleName());
            return null;
        }
    }

    private RunHistoryResponse read(String key, String calculatorName) {
        String json;
        try {
            json = redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            countLookup("error");
            log.warn("event=history_cache.read outcome=failure key={} error={}", key, e.getClass().getSimpleName());
            return null;
        }
        if (json == null) {
            countLookup("miss");
            return null;
        }
        RunHistoryResponse cached;
        try {
            cached = objectMapper.readValue(json, RunHistoryResponse.class);
        } catch (Exception e) {
            return corrupt(key, e.getClass().getSimpleName());
        }
        if (!isValid(cached, calculatorName)) {
            return corrupt(key, "InvalidSnapshot");
        }
        countLookup("hit");
        return cached;
    }

    /**
     * Nothing that fails this check may leave the cache boundary: the controller and the TTL choice
     * call {@code anyLive()}, which dereferences every entry's status. Only identity and status are
     * required — display fields may be null, so adding a nullable field stays non-breaking (plan §5).
     */
    static boolean isValid(RunHistoryResponse snapshot, String calculatorName) {
        return snapshot != null
                && calculatorName.equals(snapshot.calculatorName())
                && snapshot.runs() != null
                && snapshot.runs().stream().allMatch(e -> e != null
                        && e.runId() != null
                        && e.status() != null && KNOWN_STATUSES.contains(e.status()));
    }

    /** Not deleted: the write after the DB load overwrites it (plan C6). Never logs payload. */
    private RunHistoryResponse corrupt(String key, String reason) {
        countLookup("corrupt");
        log.warn("event=history_cache.read outcome=corrupt key={} error={}", key, reason);
        return null;
    }

    private void write(String key, RunHistoryResponse response) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(response),
                    response.anyLive() ? properties.getTransientTtl() : properties.getTerminalTtl());
            meterRegistry.counter(CACHE_HISTORY_WRITE, "outcome", "success").increment();
        } catch (Exception e) {
            meterRegistry.counter(CACHE_HISTORY_WRITE, "outcome", "error").increment();
            log.warn("event=history_cache.write outcome=failure key={} error={}", key, e.getClass().getSimpleName());
        }
    }

    private void countLookup(String outcome) {
        meterRegistry.counter(CACHE_HISTORY_LOOKUP, "outcome", outcome).increment();
    }

    private static String newGeneration() {
        return String.format("%016x", ThreadLocalRandom.current().nextLong());
    }

    // ================================================================
    // Key builders (package-private for tests) — plan §5
    // ================================================================

    static String scope(String calculatorName) {
        return URLEncoder.encode(calculatorName, StandardCharsets.UTF_8);
    }

    static String generationKey(String scope) {
        return KEY_PREFIX + "g:" + scope;
    }

    static String snapshotKey(String scope, String generation, LocalDate reportingDate, Frequency frequency,
                              int limit, String odRunId) {
        return KEY_PREFIX + "r:" + scope + ":" + generation
                + ":" + (reportingDate == null ? "all" : reportingDate)
                + ":" + (frequency == null ? "all" : frequency.name())
                + ":" + limit
                + ":" + (odRunId == null ? "od:none" : "od:value:" + odRunId);
    }
}
```

Implementation notes:
- **Snapshot validation (`isValid`)** runs before a value counts as a hit. It rejects a JSON `null`, a missing or different `calculatorName` (this also catches a key-building bug serving another calculator's data), missing `runs`, null entries, and entries without `runId` or with a status outside `RunStatus`. An unknown status would otherwise silently be treated as settled (5 min TTL, `max-age=30`).
- **`KNOWN_STATUSES.contains` is guarded by `status != null`**, because immutable sets from `Collectors.toUnmodifiableSet()` throw `NullPointerException` on `contains(null)`.
- **Logging never includes payload.** Only the exception class name (or `InvalidSnapshot`) is logged. Jackson ≥ 2.16 already excludes source content from exception messages, but logging the class name doesn't depend on that.
- **Keys and calculator names are logged at WARN.** They are not sensitive: the same values appear in request logs (`event=run_history.request`).
- **The redundant `ops.get` after a lost `setIfAbsent`** is deliberate. Another pod seeded the token first, and this pod must use that pod's token rather than its own.
- **A `null` calculator name** cannot occur. Ingestion requires it and the path variable is mandatory. If it ever did, `URLEncoder.encode(null)` would throw inside the `try` and degrade to a DB read or a metered invalidation failure.

### 7.4 Service: `service/RunQueryService.java` (replace body)

```java
package com.company.observability.service;

import com.company.observability.cache.CalculatorRunHistoryCacheService;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.dto.response.RunHistoryResponse;
import com.company.observability.repository.CalculatorRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Generic run-history reads (last-N runs, by reporting date, by on-demand {@code odRunId}).
 * Redis-first via the generation-invalidated {@link CalculatorRunHistoryCacheService}.
 */
@Service
@RequiredArgsConstructor
public class RunQueryService {

    private final CalculatorRunRepository runRepository;
    private final CalculatorRunHistoryCacheService historyCache;

    /**
     * @param frequency     null = both frequencies
     * @param reportingDate null = any date (no partition pruning — served by index, see V12)
     * @param odRunId       null = no on-demand filter
     * @param nocache       true = skip the cache read and refresh the snapshot from the DB
     */
    public RunHistoryResponse getRunHistory(String calculatorName, Frequency frequency, LocalDate reportingDate,
                                            String odRunId, int limit, boolean nocache) {
        return historyCache.getOrLoad(calculatorName, frequency, reportingDate, odRunId, limit, nocache,
                () -> new RunHistoryResponse(calculatorName,
                        runRepository.findRunHistory(calculatorName, frequency, reportingDate, odRunId, limit).stream()
                                .map(RunHistoryResponse.RunEntry::from)
                                .toList()));
    }
}
```

Removed: `LIVE_TTL`, `SETTLED_TTL` (now `transient-ttl` / `terminal-ttl`), the `AnalyticsCacheService` dependency, and the unused `@Slf4j`.

### 7.5 Remove history from `cache/AnalyticsCacheService.java`

These became dead with this change (cleanup obligation):

| Remove | Note |
|---|---|
| `getRunHistory(...)`, `putRunHistory(...)` | Replaced by `CalculatorRunHistoryCacheService.getOrLoad` |
| `buildRunHistoryKey(...)` | Replaced by `snapshotKey(...)` |
| `RUN_HISTORY_PREFIX` | — |
| Imports `URLEncoder`, `StandardCharsets`, `Frequency`, `RunHistoryResponse` | Only used by the removed members |

Keep `read(...)` / `write(...)`; `getFromCache` / `putInCache` (`/executions`) still use them.

Update two comments:
- `onRunStarted`: `// A new run changes executions results — evict every analytics key.`
- `evictForCalculator`: `// Analytics caches (executions) are tracked in the calculatorName index.`

Leftover `obs:analytics:history:*` values expire within 5 min of deploy. Their dead index members are removed by the next event eviction or the index's 1 h TTL. No migration step is needed.

### 7.6 Controller: `controller/RunQueryController.java`

There is no behaviour change. Fix the stale `@Operation` description, which still says entries carry `additionalAttributes` verbatim and contradicts the D5 contract:

```java
                    "odRunId (matches additional_attributes.odRunId set by on-demand Airflow triggers). " +
                    "Each entry exposes odRunId, consRunVersion and calculatorRunVersion (null when absent). " +
                    "nocache=true bypasses the server cache read and refreshes it."
```

## 8. Observability

| Metric | Tags | Use |
|---|---|---|
| `obs.cache.history.lookup` | `outcome=hit\|miss\|corrupt\|error\|bypass` | Hit ratio = hit / (hit + miss + corrupt + error). `corrupt` > 0 after a deploy means a missed key-version bump. |
| `obs.cache.history.write` | `outcome=success\|error` | Write health |
| `obs.cache.history.invalidation` | `outcome=success\|error` | **Any `error` means staleness up to a TTL (§4.4).** Alert on > 0 over 15 min. |
| `obs.db.query.duration{query=find_run_history}` | — (existing) | DB cost per miss; the input for the miss-lock decision (§11) |
| `obs.api.analytics.duration{endpoint=/calculators/{name}/runs}` | — (existing) | End-to-end latency, hit vs miss mix |
| `executor.*{name=taskExecutor}` (Spring Boot executor metrics; confirm exported in Task 8) | — (existing) | `executor.queue.remaining` approaching 0 is the leading signal for task rejection. This plan adds a third async listener per START/COMPLETE on a bounded executor (queue 100, abort policy). Rejections themselves are not counted. |

**Invalidation lag is not measured.**
- Events are plain objects with no publish timestamp. The `event=run.start.persist` log (`RunIngestionService.java:187`) is emitted **inside** the transaction, before commit.
- Comparing it with the DEBUG `event=history_cache.invalidate` log in dev gives only a **rough development estimate**. It is neither the commit boundary nor a production figure.
- A real measurement would need a timestamp on all three events. That is not part of this plan.

**Missed invalidations are not fully observable.** `invalidation{outcome=error}` counts only Redis failures inside the listener. A pod crash after commit, or an executor rejection, loses the invalidation without any counter (§4.4). The snapshot TTL is the only bound in those cases.

## 9. Cost and sizing

Latency and memory figures below are **illustrative estimates, not measurements**. Confirm them in Task 8 and in staging.

- **Redis round trips per request:**
  - hit: 2 sequential `GET`s (generation, then snapshot);
  - miss: 2 `GET`s + 1 `SET`;
  - first request after a seed: also 1 `SET NX`.
  - Illustrative: if an in-region round trip is ~0.3–1 ms, the extra generation `GET` adds about that much per request.
  - A Lua script could fold the two hit `GET`s into one (§11).
- **Snapshot size (illustrative):** roughly 0.5 KB per run, so ~10 KB at `limit=20` and ~50 KB at `limit=100`.
- **Orphans:** each invalidation strands the calculator's live snapshots until their TTL. The worst case per calculator is (active filter combinations) × (invalidations within 5 min) × snapshot size. For example (illustrative), 5 combinations × 10 events × 10 KB ≈ 500 KB, transient and self-expiring.
- **Generation keys:** one per calculator queried or invalidated within the last `generation-ttl`, a few dozen bytes each.
- **DB load:** there is **no upper bound**, because there is no request coalescing.
  - Expected under **sequential** polling: roughly one query per distinct key per `transient-ttl` (10 s) while live, or per `terminal-ttl` (5 min) when settled, plus about one per key after each lifecycle event or generation re-seed.
  - Each of these adds queries beyond that: concurrent misses on the same key (every pod or thread that misses queries Postgres), `nocache=true` requests, Redis failures (DB-only), and snapshot eviction.
  - Request coalescing (the miss-lock) stays deferred (C5, §11).

## 10. Implementation tasks

### Task 1: Configuration and metrics constants

**Files:**
- Create: `src/main/java/com/company/observability/config/RunHistoryCacheProperties.java` (§7.2)
- Modify: `src/main/java/com/company/observability/util/ObservabilityConstants.java` (§7.1)
- Modify: `src/main/resources/application.yml` (§6 block under `observability:`)

**Done when:** `mvn -q compile` passes.

### Task 2: `CalculatorRunHistoryCacheService` with unit tests (TDD)

**Files:**
- Create: `src/test/java/com/company/observability/cache/CalculatorRunHistoryCacheServiceTest.java`
- Create: `src/main/java/com/company/observability/cache/CalculatorRunHistoryCacheService.java` (§7.3)

**Step 1: Write the failing tests.**

```java
package com.company.observability.cache;

import com.company.observability.config.RunHistoryCacheProperties;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.dto.response.RunHistoryResponse;
import com.company.observability.event.RunStartedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.company.observability.util.ObservabilityConstants.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)   // ops.get(...) is stubbed per key; other keys must return null
class CalculatorRunHistoryCacheServiceTest {

    private static final String GEN_KEY = "obs:calculator-runs:v2:g:calc-a";
    private static final String G1 = "000000000000a001";
    private static final String KEY_G1 = "obs:calculator-runs:v2:r:calc-a:" + G1 + ":all:all:20:od:none";

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> ops;

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final RunHistoryCacheProperties properties = new RunHistoryCacheProperties();
    private CalculatorRunHistoryCacheService cache;

    @BeforeEach
    void setUp() {
        cache = new CalculatorRunHistoryCacheService(redisTemplate, objectMapper, meterRegistry, properties);
        lenient().when(redisTemplate.opsForValue()).thenReturn(ops);
    }

    // ---------------------------------------------------------------- keys

    @Test
    void snapshotKey_format() {
        assertThat(CalculatorRunHistoryCacheService.snapshotKey("capitalcalc", "abc", LocalDate.of(2026, 10, 20),
                Frequency.DAILY, 1, null))
                .isEqualTo("obs:calculator-runs:v2:r:capitalcalc:abc:2026-10-20:DAILY:1:od:none");
        assertThat(CalculatorRunHistoryCacheService.snapshotKey("capitalcalc", "abc", null, null, 20, "od-123"))
                .isEqualTo("obs:calculator-runs:v2:r:capitalcalc:abc:all:all:20:od:value:od-123");
    }

    @Test
    void snapshotKey_absentOdRunId_neverCollidesWithLiteralValues() {
        Set<String> keys = Set.of(
                CalculatorRunHistoryCacheService.snapshotKey("c", "g", null, null, 20, null),
                CalculatorRunHistoryCacheService.snapshotKey("c", "g", null, null, 20, "none"),
                CalculatorRunHistoryCacheService.snapshotKey("c", "g", null, null, 20, "all"),
                CalculatorRunHistoryCacheService.snapshotKey("c", "g", null, null, 20, "od:none"));
        assertThat(keys).hasSize(4);
    }

    @Test
    void scope_encodesNameSoItCannotImpersonateSegments() {
        assertThat(CalculatorRunHistoryCacheService.scope("capitalcalc")).isEqualTo("capitalcalc");
        assertThat(CalculatorRunHistoryCacheService.scope("a:b%c d+e")).isEqualTo("a%3Ab%25c+d%2Be");
    }

    // ---------------------------------------------------------------- read path

    @Test
    void hit_returnsSnapshot_withoutLoading() throws Exception {
        when(ops.get(GEN_KEY)).thenReturn(G1);
        when(ops.get(KEY_G1)).thenReturn(objectMapper.writeValueAsString(response("SUCCESS")));

        RunHistoryResponse result = cache.getOrLoad("calc-a", null, null, null, 20, false, failingLoader());

        assertThat(result.runs()).extracting(RunHistoryResponse.RunEntry::status).containsExactly("SUCCESS");
        assertThat(count(CACHE_HISTORY_LOOKUP, "hit")).isEqualTo(1);
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void miss_allTerminal_loadsAndWritesWithTerminalTtl() {
        when(ops.get(GEN_KEY)).thenReturn(G1);
        RunHistoryResponse loaded = response("SUCCESS");

        assertThat(cache.getOrLoad("calc-a", null, null, null, 20, false, () -> loaded)).isSameAs(loaded);

        verify(ops).set(eq(KEY_G1), anyString(), eq(Duration.ofMinutes(5)));
        assertThat(count(CACHE_HISTORY_LOOKUP, "miss")).isEqualTo(1);
    }

    @Test
    void miss_emptyOrRunning_writesWithTransientTtl() {
        when(ops.get(GEN_KEY)).thenReturn(G1);

        cache.getOrLoad("calc-a", null, null, null, 20, false, () -> new RunHistoryResponse("calc-a", List.of()));
        cache.getOrLoad("calc-a", null, null, "od-1", 20, false, () -> response("RUNNING"));

        verify(ops).set(eq(KEY_G1), anyString(), eq(Duration.ofSeconds(10)));
        verify(ops).set(eq("obs:calculator-runs:v2:r:calc-a:" + G1 + ":all:all:20:od:value:od-1"),
                anyString(), eq(Duration.ofSeconds(10)));
    }

    @Test
    void missingGeneration_seedsTokenWithNx_andUsesIt() {
        when(ops.get(GEN_KEY)).thenReturn(null);
        when(ops.setIfAbsent(eq(GEN_KEY), anyString(), eq(Duration.ofMinutes(15)))).thenReturn(true);

        cache.getOrLoad("calc-a", null, null, null, 20, false, () -> response("SUCCESS"));

        verify(ops).set(matches("obs:calculator-runs:v2:r:calc-a:[0-9a-f]{16}:all:all:20:od:none"),
                anyString(), any(Duration.class));
    }

    @Test
    void lostSeedingRace_usesWinnersToken() {
        when(ops.get(GEN_KEY)).thenReturn(null, G1);
        when(ops.setIfAbsent(eq(GEN_KEY), anyString(), any(Duration.class))).thenReturn(false);

        cache.getOrLoad("calc-a", null, null, null, 20, false, () -> response("SUCCESS"));

        verify(ops).set(eq(KEY_G1), anyString(), any(Duration.class));
    }

    @Test
    void generationReplacedDuringLoad_writeStaysOnCapturedGeneration() {
        when(ops.get(GEN_KEY)).thenReturn(G1);

        cache.getOrLoad("calc-a", null, null, null, 20, false, () -> {
            cache.invalidate("calc-a", "run_started");   // commit + invalidation land mid-query
            return response("SUCCESS");
        });

        verify(ops).set(eq(GEN_KEY), argThat(token -> !token.equals(G1)), eq(Duration.ofMinutes(15)));
        verify(ops).set(eq(KEY_G1), anyString(), any(Duration.class));   // dead generation — unreachable
    }

    @Test
    void nocache_skipsSnapshotRead_butOverwritesSnapshot() {
        when(ops.get(GEN_KEY)).thenReturn(G1);

        cache.getOrLoad("calc-a", null, null, null, 20, true, () -> response("SUCCESS"));

        verify(ops, never()).get(KEY_G1);
        verify(ops).set(eq(KEY_G1), anyString(), any(Duration.class));
        assertThat(count(CACHE_HISTORY_LOOKUP, "bypass")).isEqualTo(1);
    }

    @Test
    void corruptSnapshot_isMiss_overwrittenNotDeleted() {
        when(ops.get(GEN_KEY)).thenReturn(G1);
        when(ops.get(KEY_G1)).thenReturn("{not-json");

        cache.getOrLoad("calc-a", null, null, null, 20, false, () -> response("SUCCESS"));

        verify(redisTemplate, never()).delete(anyString());
        verify(ops).set(eq(KEY_G1), anyString(), any(Duration.class));
        assertThat(count(CACHE_HISTORY_LOOKUP, "corrupt")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null",                                                                    // JSON null
            "{\"calculatorName\":\"calc-a\"}",                                          // runs missing
            "{\"calculatorName\":\"calc-a\",\"runs\":[null]}",                          // null entry (NPE in anyLive)
            "{\"calculatorName\":\"calc-a\",\"runs\":[{}]}",                            // no identity/status (would be 'settled')
            "{\"runs\":[]}",                                                             // calculator name missing
            "{\"calculatorName\":\"calc-b\",\"runs\":[]}",                              // another calculator's snapshot
            "{\"calculatorName\":\"calc-a\",\"runs\":[{\"status\":\"SUCCESS\"}]}",      // runId missing
            "{\"calculatorName\":\"calc-a\",\"runs\":[{\"runId\":\"r1\",\"status\":\"PAUSED\"}]}" // unknown status
    })
    void invalidSnapshot_isCorrupt_andLoadsFromDb(String json) {
        when(ops.get(GEN_KEY)).thenReturn(G1);
        when(ops.get(KEY_G1)).thenReturn(json);
        AtomicInteger loads = new AtomicInteger();

        RunHistoryResponse result = cache.getOrLoad("calc-a", null, null, null, 20, false,
                () -> { loads.incrementAndGet(); return response("SUCCESS"); });

        assertThat(loads).hasValue(1);
        assertThat(result.runs()).extracting(RunHistoryResponse.RunEntry::status).containsExactly("SUCCESS");
        assertThat(count(CACHE_HISTORY_LOOKUP, "corrupt")).isEqualTo(1);
        assertThat(count(CACHE_HISTORY_LOOKUP, "hit")).isZero();
        verify(ops).set(eq(KEY_G1), anyString(), any(Duration.class));   // overwritten with the DB result
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"calculatorName\":\"calc-a\",\"runs\":[]}",                                       // empty is valid
            "{\"calculatorName\":\"calc-a\",\"runs\":[{\"runId\":\"r1\",\"status\":\"RUNNING\"}]}" // display fields optional
    })
    void minimalValidSnapshot_isHit(String json) {
        when(ops.get(GEN_KEY)).thenReturn(G1);
        when(ops.get(KEY_G1)).thenReturn(json);

        cache.getOrLoad("calc-a", null, null, null, 20, false, failingLoader());

        assertThat(count(CACHE_HISTORY_LOOKUP, "hit")).isEqualTo(1);
    }

    @Test
    void generationReadFailure_servesFromDb_withoutWriting() {
        when(ops.get(GEN_KEY)).thenThrow(new RedisConnectionFailureException("down"));
        RunHistoryResponse loaded = response("SUCCESS");

        assertThat(cache.getOrLoad("calc-a", null, null, null, 20, false, () -> loaded)).isSameAs(loaded);

        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
        assertThat(count(CACHE_HISTORY_LOOKUP, "error")).isEqualTo(1);
    }

    @Test
    void snapshotReadFailure_servesFromDb() {
        when(ops.get(GEN_KEY)).thenReturn(G1);
        when(ops.get(KEY_G1)).thenThrow(new RedisConnectionFailureException("down"));
        RunHistoryResponse loaded = response("SUCCESS");

        assertThat(cache.getOrLoad("calc-a", null, null, null, 20, false, () -> loaded)).isSameAs(loaded);
        assertThat(count(CACHE_HISTORY_LOOKUP, "error")).isEqualTo(1);
    }

    @Test
    void writeFailure_stillReturnsResponse() {
        when(ops.get(GEN_KEY)).thenReturn(G1);
        doThrow(new RedisConnectionFailureException("down")).when(ops).set(eq(KEY_G1), anyString(), any(Duration.class));
        RunHistoryResponse loaded = response("SUCCESS");

        assertThat(cache.getOrLoad("calc-a", null, null, null, 20, false, () -> loaded)).isSameAs(loaded);
        assertThat(count(CACHE_HISTORY_WRITE, "error")).isEqualTo(1);
    }

    @Test
    void loaderFailure_propagates_andNothingIsWritten() {
        when(ops.get(GEN_KEY)).thenReturn(G1);

        assertThatThrownBy(() -> cache.getOrLoad("calc-a", null, null, null, 20, false, failingLoader()))
                .isInstanceOf(IllegalStateException.class);
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void disabled_loadsFromDb_withNoRedisIo() {
        properties.setEnabled(false);
        RunHistoryResponse loaded = response("SUCCESS");

        assertThat(cache.getOrLoad("calc-a", null, null, null, 20, false, () -> loaded)).isSameAs(loaded);
        verifyNoInteractions(redisTemplate);
    }

    // ---------------------------------------------------------------- invalidation

    @Test
    void onRunStarted_replacesGenerationWithFreshToken() {
        cache.onRunStarted(new RunStartedEvent(CalculatorRun.builder().calculatorName("calc-a").build()));

        verify(ops).set(eq(GEN_KEY), matches("[0-9a-f]{16}"), eq(Duration.ofMinutes(15)));
        assertThat(count(CACHE_HISTORY_INVALIDATION, "success")).isEqualTo(1);
    }

    @Test
    void invalidate_encodesScope() {
        cache.invalidate("a:b", "run_completed");
        verify(ops).set(eq("obs:calculator-runs:v2:g:a%3Ab"), anyString(), any(Duration.class));
    }

    @Test
    void invalidate_runsEvenWhenDisabled() {
        properties.setEnabled(false);
        cache.invalidate("calc-a", "run_completed");
        verify(ops).set(eq(GEN_KEY), anyString(), any(Duration.class));
    }

    @Test
    void invalidateFailure_isSwallowedAndMetered() {
        doThrow(new RedisConnectionFailureException("down")).when(ops).set(eq(GEN_KEY), anyString(), any(Duration.class));

        cache.invalidate("calc-a", "sla_breached");

        assertThat(count(CACHE_HISTORY_INVALIDATION, "error")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- fixtures

    private double count(String name, String outcome) {
        var counter = meterRegistry.find(name).tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private static java.util.function.Supplier<RunHistoryResponse> failingLoader() {
        return () -> { throw new IllegalStateException("loader must not be called"); };
    }

    private static RunHistoryResponse response(String status) {
        return new RunHistoryResponse("calc-a", List.of(new RunHistoryResponse.RunEntry(
                "r1", "id-a", "DAILY", LocalDate.of(2026, 10, 20), "1", null, null, status,
                Instant.parse("2026-10-21T05:00:00Z"), null, null, 60000L,
                Instant.parse("2026-10-21T07:00:00Z"), null, false,
                "od-1", "3", "7")));
    }
}
```

**Step 2:** Run `mvn test -Dtest=CalculatorRunHistoryCacheServiceTest`. It is expected to FAIL to compile, because the class doesn't exist yet.

**Step 3:** Implement §7.3.

**Step 4:** Run `mvn test -Dtest=CalculatorRunHistoryCacheServiceTest`. All tests pass.

**Done when:** all of the above tests are green.

### Task 3: Redis integration test (real Redis, Testcontainers)

**Files:**
- Create: `src/test/java/com/company/observability/cache/CalculatorRunHistoryCacheIntegrationTest.java`

It extends `RedisIntegrationTestBase` (`disabledWithoutDocker = true`). It constructs the service directly and calls `invalidate` directly, so it proves the §4 properties against real Redis semantics. It does **not** prove event registration or `AFTER_COMMIT` behaviour; Task 3b covers that.

```java
package com.company.observability.cache;

import com.company.observability.config.RunHistoryCacheProperties;
import com.company.observability.dto.response.RunHistoryResponse;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CalculatorRunHistoryCacheIntegrationTest extends RedisIntegrationTestBase {

    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private CalculatorRunHistoryCacheService cache;
    private final AtomicInteger loads = new AtomicInteger();

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        cache = new CalculatorRunHistoryCacheService(redis, JsonMapper.builder().findAndAddModules().build(),
                new SimpleMeterRegistry(), new RunHistoryCacheProperties());
        loads.set(0);
    }

    @AfterEach
    void tearDown() {
        factory.destroy();
    }

    @Test
    void secondRead_isServedFromCache() {
        get("SUCCESS");
        get("SUCCESS");
        assertThat(loads).hasValue(1);
    }

    @Test
    void invalidation_forcesReload() {
        get("SUCCESS");
        cache.invalidate("calc-a", "run_started");
        get("SUCCESS");
        assertThat(loads).hasValue(2);
    }

    @Test
    void readThatRacedACommit_isNeverServed() {
        // Request 1 reads the pre-START state; START commits + invalidates while its query runs.
        RunHistoryResponse stale = cache.getOrLoad("calc-a", null, null, null, 1, false, () -> {
            loads.incrementAndGet();
            cache.invalidate("calc-a", "run_started");
            return response("SUCCESS");
        });
        assertThat(stale.runs().get(0).status()).isEqualTo("SUCCESS");

        // Request 2 must not see request 1's snapshot.
        assertThat(get("RUNNING").runs().get(0).status()).isEqualTo("RUNNING");
        assertThat(loads).hasValue(2);
    }

    @Test
    void evictedGenerationKey_doesNotResurrectOldSnapshots() {
        get("SUCCESS");                                                     // snapshot under g1
        cache.invalidate("calc-a", "run_started");                          // g2
        redis.delete(CalculatorRunHistoryCacheService.generationKey("calc-a")); // eviction/expiry
        assertThat(get("RUNNING").runs().get(0).status()).isEqualTo("RUNNING"); // fresh seed ≠ g1
        assertThat(loads).hasValue(2);
    }

    @Test
    void ttl_followsSnapshotState() {
        get("RUNNING");
        String gen = redis.opsForValue().get(CalculatorRunHistoryCacheService.generationKey("calc-a"));
        String key = CalculatorRunHistoryCacheService.snapshotKey("calc-a", gen, null, null, 1, null);
        assertThat(redis.getExpire(key)).isBetween(1L, 10L);
        assertThat(redis.getExpire(CalculatorRunHistoryCacheService.generationKey("calc-a"))).isBetween(840L, 900L);

        cache.getOrLoad("calc-a", null, null, null, 1, true, () -> response("SUCCESS")); // nocache refresh
        assertThat(redis.getExpire(key)).isBetween(240L, 300L);
    }

    @Test
    void corruptSnapshot_isReplacedWithValidJson() {
        get("SUCCESS");
        String gen = redis.opsForValue().get(CalculatorRunHistoryCacheService.generationKey("calc-a"));
        String key = CalculatorRunHistoryCacheService.snapshotKey("calc-a", gen, null, null, 1, null);
        redis.opsForValue().set(key, "{not-json");

        get("SUCCESS");
        get("SUCCESS");
        assertThat(loads).hasValue(2);                                     // reloaded once, then cached again
        assertThat(redis.opsForValue().get(key)).startsWith("{\"calculatorName\"");
    }

    private RunHistoryResponse get(String status) {
        return cache.getOrLoad("calc-a", null, null, null, 1, false, () -> {
            loads.incrementAndGet();
            return response(status);
        });
    }

    private static RunHistoryResponse response(String status) {
        return new RunHistoryResponse("calc-a", List.of(new RunHistoryResponse.RunEntry(
                "r1", "id-a", "DAILY", LocalDate.of(2026, 10, 20), "1", null, null, status,
                Instant.parse("2026-10-21T05:00:00Z"), null, null, 60000L,
                Instant.parse("2026-10-21T07:00:00Z"), null, false, null, null, null)));
    }
}
```

**Done when:** green with Docker running, skipped (not failed) without Docker.

### Task 3b: Listener wiring test (Spring context, transactions, real Redis)

**Files:**
- Create: `src/test/java/com/company/observability/cache/CalculatorRunHistoryCacheListenerIntegrationTest.java`

The test proves the three listener methods are registered as `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`. For each of the three events:
- **no** invalidation task is submitted, and the generation is unchanged, before commit;
- **exactly one** async task is submitted at commit, and running it replaces the generation;
- **no** task and no change after rollback.

It also pins the silent-drop hazard from assumption 3 (a publish outside a transaction is ignored).

**Determinism.** `@Async` runs on a `ManualTaskExecutor` that only queues tasks; the test runs them explicitly with `drain()`. There are no sleeps and no Awaitility, so no assertion races a background thread. Each regression fails deterministically:

| Regression | Fails at |
|---|---|
| `@EventListener` (sync) | pre-commit: the generation is already changed |
| `@EventListener` + `@Async`, or `BEFORE_COMMIT` + `@Async` | pre-commit: `drain()` returns 1 (a task was submitted inside the transaction) |
| `@Async` removed (sync after commit) | post-commit: `drain()` returns 0 |
| Listener method or registration missing | post-commit: `drain()` returns 0 |
| `AFTER_COMPLETION` (fires on rollback too) | rollback: `drain()` returns 1 |

The context needs:
- `@EnableTransactionManagement`, which registers the `TransactionalEventListenerFactory`;
- `@EnableAsync`, which picks up the single `TaskExecutor` bean (the manual executor);
- a no-op `AbstractPlatformTransactionManager`, because the base class manages transaction synchronization itself and no DB is needed;
- the Redis container.

```java
package com.company.observability.cache;

import com.company.observability.config.RunHistoryCacheProperties;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.SlaEvaluationResult;
import com.company.observability.domain.enums.SlaBand;
import com.company.observability.event.RunCompletedEvent;
import com.company.observability.event.RunStartedEvent;
import com.company.observability.event.SlaBreachedEvent;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(CalculatorRunHistoryCacheListenerIntegrationTest.Config.class)
class CalculatorRunHistoryCacheListenerIntegrationTest extends RedisIntegrationTestBase {

    private static final String GEN_KEY = CalculatorRunHistoryCacheService.generationKey("calc-a");
    private static final String SEED = "0000000000000001";

    @Configuration
    @EnableAsync
    @EnableTransactionManagement
    static class Config {
        @Bean
        LettuceConnectionFactory redisConnectionFactory(@Value("${spring.data.redis.host}") String host,
                                                        @Value("${spring.data.redis.port}") int port) {
            return new LettuceConnectionFactory(host, port);
        }

        @Bean
        StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
            return new StringRedisTemplate(connectionFactory);
        }

        @Bean
        CalculatorRunHistoryCacheService historyCache(StringRedisTemplate redis) {
            return new CalculatorRunHistoryCacheService(redis, JsonMapper.builder().findAndAddModules().build(),
                    new SimpleMeterRegistry(), new RunHistoryCacheProperties());
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return new NoOpTransactionManager();
        }

        @Bean
        ManualTaskExecutor taskExecutor() {
            return new ManualTaskExecutor();
        }
    }

    /** Real synchronization lifecycle (begin → commit/rollback → afterCompletion) without a DB. */
    static class NoOpTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }

    /** Queues {@code @Async} tasks; the test decides when (and whether) they run. */
    static class ManualTaskExecutor implements TaskExecutor {
        private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        /** Runs every queued task on the calling thread; returns how many ran. */
        int drain() {
            int ran = 0;
            for (Runnable task; (task = tasks.poll()) != null; ran++) {
                task.run();
            }
            return ran;
        }

        void clear() {
            tasks.clear();
        }
    }

    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private StringRedisTemplate redis;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ManualTaskExecutor executor;

    @BeforeEach
    void seedGeneration() {
        executor.clear();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        redis.opsForValue().set(GEN_KEY, SEED);
    }

    static Stream<Object> events() {
        CalculatorRun run = CalculatorRun.builder().calculatorName("calc-a").build();
        return Stream.of(
                new RunStartedEvent(run),
                new RunCompletedEvent(run),
                new SlaBreachedEvent(run, new SlaEvaluationResult(SlaBand.LATE, "test")));
    }

    @ParameterizedTest
    @MethodSource("events")
    void commit_submitsOneAsyncInvalidation_onlyAfterCommit(Object event) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            publisher.publishEvent(event);
            assertThat(executor.drain()).as("tasks submitted before commit").isZero();
            assertThat(generation()).as("generation before commit").isEqualTo(SEED);
        });

        assertThat(generation()).as("listener must not run on the committing thread").isEqualTo(SEED);
        assertThat(executor.drain()).as("async tasks submitted at commit").isEqualTo(1);
        assertThat(generation()).matches("[0-9a-f]{16}").isNotEqualTo(SEED);
    }

    @ParameterizedTest
    @MethodSource("events")
    void rollback_submitsNothing_andLeavesGenerationUnchanged(Object event) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            publisher.publishEvent(event);
            status.setRollbackOnly();
        });

        assertThat(executor.drain()).isZero();
        assertThat(generation()).isEqualTo(SEED);
    }

    @Test
    void publishOutsideTransaction_isIgnored() {
        // Documents assumption 3: publish sites MUST be transactional, or invalidation silently never runs.
        publisher.publishEvent(new RunStartedEvent(CalculatorRun.builder().calculatorName("calc-a").build()));

        assertThat(executor.drain()).isZero();
        assertThat(generation()).isEqualTo(SEED);
    }

    private String generation() {
        return redis.opsForValue().get(GEN_KEY);
    }
}
```

**Done when:**
- the test is green with Docker running, and skipped (not failed) without Docker;
- each regression in the table above has been checked once by hand: apply it, see the named assertion fail, then revert.

### Task 4: Switch `RunQueryService` to the new cache

**Files:**
- Modify: `src/main/java/com/company/observability/service/RunQueryService.java` (§7.4)
- Modify: `src/test/java/com/company/observability/service/RunQueryServiceTest.java`

**Test changes:**
- Replace `@Mock AnalyticsCacheService cacheService` with `@Mock CalculatorRunHistoryCacheService historyCache`.
- Make the mock invoke the loader, so the mapping tests stay meaningful:

  ```java
  @BeforeEach
  void passThroughCache() {
      lenient().when(historyCache.getOrLoad(any(), any(), any(), any(), anyInt(), anyBoolean(), any()))
              .thenAnswer(inv -> inv.<Supplier<RunHistoryResponse>>getArgument(6).get());
  }
  ```
- Keep `cacheMiss_readsDb_mapsOnDemandFields…` (rename to `mapsOnDemandFields_andExposesNoOtherAttribute`) and `scheduledRun_withoutAttributes_mapsOnDemandFieldsToNull`. Drop their `putRunHistory` verifications.
- Delete `cacheHit_returnsCachedWithoutDbRead`, `runningOrEmptyResult_cachedWithLiveTtl` and `nocache_skipsCacheReadButRefreshesCache`; Task 2 covers them.
- Add `passesFiltersAndNocacheThrough`:

  ```java
  verify(historyCache).getOrLoad(eq("calc-a"), eq(Frequency.MONTHLY), eq(DATE), eq("od-1"), eq(5), eq(true), any());
  ```

**Done when:** `mvn test "-Dtest=RunQueryServiceTest,RunQueryControllerTest"` is green (quotes required in PowerShell). The controller tests mock `RunQueryService` and need no change.

### Task 5: Remove history from `AnalyticsCacheService`

**Files:**
- Modify: `src/main/java/com/company/observability/cache/AnalyticsCacheService.java` (§7.5)
- Modify: `src/test/java/com/company/observability/cache/AnalyticsCacheServiceTest.java`

**Test changes:**
- Delete `putRunHistory_writesKeyWithTtl_andTracksInNameIndex`, `runHistoryKey_absentOdRunId_differsFromLiteralAll`, `runHistoryKey_encodesCalculatorName` and `getRunHistory_roundTripsOnDemandFields`. Task 2 replaces them.
- In the `onRunStarted` eviction test, drop the `obs:analytics:history:…` member from the index fixture and from the expected `delete` list, and change its comment to "a new run changes executions results".

**Done when:**
- `grep -rn "RunHistory\|history" src/main/java/com/company/observability/cache/AnalyticsCacheService.java` returns nothing;
- `mvn test -Dtest=AnalyticsCacheServiceTest` is green.

### Task 6: Controller description

**Files:** Modify `src/main/java/com/company/observability/controller/RunQueryController.java` (§7.6). Text only.

### Task 7: Documentation

| File | Change |
|---|---|
| `CLAUDE.md` → *Redis Caching* table | Replace the `obs:analytics:history:…` row with the two §5 rows. Change the `obs:analytics:index:{name}` row to "Tracks `executions` keys". |
| `CLAUDE.md` → *Event Flow* listener table | `AnalyticsCacheService`: "Invalidates `executions` keys". Add a row: `CalculatorRunHistoryCacheService` — all three events — "Replaces the calculator's history generation token (`obs:calculator-runs:v2:g:{name}`)". |
| `CLAUDE.md` → API table, `/runs` row | Append: "Cache: generation-invalidated, `observability.run-history-cache.*`". |
| `docs/plans/2026-10-21-run-history-api-design.md` | At the top of §6, add: "**Superseded by [2026-10-21-run-history-cache-plan.md](2026-10-21-run-history-cache-plan.md)**; kept for history." Add a §13 review-log entry. |
| `docs/plans/run-history-api-epic.md` | Re-scope the cache issue to this plan, with acceptance criteria = §10 Task 8. |
| `tasks/todo.md` | Plan checklist (Tasks 1–8, including 3b) and the review section on completion. |

`docs/consumer-api.md` doesn't change, because the API contract is unchanged.

### Task 8: Verification

1. Run `mvn clean test`. The expected baseline before this work is 487 tests run, 2 pre-existing `CalculatorRunRepositoryDimensionalTest` environment errors, and no new failures.
2. With Docker running, `docker compose up -d` and then `SPRING_PROFILES_ACTIVE=local mvn clean test` should leave **0 skipped** in `CalculatorRunHistoryCacheIntegrationTest`, `CalculatorRunHistoryCacheListenerIntegrationTest` and `CalculatorRunRepositoryJdbcTest`.
3. Manual end-to-end check (local profile, Git Bash).

   The script uses a **fresh calculator per execution**. No older run with a later reporting date (e.g. a MONTHLY end-of-month row on an existing calculator) can outrank the new run in `?limit=1`, and reruns don't interfere with each other.

   The `?limit=1` snapshot is primed from a **completed** run, so it carries the 5 min terminal TTL. A fresh result seconds later can therefore only come from invalidation, not expiry. The `?odRunId=` prime is empty (10 s TTL), so that check proves invalidation only because the script finishes well inside 10 s; Tasks 3 and 3b are the authoritative proof.

   Invalidation is asynchronous. The script waits, with a 5 s bound, for each generation change before it asserts anything.

   ```bash
   BASE=localhost:8080/api/v1; AUTH=admin:admin; TS=$(date +%s)
   CALC=cache-e2e-$TS; OD=od-e2e-$TS
   D1=$(date -u -d '2 days ago' +%F); D2=$(date -u -d '1 day ago' +%F)   # D2 > D1 → new run sorts first
   R="docker exec observability-redis redis-cli"
   now()  { date -u +%Y-%m-%dT%H:%M:%SZ; }
   gen()  { $R GET "obs:calculator-runs:v2:g:$CALC"; }
   wait_gen_change() {                       # $1 = previous token ("" if none); bounded 5 s
     for _ in $(seq 1 50); do [ "$(gen)" != "$1" ] && return 0; sleep 0.1; done
     echo "FAIL: generation still '$1' after 5 s"; return 1
   }
   post() { curl -fsS -u $AUTH -H 'X-Tenant-Id: t1' -H 'Content-Type: application/json' -X POST "$BASE/$1" -d "$2" >/dev/null; }
   get()  { curl -fsS -u $AUTH -H 'X-Tenant-Id: t1' "$BASE/calculators/$CALC/runs?$1"; }
   start_body() { echo "{\"runId\":\"$1\",\"calculatorId\":\"id-$CALC\",\"calculatorName\":\"$CALC\",\"frequency\":\"DAILY\",\"reportingDate\":\"$2\",\"startTime\":\"$(now)\"$3}"; }

   # 1. settled history: one completed run on D1 (each lifecycle event is waited out before priming)
   post runs/start "$(start_body r1-$TS $D1)";                          wait_gen_change ""
   OLD=$(gen); post runs/r1-$TS/complete "{\"reportingDate\":\"$D1\",\"endTime\":\"$(now)\"}"; wait_gen_change "$OLD"

   # 2. prime two filter combinations under the current generation
   get "limit=1" | grep -q "\"runId\":\"r1-$TS\"" && echo "PRIME limit=1 ok"
   get "odRunId=$OD" | grep -q '"runs":\[\]'     && echo "PRIME odRunId ok (empty)"
   $R --scan --pattern "obs:calculator-runs:v2:r:$CALC:$(gen):*"        # → exactly 2 keys
   $R TTL "obs:calculator-runs:v2:r:$CALC:$(gen):all:all:1:od:none"     # → ~300 (terminal TTL)

   # 3. new on-demand run on D2; wait for the async invalidation
   OLD=$(gen)
   post runs/start "$(start_body r2-$TS $D2 ",\"additionalAttributes\":{\"odRunId\":\"$OD\"}")"
   wait_gen_change "$OLD" && echo "INVALIDATED $OLD → $(gen)"

   # 4. both variants are fresh immediately — the limit=1 prime still had ~5 min to live
   get "limit=1"     | grep -q "\"runId\":\"r2-$TS\".*\"status\":\"RUNNING\"" && echo "PASS limit=1"     || echo "FAIL limit=1"
   get "odRunId=$OD" | grep -q "\"runId\":\"r2-$TS\""                         && echo "PASS odRunId"      || echo "FAIL odRunId"

   # 5. metrics (cache + async executor saturation signal, §8)
   curl -s localhost:8080/actuator/prometheus | grep -E 'obs_cache_history|executor_queue_remaining'
   ```

   The `RunEntry` JSON fields serialize in declaration order, so `runId` precedes `status`.
4. Kill-switch check: restart with `observability.run-history-cache.enabled=false` and rerun the script.
   - Every `wait_gen_change` still succeeds, because invalidation runs while the cache is disabled (C8).
   - The step 2 scan returns **0** keys, and the `TTL` line returns `-2`.
   - Both `PASS` lines still print, now served from the DB.

**Done when:**
- every step above behaves as described;
- the results are recorded in the `tasks/todo.md` review section.

## 11. Deferred (with triggers)

| Item | Add when | Sketch |
|---|---|---|
| Miss-lock (stampede guard) | Sampled diagnostics show bursts of concurrent misses on the **same** key, **and** `find_run_history` p95 is a material share of endpoint latency. `lookup{outcome=miss}` cannot show this: it has no key dimension. Don't add key tags (high cardinality). Use a temporary sampled DEBUG miss log carrying the key, in dev or staging. | `SET {snapshotKey}:lock 1 NX PX 2000`. The holder loads and writes. A non-holder sleeps ≤ 100 ms, re-reads once, then loads from the DB itself. No explicit unlock; the lock expires. All failures fall through to the DB. |
| Single-RTT hit | Redis round-trip time becomes a material share of hit latency. | Lua: `local g = GET gen; if not g then return nil end; return {g, GET(prefix..g..suffix)}`. |
| Alias scope | The history endpoint starts expanding UI aliases. | Also invalidate `scope(alias)` via `CalculatorNameResolver.findAliasFor`, as `AnalyticsCacheService` does. |
| `/executions` on generations | The index-set race or cardinality is observed there. | Same pattern; separate plan. |
| Long historical TTL for settled past dates | The §6 review signs off on mutation coverage, missed-invalidation behaviour and the acceptable worst-case staleness. Error-free metrics alone are not sufficient (§8). | A third TTL tier, keyed on `reportingDate < today − settle window`. |
| Tenant-scoped keys | Run history becomes tenant-filtered in SQL. | Scope = `enc(tenantId) + ':' + enc(name)`. Invalidation must then target the run's tenant scope, and readers must not be able to omit the tenant. |

## 12. Rollout and rollback

- **Deploy:** there is no data migration. New pods use `obs:calculator-runs:v2:*`. Old `obs:analytics:history:*` values expire within 5 min.
  - During the rollout window, a mutation handled by an old pod doesn't replace the generation. A new-pod snapshot can then be stale for up to its TTL (§4.4).
  - Accept this, or for a strict window, deploy with `enabled: false` and flip it to `true` once all pods run the new version (that flip needs a restart).
- **Kill switch:** `observability.run-history-cache.enabled=false` makes reads DB-only. The DB cost per request is one index-served query (design doc §5).
- **Rollback:** the previous version ignores `v2` keys, which expire within 15 min (generation) or 5 min (snapshots).

## 13. Review log

**2026-10-21: design review of v2.** Confirmed as sound: name-only scope, random generations, writing under the captured generation (no re-check), no miss-lock, `nocache` = refresh, invalidation while disabled, `RunHistoryResponse` reuse, and keeping `/executions` separate. Findings, all accepted:

| # | Finding | Resolution |
|---|---|---|
| P1 | Only `runs != null` was checked, so `[null]` (NPE in the controller), `[{}]` (treated as settled) and a missing calculator name escaped as hits. Reproduced with Jackson and the real DTO. | `isValid` (§7.3): calculator name matches, entries non-null, `runId` present, `status` is a known `RunStatus`; failures count as `corrupt` and load from the DB. Parameterized tests for the invalid and minimal-valid cases (Task 2). |
| P2 | The freshness promise was too strong: an in-flight request can return the previous generation's snapshot after invalidation. | Goal, §4.2, §4.4 and the class Javadoc narrowed to requests that resolve the generation after invalidation. |
| P2 | Listener registration and `AFTER_COMMIT` were untested; assumption 3 said events are published after commit. | Task 3b (Spring + transaction + Redis test for all three events: commit, rollback, outside a transaction). Assumption 3 corrected: published inside the transaction, handled after commit. |
| P2 | §9 claimed an upper bound on DB queries. | Replaced with the expected rate under sequential polling, plus the sources of extra queries. No bound without coalescing; the miss-lock stays deferred. |
| P2 | §8 claimed lag was measured and "expected milliseconds"; the comparison log is pre-commit and the metric is a counter. | Reworded as a rough dev estimate only. Added the missed-invalidation paths (pod crash, executor rejection) that no counter sees, and the executor saturation signal. |
| minor | Surefire `A+B` syntax; "idle" generation TTL; unmeasured estimates; stampede trigger not observable from metric tags. | Comma syntax with PowerShell quoting; TTL described as lifetime since the last invalidation or seed; estimates labelled illustrative; trigger uses sampled diagnostics. |

**2026-10-21: second review.** All three findings were checked against the plan and accepted. The resolutions differ in places:

| Finding | Assessment | Resolution |
|---|---|---|
| The long-TTL trigger (§6, §11) relied on zero invalidation errors, contradicting §8. | Genuine. The first revision updated §8 but left the two triggers behind. | Replaced the trigger with a review of mutation coverage, missed-event behaviour and acceptable staleness. Initial TTLs unchanged. |
| The Task 3b regression check was racy. | Genuine for `@EventListener` + `@Async` and `BEFORE_COMMIT` + `@Async`: the submitted task usually finishes after the pre-commit assertion, so the test passes. Plain `@EventListener` already failed deterministically. | `ManualTaskExecutor` with explicit `drain()`. Every assertion is deterministic, with no sleeps or Awaitility. The test also now pins `@Async` (exactly one task at commit) and catches `AFTER_COMPLETION`, which also fires on rollback. |
| Task 8 asserted before the async invalidation, and the new run might not be first in `limit=1`. | Genuine on both counts. The ordering risk is concrete: `capitalcalc`'s MONTHLY end-of-month rows (e.g. `2026-10-31`) outrank a DAILY run dated today. **Additional issue found:** the old step primed with possibly empty or RUNNING snapshots (10 s TTL), so a slow manual check couldn't tell invalidation from expiry. | Bounded `wait_gen_change` before every assertion. A fresh calculator per execution with `D2 > D1`. The `limit=1` prime comes from a completed run (5 min TTL), so freshness can only come from invalidation. The limits of the `odRunId` check are stated. |
