# RunNumberScope Detailed Implementation Plan

## Objective

Replace nullable `runNumber` query semantics with an explicit `RunNumberScope` model in `observability-service`, while preserving ingestion semantics (`run_number` may remain `NULL` when upstream does not provide a concrete cycle).

This plan targets:

- Cleaner API contracts in service/repository layers
- Consistent SQL semantics across features
- Removal of JDBC untyped-null pitfalls
- Backward-compatible rollout with minimal functional risk

---

# Current Behavior Snapshot

Observed from current code:

- Ingestion accepts nullable `runNumber` and persists it as-is in `calculator_runs`.
- Query paths normalize blank `runNumber` to `null` in multiple places.
- Repository semantics are inconsistent:
  - Exact only: `run_number = :runNumber`
  - Exact plus null: `run_number = :runNumber OR run_number IS NULL`
  - Nullable control in SQL: `(:runNumber IS NULL OR run_number = :runNumber)`
- `DailyAggregateRepository.findRecentExact(...)` already uses typed binding for nullable run number (`Types.VARCHAR`), but `findProfileByRunNumberAndDimension(...)` does not.
- Cache keys already use an internal token (`all`) for wildcard scope; this is key formatting only, not persisted data.

---

# Target Design

## 1. Separate Data from Query Intent

### Ingestion Data (`calculator_runs.run_number`)

- `NULL` means upstream run has no explicit cycle number.
- Non-null value means explicit cycle number.

### Query Intent (`RunNumberScope`)

- `ANY`: Aggregate/query across all run-number buckets.
- `EXACT(value)`: Query one explicit run number.

No magic value (for example `"ALL"`) is written to `run_number` in the database.

---

## 2. Explicit Matching Policy Where Needed

Because current methods intentionally differ, introduce a small query policy at repository level where relevant:

- `EXACT_ONLY`
  - Match only `run_number = value`
- `EXACT_OR_NULL`
  - Match `run_number = value OR run_number IS NULL`

Use this only where legacy behavior relies on null rows being included with exact requests.

---

# Proposed Domain/API Additions

## New Type: `RunNumberScope`

Add under:

```text
src/main/java/com/ubs/cf/observability/domain/
```

### Structure

Sealed interface / record-style model:

- `RunNumberScope.Any`
- `RunNumberScope.Exact(String value)`

### Factory Helpers

- `fromNullable(String raw)`
- `fromRequestParam(String raw)`  
  - Blank → `ANY`
  - Numeric normalization

### Utility Methods

- `isAny()`
- `valueOrNull()` (adapter only for transitional code)

### Optional Helper

`RunNumberMatchPolicy`

---

## New Enum: `RunNumberMatchPolicy`

Add in repository package:

```java
EXACT_ONLY,
EXACT_OR_NULL
```

---

# File-by-File Refactor Plan

## Phase 0 — Additive Foundation (No Behavior Change)

### Tasks

1. Add `RunNumberScope` type.
2. Add parser/normalizer utility reused by controllers/services.
3. Keep existing method overloads that accept `String runNumber` and delegate to scope-based methods.

### Files

#### New

```text
src/main/java/com/ubs/cf/observability/domain/RunNumberScope.java
```

#### Optional

```text
src/main/java/com/ubs/cf/observability/util/RunNumberScopes.java
```

---

## Phase 1 — Service Boundary Migration

Convert internal service APIs from nullable `String` to `RunNumberScope`.

### Services to Migrate

- `CalculatorProfileService`
- `CalculatorStateService`
- `AnalyticsService`
- `ExpectedRunsService`

### Additional Call Sites

- `RunIngestionService`
  - Where profiles are resolved

### Controller Entry Points

#### `RunQueryController`

- Parse request `run_number` once.
- Create `RunNumberScope`.
- Pass scope object downstream.

### Notes

- Continue accepting existing query parameter shape externally.
- Keep response schema unchanged in this phase.

---

## Phase 2 — Repository Semantics Unification

Refactor repository method signatures and SQL construction to be scope-driven.

---

### DailyAggregateRepository

#### 1. `findProfileByRunNumberAndDimension(...)`

Replace nullable SQL predicate:

```sql
(:runNumber IS NULL OR ...)
```

With branch-based SQL:

##### ANY

Omit run-number predicate entirely.

##### EXACT

```sql
AND run_number = :runNumber
```

Additional notes:

- Avoid nullable bound params in this method.
- During transition, if nullable param path remains, bind with:

```java
Types.VARCHAR
```

---

#### 2. `findRecentExactByDimension(...)`

#### 3. `findRecentExact(...)`

Align scope behavior with chosen semantics:

- If caller is `ANY`, use all run numbers in fallback.
- If caller is `EXACT`, use exact-match semantics (plus optional policy).

---

#### 4. `findProfileByRunNumber(...)`

Convert to:

- Scope-driven implementation, or
- Exact-only helper invoked from scope switch.

---

### CalculatorRunRepository

Replace nullable `runNumber` branching with scope/match-policy explicit methods in:

- `findAllRunsByDateAndDimension(...)`
- `findLatestRunEstimatesByName(...)`
- `findRecentRunsByCalculatorNameAndFrequency(...)`

### Important

Preserve legacy places that intentionally include null rows when run number is scoped.

---

## Phase 3 — Cache Key and Telemetry Normalization

### 1. Introduce One Central Key-Segment Mapper

| Scope | Cache Key |
|---------|---------|
| `ANY` | `all` |
| `EXACT(n)` | `n` |

### 2. Reuse In

- `AnalyticsCacheService`
- `CalculatorStateCacheService`
- Profile cache key builder in `CalculatorProfileService`

### 3. Optional Metrics

Add metric tag:

```text
run_scope=any
run_scope=exact
```

For:

- Profile queries
- State queries
- Analytics queries

---

# Behavioral Decisions to Confirm Before Coding

## 1. For EXACT Queries, Should Null-Run Records Be Included?

Current behavior is mixed.

Decision should be made per use case and encoded via `RunNumberMatchPolicy`.

---

## 2. For Dimension-Scoped Fallback When Scope Is ANY

Should fallback:

### Option A (Recommended)

Aggregate across all run numbers.

### Option B

Include only null rows (current behavior in one path).

---

## 3. Run Number Validation

Should non-numeric run numbers:

- Continue to be accepted for backward compatibility?
- Be rejected at the API boundary?