# `/batch/runs` NOT_STARTED SLA — Reference-Time Testing Support

## Scope
Adding local/dev testability of SLA breach states for NOT_STARTED synthetic entries.
Prior work (estEnd fallback fix + logging) is already implemented.

---

## Context

`evaluateSlaStatus()` in [ExpectedRunsService.java](src/main/java/com/company/observability/service/ExpectedRunsService.java) compares `Instant.now()` against the projected SLA deadline. This is correct for production but makes local/dev testing of breach states impractical:

- Querying `reporting_date=2026-05-15` always produces `VERY_LATE` because the projected SLA (`2026-05-18T16:15:00Z`) is a month in the past relative to today (2026-06-14).
- There is no way to test the ON_TIME → LATE → VERY_LATE transition at the API level without data flowing in real time.
- Existing unit tests in `ExpectedRunsServiceTest` use `Instant.now() ± duration` offsets, making them non-deterministic and potentially flaky.

---

## Design — Request-Scoped `Clock` Bean

Inject a `java.time.Clock` into both services that call `evaluateSlaStatus`. In local/dev, a request-scoped `Clock` bean reads an optional `as_of` query parameter from the HTTP request and returns `Clock.fixed(asOf, UTC)` when provided; otherwise falls back to `Clock.systemUTC()`. No controller or business logic changes are needed — the `Clock` bean reads the parameter directly from `HttpServletRequest`.

**Why this approach:**
- **No parameter threading**: `as_of` is extracted inside the `Clock` bean config, invisible to business methods.
- **No state mutation**: Spring's request scope creates a fresh `Clock` instance per HTTP request (via CGLIB proxy in singletons).
- **Production-safe**: guarded by `observability.sla.allow-reference-time: false` (default). The `as_of` parameter is silently ignored when the flag is false.
- **Unit test benefit**: the new 3-arg `evaluateSlaStatus(deadline, bandGap, now)` overload lets unit tests pass a deterministic `Instant`, fixing the flaky `Instant.now() ± seconds` pattern in `ExpectedRunsServiceTest`.
- **Background job safe**: no scheduled job calls `evaluateSlaStatus`, so the request-scoped proxy is never accessed outside an HTTP context.

### Example usage after this change

```
# ON_TIME — queried before deadline
GET /api/v1/calculators/batch/runs?reporting_date=2026-05-15&frequency=DAILY&run_number=1&keys=capital&as_of=2026-05-18T15:00:00Z

# LATE — 5 min past deadline (within the 15-min late band)
GET /api/v1/calculators/batch/runs?...&as_of=2026-05-18T16:20:00Z

# VERY_LATE — 35 min past deadline
GET /api/v1/calculators/batch/runs?...&as_of=2026-05-18T16:50:00Z

# No as_of → uses Instant.now() as always
GET /api/v1/calculators/batch/runs?reporting_date=2026-05-15&...
```

---

## Implementation

### 1. `SlaProperties.java` — add guard field

`SlaProperties` uses `@ConfigurationProperties(prefix = "observability.sla")` with `@Getter`/`@Setter`. Add one field:

```java
private boolean allowReferenceTime = false;
```

Maps to YAML as `observability.sla.allow-reference-time`.

### 2. `application.yml` — default to off

Under the existing `observability.sla:` block, add:
```yaml
allow-reference-time: false
```

### 3. `application-local.yml` and `application-dev.yml` — enable for test environments

```yaml
observability:
  sla:
    allow-reference-time: true
```

### 4. New: `src/main/java/com/company/observability/config/ClockConfig.java`

```java
@Configuration
public class ClockConfig {

    @Bean
    @RequestScope
    Clock slaReferenceClock(HttpServletRequest request, SlaProperties slaProps) {
        if (slaProps.isAllowReferenceTime()) {
            String asOf = request.getParameter("as_of");
            if (asOf != null && !asOf.isBlank()) {
                try {
                    return Clock.fixed(Instant.parse(asOf), ZoneOffset.UTC);
                } catch (DateTimeParseException ignored) {
                    // invalid ISO instant — fall through to system clock
                }
            }
        }
        return Clock.systemUTC();
    }
}
```

`@RequestScope` creates a CGLIB proxy when injected into singleton services. Each HTTP request gets its own `Clock` instance transparently.

### 5. `ExpectedRunsService.java` — inject `Clock`, add 3-arg overload

Add to constructor injection (via existing `@RequiredArgsConstructor`):
```java
private final Clock clock;
```

Add the 3-arg implementation and convert the existing 2-arg method to a wrapper (preserves any direct callers in tests):
```java
// Keep: wrapper for backward compat
static SlaEval evaluateSlaStatus(Instant deadline, long bandGapMs) {
    return evaluateSlaStatus(deadline, bandGapMs, Instant.now());
}

// New: real implementation, accepts controllable now
static SlaEval evaluateSlaStatus(Instant deadline, long bandGapMs, Instant now) {
    if (deadline == null) return new SlaEval("ON_TIME", false);
    if (!now.isAfter(deadline)) return new SlaEval("ON_TIME", false);
    if (!now.isAfter(deadline.plusMillis(bandGapMs))) return new SlaEval("LATE", true);
    return new SlaEval("VERY_LATE", true);
}
```

In `placeholder()`, update the call to pass `clock.instant()`:
```java
// before:
SlaEval eval = evaluateSlaStatus(calculatorDeadline, slaProps.bandGapMs());
// after:
SlaEval eval = evaluateSlaStatus(calculatorDeadline, slaProps.bandGapMs(), clock.instant());
```

### 6. `CalculatorStateService.java` — inject `Clock`

Add to constructor injection:
```java
private final Clock clock;
```

In `entryWithSyntheticRun()`, update the call:
```java
// before:
ExpectedRunsService.SlaEval sla =
        ExpectedRunsService.evaluateSlaStatus(projectedSla, slaProperties.bandGapMs());
// after:
ExpectedRunsService.SlaEval sla =
        ExpectedRunsService.evaluateSlaStatus(projectedSla, slaProperties.bandGapMs(), clock.instant());
```

### 7. `ExpectedRunsServiceTest.java` — fix flaky time-dependent tests

Replace `Instant.now() ± seconds` patterns with deterministic fixed-instant 3-arg calls:

```java
// before (flaky):
assertThat(evaluateSlaStatus(Instant.now().plusSeconds(3600), BAND_GAP).slaStatus())
    .isEqualTo("ON_TIME");

// after (deterministic):
Instant ref = Instant.parse("2026-03-06T14:45:00Z");
assertThat(evaluateSlaStatus(ref.plusSeconds(3600), BAND_GAP, ref).slaStatus())
    .isEqualTo("ON_TIME");
```

For tests that go through service methods (where `Clock` is now injected), add:
```java
@Mock Clock clock;
// in @BeforeEach:
when(clock.instant()).thenReturn(Instant.parse("2026-03-06T14:45:00Z"));
```

---

## Files Changed

| File | Change |
|---|---|
| [SlaProperties.java](src/main/java/com/company/observability/config/SlaProperties.java) | Add `allowReferenceTime` field |
| [application.yml](src/main/resources/application.yml) | Add `allow-reference-time: false` under `observability.sla` |
| [application-local.yml](src/main/resources/application-local.yml) | Set `allow-reference-time: true` |
| [application-dev.yml](src/main/resources/application-dev.yml) | Set `allow-reference-time: true` |
| [ClockConfig.java](src/main/java/com/company/observability/config/ClockConfig.java) | New file — request-scoped `Clock` bean |
| [ExpectedRunsService.java](src/main/java/com/company/observability/service/ExpectedRunsService.java) | Inject `Clock`; add 3-arg `evaluateSlaStatus` overload; update `placeholder()` call |
| [CalculatorStateService.java](src/main/java/com/company/observability/service/CalculatorStateService.java) | Inject `Clock`; update `entryWithSyntheticRun()` call |
| [ExpectedRunsServiceTest.java](src/test/java/com/company/observability/service/ExpectedRunsServiceTest.java) | Replace flaky `Instant.now() ± seconds` with fixed-instant 3-arg calls |

---

## Verification

1. `SPRING_PROFILES_ACTIVE=local mvn spring-boot:run`
2. Query with `as_of` before deadline → all 10 capital regions return `slaStatus: ON_TIME`, no `slaBreached`.
3. Query with `as_of` 5 min past deadline → `LATE, slaBreached: true`.
4. Query with `as_of` 35 min past deadline → `VERY_LATE, slaBreached: true`.
5. Query without `as_of` → behaviour unchanged; `VERY_LATE` for the historical reporting date (correct, deadline was May 18).
6. Temporarily set `allow-reference-time: false` locally, verify `as_of` is silently ignored.
7. Run all tests: `SPRING_PROFILES_ACTIVE=local mvn clean test` — `ExpectedRunsServiceTest` now passes deterministically.
