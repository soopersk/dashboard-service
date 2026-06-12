# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

##  Role & Expertise

> You are a **Principal Software Architect and Senior Backend Engineer** with deep, hands-on expertise in:
>
> * Distributed systems and cloud-native architectures
> * Java, Spring Boot, Spring Cache, Spring Data
> * PostgreSQL (Azure Flexible Server), Redis cache
> * Apache Airflow orchestration
> * Kubernetes (AKS), Azure AD, Azure Monitor
> * Designing enterprise-grade observability and monitoring platforms
> * High-availability, scalability, security, and operational excellence
> * You write, refactor, debug, and architect code alongside a human developer who reviews your work in a side-by-side IDE setup.


## Tech Stack
- **Backend (Java)**: Java 17, Spring Boot 3.5.9, REST APIs, Redis Cache, NamedParameterJdbcTemplate (No JPA)
- **Database**: Azure PostgreSQL 17 (primary), Flyway for db migration
- **Infrastructure**: Docker (Rancher Desktop locally)
- **Build Tools**: Maven (Java)


**No Deprecated or Outdated Code:**
- **ALWAYS** use latest stable syntax and features from official documentation
- **NEVER** generate deprecated methods, classes, or patterns
- **ALWAYS** verify API signatures against current documentation before generating code
- **ALWAYS** check for breaking changes in recent versions

## Core Behaviors

> Defined in `.claude/rules/core-behaviors.md` (always loaded).

## Communication

- Be direct. No filler ("Certainly!", "Of course!", "Great question!")
- Quantify: "adds ~200ms latency" not "might be slower"
- When stuck or unsure, say so

## Functional Requirements
- Leverage Redis cache to reduce DB load
- Ingestion and write endpoints (start/complete) should be efficient with low latency
- On-completion and live SLA breach detection and alerting
- Dashboard feed (`/batch/runs`) — dimensional run state per calculator, optimized for performance
- Raw execution history (`/executions`) — per-run actual-vs-expected comparison

## Task Management

### Creating Tasks
1. Plan First: Write plan to `tasks/todo.md` with checkable items
2. Verify Plan: Check in before starting implementation
3. Track Progress: Mark items complete as you go
4. Explain Changes: High-level summary at each step
5. Document Results: Add review section to `tasks/todo.md`
6. Capture Lessons: Update `tasks/lessons.md` after corrections

### Working on Tasks
- Update status to in_progress BEFORE starting each task
- Mark completed only after verification (tests pass, linting clean, etc.)
- Add follow-up tasks discovered during implementation

### Resuming Tasks
- On session start, ALWAYS run TaskList to check for pending/in_progress tasks
- If tasks exist, summarize status and ask which to resume
- After /clear or /compact, immediately check TaskList again

## Subagent Strategy
- Use subagents liberally to keep main context window clean
- Offload research, exploration, and parallel analysis to subagents
- For complex problems, throw more compute at it via subagents
- One task per subagent for focused execution


## Commands

```bash
# Start local infrastructure (PostgreSQL + Redis)
docker compose up -d

# Run the application locally
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run

# Build
mvn clean package

# Run all tests (requires Docker containers running)
SPRING_PROFILES_ACTIVE=local mvn clean test

# Run a specific test class
mvn test -Dtest=ClassName

# Connect to local Postgres
docker exec -it observability-postgres psql -U postgres -d observability

# Connect to local Redis
docker exec -it observability-redis redis-cli
```

Local endpoints when running:
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Health: `http://localhost:8080/actuator/health`
- Prometheus metrics: `http://localhost:8080/actuator/prometheus`

## Architecture

**Spring Boot 3.5.9, Java 17.** No JPA   all persistence uses `JdbcTemplate` with manual `RowMapper`s. Flyway manages schema at `src/main/resources/db/migration/`.

### API Surface

Four controllers. All except `HealthController` require HTTP Basic auth and `X-Tenant-Id` header:

| Controller | Path | Purpose |
|---|---|---|
| `HealthController` | `GET /api/v1/health` | Health check (unauthenticated) |
| `RunIngestionController` | `POST /api/v1/runs/start`, `POST /api/v1/runs/{runId}/complete` | Airflow calls this to record run lifecycle |
| `RunQueryController` | `GET /api/v1/calculators/batch/runs` | Dashboard feed — dimensional run state per calculator for a reporting date. `keys` param is pipe-separated `calculator_name` values. Response keyed by `calculatorName`. Regional calculators return one `RunEntry` per `region`; typed calculators per `runType`. `isRerun=true` signals a re-trigger. |
| `AnalyticsController` | `GET /api/v1/analytics/calculators/{calculatorName}/executions` | Raw execution history — each physical run appears independently (no split-grouping). Includes `runNumber` and `expectedDurationMs` per entry for actual-vs-expected comparison. Path uses `calculatorName` (not UUID). |

`/api/v1/health`, Swagger, and `/v3/api-docs` are unauthenticated. Security is HTTP Basic with an in-memory single user configured via `observability.security.basic.*` properties (default: `admin`/`admin`).

### Multi-tenancy

`tenantId` is passed via `X-Tenant-Id` request header and stored on every `CalculatorRun`. `tenantId` is optional and not used in for any query filtering.

### Database (PostgreSQL)

`calculator_runs` is a **range-partitioned table** on `reporting_date` (see `V2__calculator_runs.sql`). The composite PK is `(run_id, reporting_date)`.

**Always include `reporting_date` when querying by `run_id`** to enable partition pruning.

Frequency-specific query windows:
- `DAILY`: `reporting_date >= CURRENT_DATE - 3 days`
- `MONTHLY`: `reporting_date = end-of-month` date within last 13 months

`PartitionManagementJob` creates future partitions daily at 1 AM. `DailyAggregationJob` (default 00:30 daily) rebuilds `calculator_sli_daily` from `calculator_runs` for a trailing window (idempotent recompute) and warms the `CalculatorProfile` cache — replacing the former per-completion aggregate write.

### Redis Caching

`RedisCalculatorCache` only tracks running state (it adds/removes `obs:running` members on each write — it is no longer a write-through / read-through cache for run history). The other caches below are owned by their respective services. Cache key structure:

| Key | Type | Content |
|---|---|---|
| `obs:running` | Set | `{calcId}:{frequency}` strings for currently-running runs — feeds the `INGESTION_RUN_ACTIVE` gauge via `countRunning()` (Redis-first, DB fallback). Maintained by `RedisCalculatorCache.trackRunningState()`. |
| `obs:sla:deadlines` | Sorted Set | Run keys scored by SLA deadline epoch ms (`SlaMonitoringCache`) |
| `obs:sla:run_info` | Hash | Run metadata JSON keyed by run key `{tenantId}:{runId}:{reportingDate}` |
| `obs:analytics:executions:{name}:{freq}:{days}:{runNumber\|all}:{asOfDate}` | String | Cached `/executions` responses keyed by `calculatorName` (5-min TTL). The only analytics cache written. |
| `obs:analytics:index:{name}` | Set | Tracks all `executions` keys for bulk invalidation (1h TTL). Keyed by `calculatorName` only. |
| `obs:profile:{calcName}:{frequency}` | String | Cached `CalculatorProfile` (blended, no runNumber/dim). TTL 26h (with samples) / 60m (empty sentinel). |
| `obs:profile:{calcName}:{frequency}:{runNumber}` | String | Run_number-scoped profile. Same TTL rules. |
| `obs:profile:{calcName}:{frequency}:{runNumber\|*}:{dim}` | String | Dimension-scoped profile (e.g. region "WMAP"). `*` when runNumber is null. Warmed nightly as third tier. All three tiers served by `CalculatorProfileService`. |
| `obs:state:{calculatorName}:{reportingDate}:{frequency}:{runNumber\|all}` | String | Cached `CalculatorEntry` for `/batch/runs`. State-aware TTL: 30s (RUNNING) / 60s (NOT_STARTED or empty) / 5m (terminal with failure/breach) / 4h (terminal clean). `CalculatorStateCacheService`. Invalidation: TTL-only. |

### SLA Detection (self-describing spec)

At start, `SlaBaselineResolver` derives one absolute deadline and freezes it into `calculator_runs.sla_time`; grading, live detection, and queries all compare against that frozen instant. **There is no global mode** — `StartRunRequest.slaTime` is a `String` that self-describes its form (switching a calculator to duration-based later is purely an Airflow catalogue change, zero service change):

| `slaTime` form | DAILY | MONTHLY |
|---|---|---|
| `"T+N@HH:mm"` (N≥1) | deadline = `nextBusinessDay(reportingDate, N)` at `HH:mm` in `slaTimezone` (default UTC). No band added (bands are grading-only) | **rejected** (`DomainValidationException`) — MONTHLY clock SLA must be bare `HH:mm` |
| `"HH:mm"` (bare clock) | offset falls back to `parseRunNumber(runNumber)` (run 1→T+1, run 2→T+2, null/invalid→T+2) | `TimeUtils.clockTimeDeadlineUtc(startTime, HH:mm)` — startTime's UTC date at the cutoff, rolled +1 day if at/before startTime |
| `"PT2H30M"` (ISO-8601 duration) | `deadline = startTime + duration×(1+thresholdPercent/100) + lateBand`; `baselineDurationMs = duration` | same |
| blank/null | **always-on** fallback chain: `expectedDurationMs` → profile avg (needs `minSampleSize` samples) → ungraded (`obs.sla.baseline.ungraded` counter) | same |

`expectedDurationMs` is the historical expectation (request → profile avg → resolved baseline → null), independent of the SLA limit, and is never gated by config. The raw spec is logged at ingestion (`event=run.start.persist slaSpec=…`) but **not persisted** — no schema change. Config: all knobs flat under `observability.sla.*` (`duration-threshold-percent`, `late-band-minutes`, `very-late-band-minutes`, `min-sample-size`, `lookback.*`, `slaTimezone`).

The `/batch/runs` NOT_STARTED projection recovers the T+N offset from the latest run's `reportingDate→sla_time` distance (DAILY) and scopes the latest-run lookup by `run_number` so a RUN1 projection does not borrow RUN2's deadline; MONTHLY projects best-effort from the estimated start date.

Two mechanisms:

1. **On-write** (`SlaEvaluationService`): grades the run's actual duration against the frozen `slaTime` — `≤ edge` → ON_TIME; `≤ edge + bandGap` → LATE/MEDIUM; beyond → VERY_LATE/HIGH; `FAILED`/`TIMEOUT` → CRITICAL. (The old absolute-time and 150%-of-expected checks were removed; there is no start-time breach.)

2. **Live** (`LiveSlaBreachDetectionJob`): on `observability.sla.live-detection.interval-ms` (default `15000` ms), queries Redis sorted set for runs past their frozen deadline, marks `sla_breached=true`, fires `SlaBreachedEvent`. Severity is based on minutes past the deadline using the band gap. Early warning runs on `observability.sla.early-warning.interval-ms` (default `60000` ms).

SLA monitoring now covers **DAILY and MONTHLY** (any run with a derived deadline), controlled by `observability.sla.live-tracking.enabled` (default: `true`).

### Event Flow

Spring `ApplicationEventPublisher` fires three internal events:
- `RunStartedEvent`   after every `startRun`
- `RunCompletedEvent`   on non-breach completion
- `SlaBreachedEvent`   on breach (on completion or from live detection)

All listeners use `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`   they run after the DB transaction commits, on the async thread pool.

| Listener | Events handled | Action |
|---|---|---|
| `AlertHandlerService` | `SlaBreachedEvent` only | Persists `sla_breach_events` record, logs alert warning |
| `AnalyticsCacheService` | `RunStartedEvent` (executions prefix), `RunCompletedEvent`, `SlaBreachedEvent` | Invalidates `executions` analytics cache keys for the calculator (name-index only) |

### Profiles

| Profile | Use |
|---|---|
| `local` | Localhost Postgres/Redis via Docker Compose |
| `dev` | Azure infra (dev) |
| `prod` | Azure infra (prod) |

Environment variables override defaults: `POSTGRES_HOST`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `OBS_BASIC_USER`, `OBS_BASIC_PASSWORD`.

### Key Conventions

- All timestamps are stored as `TIMESTAMPTZ` (UTC); CET display values (`start_hour_cet`, `end_hour_cet`) are pre-computed via `TimeUtils` and stored as `DECIMAL(4,2)`.
- `run_parameters` and `additional_attributes` are `JSONB` columns, serialized/deserialized via `JsonbConverter`.
- Upsert pattern: all writes use `INSERT ... ON CONFLICT (run_id, reporting_date) DO UPDATE`   the service is idempotent.
- `Frequency` enum (`com.company.observability.domain.enums.Frequency`) accepts both full names (`DAILY`, `MONTHLY`) and short codes (`D`, `M`) via `Frequency.from()`. Returns `DAILY` on unknown/null input — does NOT throw.
- The daily aggregate table is `calculator_sli_daily` (Java class: `DailyAggregate`, repo: `DailyAggregateRepository`). It stores **sums** (averages computed at read time), is **frequency-aware** (4-col PK incl. `frequency`, V8), and is rebuilt by the nightly `DailyAggregationJob` — **not** written on `completeRun()`. It is now read **only** by `CalculatorProfileService` (frequency-scoped) for the SLA baseline/estimate profile; the former aggregate-backed analytics endpoints (`/runtime`, `/sla-summary`, `/trends`) have been removed.
- `executions` reads raw `calculator_runs` via `findRunsByName()` (live, not EOD-stale).
- Estimated start/end on a run are set at start by precedence: request value → resolved baseline fallback (from `slaTime`/`expectedDurationMs`/profile average) with profile-based start fallback when needed.
- Columns `calculator_name`, `start_time`, `sla_time`, `expected_duration_ms`, `estimated_start/end_time` are **immutable after first INSERT** (ON CONFLICT UPDATE deliberately omits them).

### Known Tech Debt (quick reference   see `tech-spec.md` Â§13 for detail)

| ID | Summary | Risk |
|---|---|---|
| TD-1 | `findById(String)`   no `reporting_date`, full scan ~455 partitions | High perf |
| TD-2 | `cleanup_expired_idempotency_keys()` PL/pgSQL function references dropped table | Runtime error if called |
| TD-3 | ✅ RESOLVED — aggregate is sum-based and rebuilt by the nightly `DailyAggregationJob` (single-writer recompute); `upsertDaily()` removed | — |
| TD-4 | `RETRYING` alert status excluded from retry query | Silent alert loss |
| TD-5 | `SlaBreachEvent` breach_type/severity/alertStatus stored as raw String | No Java type safety |
| TD-6 | `Frequency.lookbackDays` is dead code (never used in queries) | Latent bug if ever used |
| TD-7 | Basic Auth password is plaintext (`{noop}` encoding) | Security |
| TD-8 | MONTHLY queries scan ~395 partitions (end-of-month filter can't prune) | Medium perf |
| TD-9 | No per-endpoint latency tracking (only batch has a Timer) | Observability gap |
| TD-10 | `application-dev.yml` and `application-prod.yml` have stale JPA/Hibernate config | Misleading |
| TD-11 | Alert delivery is log-only   no external notification channel wired | Feature gap |


