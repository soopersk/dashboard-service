# TTL Enhancements — CalculatorStateCacheService

## Context

Critical review of the `/batch/runs` caching layer identified three gaps in `determineTtl`:

1. The 3-day stability horizon is too short — any DAILY run older than 3 days gets the long TTL, but re-triggers within the week are plausible. User wants 7 days.
2. MONTHLY reporting_date is always end-of-month, so it passes the 3-day gate immediately (a May 31 run is "stable" by June 3). MONTHLY re-triggers (reconciliations, corrections) happen throughout the following month — needs its own calendar-aware logic.
3. TTL_TERMINAL_CLEAN at 4h means eviction failures leave stale data for up to 4h. Reducing to 1h cuts the blast radius without meaningful DB cost.
4. Eviction failures are invisible: the catch block logs a warn but emits no metric, and the count of keys actually deleted is ignored.

---

## Design Decisions

### 1. DAILY stability horizon: 3 → 7 days

The DAILY query window is `CURRENT_DATE - 3 days`. Anything older is explicit historical access. 7 days ensures a full working week has passed before a DAILY run snapshot is treated as a stable archive.

### 2. MONTHLY stability: `YearMonth` calendar boundary (not a raw day count)

A raw day count (e.g. 30 days) is calendar-ambiguous — April 30 would become stable on May 30 (one day before the month ends). The business-correct invariant is:

> *A MONTHLY run is potentially unstable for the entire calendar month following its reporting month. It is a stable archive once it is 2+ full calendar months in the past.*

Implementation: `YearMonth.from(reportingDate).isBefore(YearMonth.now().minusMonths(1))`

| Today | Reporting month | `isBefore(May 2026)`? | TTL |
|---|---|---|---|
| June 15 | May 2026 (May 31 run) | false | 5 min — still active cycle |
| June 15 | April 2026 (Apr 30 run) | true | 1h — stable archive |
| June 15 | Jan 2026 (Jan 31 run) | true | 1h — stable archive |

May MONTHLY runs stay at 5-min TTL all of June, becoming stable in July. This matches real re-trigger windows for month-end reconciliation.

### 3. TTL_TERMINAL_CLEAN: 4h → 1h

| | 4h (current) | 1h (proposed) |
|---|---|---|
| Stale exposure on eviction failure | Up to 4h | Up to 1h — 4× reduction |
| Extra DB reads/day per cached historical entry | 6 | 24 |

The 18 extra reads/day per calculator on historical data are negligible (partition-pruned, indexed). The 4× staleness reduction is the meaningful gain. 1h still far outperforms the 5-min fallback for archive data.

### 4. Eviction: deleted count + failure counter + trigger context

`redisTemplate.delete(List<String>)` returns `Long` (keys actually removed). Currently ignored. Capturing it distinguishes:
- `deleted=0/2` — keys not cached (no-op, normal)
- `deleted=1/2` — partial eviction (one key was cached)
- `deleted=2/2` — full eviction (both `:all` and `:{runNumber}` removed)

A `CACHE_STATE_EVICTION_FAILURE` Micrometer counter makes Redis failures visible to dashboards and alerting. Without it, swallowed exceptions are invisible.

The invalidation listener currently has no logging. Adding one debug line per handler creates a traceable log chain:
```
event=state.cache.invalidate trigger=run_started calculator=X runNumber=2
event=state.cache.evict outcome=success keys=[...] deleted=2/2
```

---

## Implementation

### A — `ObservabilityConstants.java`

Add alongside `CACHE_STATE_EVICTION`:
```java
public static final String CACHE_STATE_EVICTION_FAILURE = "obs.cache.state.eviction.failure";
```

---

### B — `CalculatorStateCacheService.java` (5 targeted changes)

**B1. Reduce `TTL_TERMINAL_CLEAN`**
```java
// Before:
static final Duration TTL_TERMINAL_CLEAN = Duration.ofHours(4);
// After:
static final Duration TTL_TERMINAL_CLEAN = Duration.ofHours(1);
```

**B2. Add stability-horizon constants** (after the TTL declarations):
```java
private static final int DAILY_STABILITY_DAYS     = 7;
private static final int MONTHLY_STABILITY_MONTHS = 1;
```

**B3. Add `String frequency` to `determineTtl` signature**
```java
// Before:
Duration determineTtl(CalculatorEntry entry, LocalDate reportingDate)
// After:
Duration determineTtl(CalculatorEntry entry, LocalDate reportingDate, String frequency)
```

**B4. Replace the final return in `determineTtl`** with frequency-aware logic:
```java
// Before (current lines 180-182):
return reportingDate.isBefore(LocalDate.now().minusDays(3))
        ? TTL_TERMINAL_CLEAN
        : TTL_TERMINAL_WITH_FAILURES;

// After:
if ("MONTHLY".equals(frequency)) {
    YearMonth reportingMonth = YearMonth.from(reportingDate);
    return reportingMonth.isBefore(YearMonth.now().minusMonths(MONTHLY_STABILITY_MONTHS))
            ? TTL_TERMINAL_CLEAN
            : TTL_TERMINAL_WITH_FAILURES;
}
return reportingDate.isBefore(LocalDate.now().minusDays(DAILY_STABILITY_DAYS))
        ? TTL_TERMINAL_CLEAN
        : TTL_TERMINAL_WITH_FAILURES;
```

Add `import java.time.YearMonth;` to the imports.

**B5. Pass `frequency` in `putEntries` and improve `evictEntry`**

In `putEntries` forEach:
```java
// Before:
Duration ttl = determineTtl(entry, reportingDate);
// After:
Duration ttl = determineTtl(entry, reportingDate, frequency);
```

Replace the full `evictEntry` try/catch:
```java
try {
    Long deleted = redisTemplate.delete(keys);
    meterRegistry.counter(CACHE_STATE_EVICTION, "calculator", calculatorName).increment();
    log.debug("event=state.cache.evict outcome=success keys={} deleted={}/{}", keys, deleted, keys.size());
} catch (Exception e) {
    meterRegistry.counter(CACHE_STATE_EVICTION_FAILURE, "calculator", calculatorName).increment();
    log.warn("event=state.cache.evict outcome=failure keys={} error={}", keys, e.getMessage());
}
```

---

### C — `CalculatorStateCacheInvalidationListener.java`

Add one debug log line in each handler before `evict(run)`:

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Async
public void onRunStarted(RunStartedEvent event) {
    CalculatorRun run = event.getRun();
    log.debug("event=state.cache.invalidate trigger=run_started calculator={} runNumber={} reportingDate={}",
              run.getCalculatorName(), run.getRunNumber(), run.getReportingDate());
    evict(run);
}

@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Async
public void onRunCompleted(RunCompletedEvent event) {
    CalculatorRun run = event.getRun();
    log.debug("event=state.cache.invalidate trigger=run_completed calculator={} runNumber={} reportingDate={}",
              run.getCalculatorName(), run.getRunNumber(), run.getReportingDate());
    evict(run);
}

@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Async
public void onSlaBreached(SlaBreachedEvent event) {
    CalculatorRun run = event.getRun();
    log.debug("event=state.cache.invalidate trigger=sla_breached calculator={} runNumber={} reportingDate={}",
              run.getCalculatorName(), run.getRunNumber(), run.getReportingDate());
    evict(run);
}
```

---

### D — `CalculatorStateCacheServiceTest.java`

**D1.** Add `"DAILY"` as the third argument to all existing `determineTtl(entry, date)` calls — 8 tests affected.

**D2.** Rename `determineTtl_cleanSuccessOnOldDate_returns4h` → `_returns1h`; the assertion uses the `TTL_TERMINAL_CLEAN` constant so no value change in the assertion code itself.

Ensure `DATE = LocalDate.of(2026, 5, 1)` is still > 7 days old (it is — 45 days). No date change needed.

**D3.** Add MONTHLY-specific TTL tests:
```java
@Test
void determineTtl_cleanSuccessMonthly_lastMonth_returns5min() {
    LocalDate lastMonthEnd = YearMonth.now().minusMonths(1).atEndOfMonth();
    CalculatorEntry entry = new CalculatorEntry("calc", null, List.of(runEntry("SUCCESS", null)));
    assertThat(service.determineTtl(entry, lastMonthEnd, "MONTHLY"))
            .isEqualTo(TTL_TERMINAL_WITH_FAILURES);
}

@Test
void determineTtl_cleanSuccessMonthly_twoMonthsAgo_returns1h() {
    LocalDate twoMonthsAgoEnd = YearMonth.now().minusMonths(2).atEndOfMonth();
    CalculatorEntry entry = new CalculatorEntry("calc", null, List.of(runEntry("SUCCESS", null)));
    assertThat(service.determineTtl(entry, twoMonthsAgoEnd, "MONTHLY"))
            .isEqualTo(TTL_TERMINAL_CLEAN);
}
```

**D4.** Update eviction tests — stub the delete return value and add a failure counter test:
```java
@Test
void evictEntry_withRunNumber_deletesBothKeys() {
    when(redisTemplate.delete(anyList())).thenReturn(2L);
    service.evictEntry("cap", DATE, FREQ, "1");
    verify(redisTemplate).delete(List.of(
            "obs:state:cap:" + DATE + ":DAILY:all",
            "obs:state:cap:" + DATE + ":DAILY:1"));
}

@Test
void evictEntry_redisFailure_incrementsFailureCounter() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    service = new CalculatorStateCacheService(redisTemplate, objectMapper, registry);
    doThrow(new RuntimeException("Redis down")).when(redisTemplate).delete(anyList());

    service.evictEntry("cap", DATE, FREQ, "1");

    assertThat(registry.counter("obs.cache.state.eviction.failure",
            "calculator", "cap").count()).isEqualTo(1.0);
}
```

---

## TTL Behaviour After Changes

| Condition | Before | After |
|---|---|---|
| Any RUNNING | 30s | 30s |
| Null status / NOT_STARTED | 60s | 60s |
| Any FAILED/TIMEOUT/CANCELLED/SLA breach | 5 min | 5 min |
| All SUCCESS, DAILY, ≤7 days old | 5 min | 5 min (was ≤3 days) |
| All SUCCESS, DAILY, >7 days old | **4h** | **1h** |
| All SUCCESS, MONTHLY, last calendar month | **4h** | **5 min** ← key fix |
| All SUCCESS, MONTHLY, 2+ calendar months ago | **4h** | **1h** |

---

## Files Changed

| File | Change |
|---|---|
| `src/main/java/com/company/observability/util/ObservabilityConstants.java` | Add `CACHE_STATE_EVICTION_FAILURE` |
| `src/main/java/com/company/observability/cache/CalculatorStateCacheService.java` | TTL 4h→1h; stability constants; frequency-aware `determineTtl`; `evictEntry` deleted count + failure counter |
| `src/main/java/com/company/observability/cache/CalculatorStateCacheInvalidationListener.java` | Trigger-context debug log in each handler |
| `src/test/java/com/company/observability/cache/CalculatorStateCacheServiceTest.java` | Update all `determineTtl` calls; add MONTHLY tests; update eviction tests |

---

## Verification

```bash
mvn test -Dtest=CalculatorStateCacheServiceTest
SPRING_PROFILES_ACTIVE=local mvn clean test
```

Expected log trace for a RunStartedEvent-triggered eviction:
```
DEBUG event=state.cache.invalidate trigger=run_started calculator=capitalcalcdev runNumber=2 reportingDate=2026-05-31
DEBUG event=state.cache.evict outcome=success keys=[obs:state:capitalcalcdev:2026-05-31:MONTHLY:all, obs:state:capitalcalcdev:2026-05-31:MONTHLY:2] deleted=2/2
```

---

# Cache Bypass Flag — /batch/runs and /executions

## Context

Both query endpoints use a cache-aside pattern. There is no way to force a fresh DB read without waiting for TTL expiry or an invalidation event. This plan adds `?nocache=true` as an opt-in query parameter to bypass the cache read and fetch directly from the DB — useful for debugging stale data, verifying re-trigger outcomes, and operational spot-checks.

**Chosen approach:** `?nocache=true` query parameter (default absent = `false` = normal cached behaviour).

- Immediately visible in Swagger UI — auto-documented with default value
- Appears in access logs (URL-based) — auditable without special log parsing
- Testable from browser address bar, curl, Swagger "Try it out" without any header setup
- No risk of proxy/gateway stripping
- Fully backward-compatible — existing callers unaffected

---

## How the Cache-Aside Pattern Works in Both Services

### `CalculatorStateService.getState()` (line 56)
```java
Map<String, CalculatorEntry> cached = stateCache.getEntries(reportingDate, freqName, rn, calculatorNames);
List<String> missNames = calculatorNames.stream()
        .filter(name -> !cached.containsKey(name)).toList();
// missNames → DB fetch → putEntries → merge
```
When `cached` is empty, every name becomes a miss → full DB fetch → `putEntries` writes fresh data.
**Change: one ternary on the cache-read line.**

### `AnalyticsService.getRunExecutionsByName()` (line 47)
```java
RunPerformanceData cached = cacheService.getFromCache(...);
if (cached != null) return cached;
// DB fetch → putInCache → return
```
When `cached` is null, falls through to DB.
**Change: one ternary on the cache-read line.**

**Behaviour when `nocache=true`:**
- Cache read is **skipped** — goes directly to DB for all entries
- Fresh result is **written back to cache** — refreshes the cache, not just a pass-through
- No explicit pre-eviction needed: `SET key json ttl` is an atomic overwrite

---

## Implementation

### Change 1 — `RunQueryController.java`

Add `nocache` as a query parameter and pass it to the service:

```java
// Add to method signature:
@RequestParam(value = "nocache", defaultValue = "false") boolean nocache,

// Update service call (existing line 89):
Map<String, CalculatorEntry> byRealName =
        calculatorStateService.getState(reportingDate, freq, runNumber, allRealNames, nocache);
```

---

### Change 2 — `CalculatorStateService.java`

Add `boolean nocache` to `getState()` and change one line:

```java
public Map<String, CalculatorEntry> getState(
        LocalDate reportingDate, Frequency frequency, String runNumber,
        List<String> calculatorNames, boolean nocache)
```

Replace the cache read (line 56):
```java
// Before:
Map<String, CalculatorEntry> cached =
        stateCache.getEntries(reportingDate, freqName, rn, calculatorNames);

// After:
Map<String, CalculatorEntry> cached = nocache
        ? Collections.emptyMap()
        : stateCache.getEntries(reportingDate, freqName, rn, calculatorNames);
```

Add a debug log immediately after:
```java
if (nocache) {
    log.debug("event=state.cache.bypass calculators={} reportingDate={} frequency={}",
              calculatorNames, reportingDate, frequency);
}
```

Everything else (miss detection, DB fetch, `putEntries`) runs unchanged.

---

### Change 3 — `AnalyticsController.java`

Same query parameter as Change 1:

```java
// Add to method signature:
@RequestParam(value = "nocache", defaultValue = "false") boolean nocache,

// Update service call (existing line 66):
RunPerformanceData response = analyticsService
        .getRunExecutionsByName(calculatorName, days, freq, runNumber, effectiveAsOfDate, nocache);
```

---

### Change 4 — `AnalyticsService.java`

Add `boolean nocache` to `getRunExecutionsByName()` and change one line:

```java
public RunPerformanceData getRunExecutionsByName(
        String calculatorName, int days, Frequency frequency, String runNumber,
        LocalDate asOfDate, boolean nocache)
```

Replace the cache read (line 47):
```java
// Before:
RunPerformanceData cached = cacheService.getFromCache(
        CACHE_EXECUTIONS, calculatorName, frequency.name(), days, rn,
        asOfDate, RunPerformanceData.class);

// After:
RunPerformanceData cached = nocache ? null
        : cacheService.getFromCache(
                CACHE_EXECUTIONS, calculatorName, frequency.name(), days, rn,
                asOfDate, RunPerformanceData.class);
```

Add a debug log at the top of the method:
```java
if (nocache) {
    log.debug("event=analytics.cache.bypass calculatorName={} frequency={} days={}",
              calculatorName, frequency, days);
}
```

---

## Files Changed

| File | Change |
|---|---|
| `src/main/java/com/company/observability/controller/RunQueryController.java` | Add `nocache` `@RequestParam`; pass to service |
| `src/main/java/com/company/observability/service/CalculatorStateService.java` | Add `nocache` param; ternary on cache read; debug log |
| `src/main/java/com/company/observability/controller/AnalyticsController.java` | Add `nocache` `@RequestParam`; pass to service |
| `src/main/java/com/company/observability/service/AnalyticsService.java` | Add `nocache` param; ternary on cache read; debug log |

4 files. Each change is 1–3 lines. No new classes, constants, or DTOs.

---

## Usage

```bash
# Force fresh DB read for batch/runs
curl -u admin:admin \
     "http://localhost:8080/api/v1/calculators/batch/runs?keys=capitalcalcdev&frequency=MONTHLY&reporting_date=2026-05-31&run_number=2&nocache=true"

# Force fresh DB read for executions
curl -u admin:admin \
     "http://localhost:8080/api/v1/analytics/calculators/capitalcalcdev/executions?frequency=MONTHLY&nocache=true"

# Normal request — cache active (nocache absent or false)
curl -u admin:admin \
     "http://localhost:8080/api/v1/calculators/batch/runs?keys=capitalcalcdev&frequency=MONTHLY&reporting_date=2026-05-31"
```

Swagger UI: the `nocache` parameter appears in the parameter list with default `false` — testable via "Try it out" with no extra setup.

---

## Tests to Add

```java
// CalculatorStateService
@Test
void getState_nocache_skipsCacheAndFetchesAllFromDb() {
    // pass nocache=true → stateCache.getEntries() must not be called
    // all calculatorNames must go to DB
}

// AnalyticsService
@Test
void getRunExecutionsByName_nocache_skipsCacheAndFetchesFromDb() {
    // pass nocache=true → cacheService.getFromCache() must not be called
}
```

---

## Verification

```bash
# 1. Populate cache
curl -u admin:admin "http://localhost:8080/api/v1/calculators/batch/runs?keys=capitalcalcdev&..."

# 2. Bypass — log should show: event=state.cache.bypass
curl -u admin:admin "http://localhost:8080/api/v1/calculators/batch/runs?keys=capitalcalcdev&...&nocache=true"

# 3. Normal request — hits refreshed cache
curl -u admin:admin "http://localhost:8080/api/v1/calculators/batch/runs?keys=capitalcalcdev&..."

# Monitor Redis writes during step 2
docker exec -it observability-redis redis-cli MONITOR | grep "obs:state"
```
