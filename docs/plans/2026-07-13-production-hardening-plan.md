# Production Hardening Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix the critical/major defects found in the 2026-07-13 architecture review (partition lifecycle, multi-replica job safety, alert durability, ingestion race, baseline data quality), then clean up the remaining medium/minor code and config issues.

**Architecture:** Phase 1 makes the service survive production: runtime partition management (the single biggest outage risk), ShedLock for multi-replica scheduled jobs, a durable alert path, an atomic `startRun`, and SUCCESS-only SLA baselines. Phase 2 is correctness/hygiene: circular-mean start times, API/error-contract fixes, Redis batching, security and config cleanup, and doc alignment. No behavior change is made to the intentionally-disabled live SLA detection (user decision 2026-07-13); it stays off.

**Tech Stack:** Java 17, Spring Boot 3.5.9, NamedParameterJdbcTemplate (no JPA), PostgreSQL 17 (range-partitioned `calculator_runs`), Flyway, Redis/Lettuce, ShedLock (new), Testcontainers.

**Context/decisions from review:**
- Deployment is **multiple replicas / HPA** → job coordination is mandatory, not optional.
- Live SLA detection disabled in all YAMLs is **intentional** → do NOT enable; only add a startup warning (P2.9).
- Full findings: see plan review notes; each task below cites the defect it fixes.

**Conventions for every task:**
- Tests need local infra: `docker compose up -d` once, then `mvn test -Dtest=<Class>` (Testcontainers-based tests manage their own containers; `PostgresJdbcIntegrationTestBase` / `RedisIntegrationTestBase` are the bases to extend).
- Full suite: `SPRING_PROFILES_ACTIVE=local mvn clean test`.
- Commit after each task (conventional commits, e.g. `fix: …` / `feat: …`).
- Flyway versions assume current max is **V9**. If other migrations land first, renumber.

---

## Phase 1 — Critical & Major

### Task P1.1: Runtime partition creation + retention (CRITICAL C1)

**Defect:** `create_calculator_run_partitions()` is called exactly once — inside the V2 migration ([V2__calculator_runs.sql:155](../../src/main/resources/db/migration/V2__calculator_runs.sql)). `PartitionManagementJob` only monitors. Ingestion hard-fails ~60 days after first deploy (`no partition of relation "calculator_runs" found for row`); `drop_old_calculator_run_partitions()` never runs, so 395-day retention is unenforced. `docs/architecture.md:115-116` documents jobs that don't exist.

**Files:**
- Modify: `src/main/java/com/company/observability/scheduled/PartitionManagementJob.java`
- Modify: `src/main/java/com/company/observability/util/ObservabilityConstants.java` (add metric names)
- Test: `src/test/java/com/company/observability/scheduled/PartitionManagementJobJdbcTest.java` (new, extends `PostgresJdbcIntegrationTestBase`)

**Step 1: Write the failing test**

```java
package com.company.observability.scheduled;

import com.company.observability.repository.PostgresJdbcIntegrationTestBase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PartitionManagementJobJdbcTest extends PostgresJdbcIntegrationTestBase {

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    private PartitionManagementJob job() {
        return new PartitionManagementJob(jdbcTemplate, new SimpleMeterRegistry());
    }

    @Test
    void createFuturePartitions_createsSixtyDayWindow() {
        String partitionName = "calculator_runs_" + LocalDate.now().plusDays(59)
                .format(DateTimeFormatter.ofPattern("yyyy_MM_dd"));
        // Drop it if the migration already created it, so the job has work to do
        jdbcTemplate.getJdbcTemplate().execute("DROP TABLE IF EXISTS " + partitionName);

        job().createFuturePartitions();

        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_class WHERE relname = :name)",
                Map.of("name", partitionName), Boolean.class);
        assertThat(exists).isTrue();
    }

    @Test
    void dropOldPartitions_removesPartitionsPastRetention() {
        // Create a fake ancient partition, then verify the job drops it
        jdbcTemplate.getJdbcTemplate().execute("""
                CREATE TABLE IF NOT EXISTS calculator_runs_2020_01_01
                PARTITION OF calculator_runs FOR VALUES FROM ('2020-01-01') TO ('2020-01-02')""");

        job().dropOldPartitions();

        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_class WHERE relname = 'calculator_runs_2020_01_01')",
                Map.of(), Boolean.class);
        assertThat(exists).isFalse();
    }
}
```

**Step 2: Run to verify failure**

Run: `mvn test -Dtest=PartitionManagementJobJdbcTest`
Expected: COMPILE ERROR — `createFuturePartitions()` / `dropOldPartitions()` don't exist.

**Step 3: Implement the job methods**

Add to `PartitionManagementJob` (below `monitorPartitionHealth`), plus two counters:

```java
/**
 * Creates daily partitions for the rolling [yesterday, +60d] window by invoking the
 * V2-defined SQL function. Without this the last partition ages out ~60 days after the
 * migration ran and every INSERT fails with "no partition of relation found for row".
 */
@Scheduled(cron = "${observability.partitions.creation.cron:0 0 1 * * *}")
public void createFuturePartitions() {
    Map<String, String> snapshot = MdcContextUtil.setJobContext("partition-create");
    try {
        jdbcTemplate.getJdbcTemplate().execute("SELECT create_calculator_run_partitions()");
        log.info("event=partition.create outcome=success");
        meterRegistry.counter(PARTITION_CREATE_EXECUTION, "result", "success").increment();
    } catch (Exception e) {
        log.error("event=partition.create outcome=failure", e);
        meterRegistry.counter(PARTITION_CREATE_EXECUTION, "result", "failure").increment();
    } finally {
        MdcContextUtil.restoreContext(snapshot);
    }
}

/** Enforces the 395-day retention documented in V2 (drop function was never invoked at runtime). */
@Scheduled(cron = "${observability.partitions.retention.cron:0 0 2 * * SUN}")
public void dropOldPartitions() {
    Map<String, String> snapshot = MdcContextUtil.setJobContext("partition-drop");
    try {
        jdbcTemplate.getJdbcTemplate().execute("SELECT drop_old_calculator_run_partitions()");
        log.info("event=partition.drop outcome=success");
        meterRegistry.counter(PARTITION_DROP_EXECUTION, "result", "success").increment();
    } catch (Exception e) {
        log.error("event=partition.drop outcome=failure", e);
        meterRegistry.counter(PARTITION_DROP_EXECUTION, "result", "failure").increment();
    } finally {
        MdcContextUtil.restoreContext(snapshot);
    }
}
```

In `ObservabilityConstants` add:

```java
public static final String PARTITION_CREATE_EXECUTION = "obs.partition.create.execution";
public static final String PARTITION_DROP_EXECUTION   = "obs.partition.drop.execution";
```

**Step 4: Run tests**

Run: `mvn test -Dtest=PartitionManagementJobJdbcTest`
Expected: PASS (2 tests).

**Step 5: Commit**

```bash
git add src/main/java/com/company/observability/scheduled/PartitionManagementJob.java \
        src/main/java/com/company/observability/util/ObservabilityConstants.java \
        src/test/java/com/company/observability/scheduled/PartitionManagementJobJdbcTest.java
git commit -m "fix: actually run partition create/drop functions on schedule (C1)"
```

---

### Task P1.2: Backfill-safe partition range creation + admin endpoint (CRITICAL C1, Airflow backfills)

**Defect:** Partitions exist only from (migration day − 1) forward. An Airflow backfill posting `reporting_date` older than that fails on INSERT. There is no `DEFAULT` partition (deliberately — a DEFAULT holding rows blocks later `CREATE PARTITION` for overlapping ranges, an operational trap). Fix: an explicit range-creation function + admin endpoint, called before running a backfill.

**Files:**
- Create: `src/main/resources/db/migration/V10__partition_range_function.sql`
- Create: `src/main/java/com/company/observability/controller/AdminPartitionController.java`
- Modify: `src/main/java/com/company/observability/scheduled/PartitionManagementJob.java` (add `ensurePartitions(from, to)`)
- Test: `src/test/java/com/company/observability/controller/AdminPartitionControllerTest.java` (mirror `AdminAggregationControllerTest` style)
- Test: extend `PartitionManagementJobJdbcTest`

**Step 1: Migration**

```sql
-- V10__partition_range_function.sql
-- Explicit-range partition creation for backfills: create daily partitions covering
-- [start_date, end_date] so historical reporting_dates can be ingested. Mirrors the
-- rolling-window function from V2 but with caller-supplied bounds.
CREATE OR REPLACE FUNCTION create_calculator_run_partitions_range(start_date DATE, end_date DATE)
RETURNS INTEGER AS $$
DECLARE
    partition_date DATE := start_date;
    partition_name TEXT;
    created_count  INTEGER := 0;
BEGIN
    WHILE partition_date <= end_date LOOP
        partition_name := 'calculator_runs_' || TO_CHAR(partition_date, 'YYYY_MM_DD');
        IF NOT EXISTS (
            SELECT 1 FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relname = partition_name AND n.nspname = 'public'
        ) THEN
            EXECUTE format(
                'CREATE TABLE IF NOT EXISTS %I PARTITION OF calculator_runs
                 FOR VALUES FROM (%L) TO (%L)',
                partition_name, partition_date, partition_date + INTERVAL '1 day');
            created_count := created_count + 1;
        END IF;
        partition_date := partition_date + INTERVAL '1 day';
    END LOOP;
    RETURN created_count;
END;
$$ LANGUAGE plpgsql;
```

**Step 2: Failing test (job helper)** — add to `PartitionManagementJobJdbcTest`:

```java
@Test
void ensurePartitions_createsHistoricalRange() {
    int created = job().ensurePartitions(LocalDate.of(2023, 6, 1), LocalDate.of(2023, 6, 3));
    assertThat(created).isEqualTo(3);
    // Idempotent
    assertThat(job().ensurePartitions(LocalDate.of(2023, 6, 1), LocalDate.of(2023, 6, 3))).isZero();
}
```

Run: `mvn test -Dtest=PartitionManagementJobJdbcTest` → COMPILE ERROR.

**Step 3: Implement**

`PartitionManagementJob`:

```java
/** Backfill support: create partitions for an explicit historical range. Returns count created. */
public int ensurePartitions(LocalDate from, LocalDate to) {
    Integer created = jdbcTemplate.queryForObject(
            "SELECT create_calculator_run_partitions_range(:from, :to)",
            new MapSqlParameterSource().addValue("from", from).addValue("to", to),
            Integer.class);
    log.info("event=partition.ensure_range outcome=success from={} to={} created={}", from, to, created);
    return created != null ? created : 0;
}
```

`AdminPartitionController` (follows `AdminAggregationController` shape; `/api/v1/admin/**` already requires ROLE_ADMIN):

```java
package com.company.observability.controller;

import com.company.observability.scheduled.PartitionManagementJob;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/partitions")
@Tag(name = "Admin Partitions", description = "Admin-only partition management for backfills")
@RequiredArgsConstructor
public class AdminPartitionController {

    private static final long MAX_SPAN_DAYS = 800;

    private final PartitionManagementJob partitionManagementJob;

    @PostMapping("/ensure")
    @Operation(summary = "Create calculator_runs partitions for a date range",
            description = "Run before an Airflow backfill whose reporting_dates predate existing partitions.")
    public ResponseEntity<Map<String, Object>> ensure(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_SPAN_DAYS) {
            throw new IllegalArgumentException("Range exceeds maximum span of " + MAX_SPAN_DAYS + " days");
        }
        int created = partitionManagementJob.ensurePartitions(from, to);
        return ResponseEntity.ok(Map.of("from", from, "to", to, "partitionsCreated", created));
    }
}
```

Controller test: mirror `AdminAggregationControllerTest` (mock the job; assert 200 + body, 400 on `from > to` and span > 800).

**Step 4: Run tests**

Run: `mvn test -Dtest=PartitionManagementJobJdbcTest,AdminPartitionControllerTest` → PASS.

**Step 5: Commit**

```bash
git add src/main/resources/db/migration/V10__partition_range_function.sql \
        src/main/java/com/company/observability/scheduled/PartitionManagementJob.java \
        src/main/java/com/company/observability/controller/AdminPartitionController.java \
        src/test/java/com/company/observability/controller/AdminPartitionControllerTest.java \
        src/test/java/com/company/observability/scheduled/PartitionManagementJobJdbcTest.java
git commit -m "feat: partition range creation function + admin endpoint for backfills (C1)"
```

---

### Task P1.3: ShedLock on all scheduled jobs (CRITICAL C2 — multi-replica)

**Defect:** No distributed lock library. With 2+ replicas, `DailyAggregationJob.runDailyAggregation()` runs concurrently: `recomputeForDateRange` is DELETE + INSERT in one transaction — two replicas race to insert the same PK rows → duplicate-key rollback → one frequency's recompute silently lost for the night. Partition DDL and live-detection polling are also duplicated.

**Files:**
- Modify: `pom.xml`
- Create: `src/main/resources/db/migration/V11__shedlock.sql`
- Create: `src/main/java/com/company/observability/config/SchedulingConfig.java`
- Modify: `src/main/java/com/company/observability/scheduled/DailyAggregationJob.java`
- Modify: `src/main/java/com/company/observability/scheduled/PartitionManagementJob.java`
- Modify: `src/main/java/com/company/observability/scheduled/LiveSlaBreachDetectionJob.java`

**Step 1: Dependencies** — in `pom.xml` (⚠️ verify latest 6.x on Maven Central at implementation time; 6.3.0 as of plan writing):

```xml
<dependency>
    <groupId>net.javacrumbs.shedlock</groupId>
    <artifactId>shedlock-spring</artifactId>
    <version>6.3.0</version>
</dependency>
<dependency>
    <groupId>net.javacrumbs.shedlock</groupId>
    <artifactId>shedlock-provider-jdbc-template</artifactId>
    <version>6.3.0</version>
</dependency>
```

**Step 2: Migration** — official ShedLock Postgres schema:

```sql
-- V11__shedlock.sql
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
```

**Step 3: Config**

```java
package com.company.observability.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Distributed lock for @Scheduled jobs — the service runs with multiple replicas (HPA);
 * without this, DailyAggregationJob's DELETE+INSERT recompute races itself across pods
 * (duplicate-key rollback → lost nightly recompute) and partition DDL / SLA polling duplicate.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulingConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }
}
```

**Step 4: Annotate every `@Scheduled` method** (import `net.javacrumbs.shedlock.spring.annotation.SchedulerLock`):

| Class / method | Annotation |
|---|---|
| `DailyAggregationJob.runDailyAggregation` | `@SchedulerLock(name = "dailyAggregation", lockAtMostFor = "PT45M", lockAtLeastFor = "PT2M")` |
| `PartitionManagementJob.createFuturePartitions` | `@SchedulerLock(name = "partitionCreate", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")` |
| `PartitionManagementJob.dropOldPartitions` | `@SchedulerLock(name = "partitionDrop", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")` |
| `PartitionManagementJob.monitorPartitionHealth` | `@SchedulerLock(name = "partitionMonitor", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1M")` |
| `LiveSlaBreachDetectionJob.detectLiveSlaBreaches` | `@SchedulerLock(name = "liveSlaDetection", lockAtMostFor = "PT2M", lockAtLeastFor = "PT5S")` |
| `LiveSlaBreachDetectionJob.sweepOverdueRunsFromDb` | `@SchedulerLock(name = "slaDbSweep", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")` |
| `LiveSlaBreachDetectionJob.detectApproachingSla` | `@SchedulerLock(name = "slaEarlyWarning", lockAtMostFor = "PT2M", lockAtLeastFor = "PT30S")` |

Note: `lockAtMostFor` must exceed worst-case runtime; `dailyAggregation` PT45M is deliberate headroom for the 800-day admin recompute path NOT covered by the lock (admin endpoint is manual — leave unlocked, it's an operator action).

**Step 5: Verify**

Run: `SPRING_PROFILES_ACTIVE=local mvn clean test`
Expected: full suite passes; Spring context tests load with the new config (Flyway creates `shedlock`).

Manual smoke (optional): `SPRING_PROFILES_ACTIVE=local mvn spring-boot:run`, then `docker exec -it observability-postgres psql -U postgres -d observability -c "SELECT * FROM shedlock"` after 01:00-cron trigger or a manual job invocation shows lock rows.

**Step 6: Commit**

```bash
git add pom.xml src/main/resources/db/migration/V11__shedlock.sql \
        src/main/java/com/company/observability/config/SchedulingConfig.java \
        src/main/java/com/company/observability/scheduled/
git commit -m "feat: ShedLock distributed locking for all scheduled jobs (C2)"
```

---

### Task P1.4: Durable alert path — stop rolling back the breach record (MAJOR H1)

**Defect:** `AlertHandlerService.handleSlaBreachEvent` is `@Async @Transactional(REQUIRES_NEW)`. On sender failure, `markFailed` persists the FAILED status **then rethrows** ([AlertHandlerService.java:118-125](../../src/main/java/com/company/observability/service/AlertHandlerService.java)) — the rethrow rolls back the REQUIRES_NEW transaction, wiping BOTH the `save(breach)` and the FAILED update. No DB trace of the breach remains. Latent while the sender is log-only; armed the moment a real channel (TD-11) is wired.

**Fix:** Remove `@Transactional(REQUIRES_NEW)` (the listener runs `@Async` on a fresh thread; each `JdbcTemplate` write auto-commits, so `save` is durable before `send` is attempted). Swallow the sender exception after `markFailed` — the FAILED row + `SLA_ALERT_FAILED` metric + lifecycle log ARE the failure signal.

**Files:**
- Modify: `src/main/java/com/company/observability/service/AlertHandlerService.java`
- Test: `src/test/java/com/company/observability/service/AlertHandlerServiceTest.java`

**Step 1: Write the failing test** (adapt to the existing test's mock setup):

```java
@Test
void senderFailure_persistsBreachAndFailedStatus_withoutThrowing() {
    when(breachRepository.save(any())).thenAnswer(inv -> {
        SlaBreachEvent b = inv.getArgument(0);
        b.setBreachId(42L);
        return b;
    });
    doThrow(new AlertDeliveryException("smtp down")).when(alertSender).send(any());

    assertThatCode(() -> service.handleSlaBreachEvent(breachedEvent()))
            .doesNotThrowAnyException();

    ArgumentCaptor<SlaBreachEvent> captor = ArgumentCaptor.forClass(SlaBreachEvent.class);
    verify(breachRepository).update(captor.capture());
    assertThat(captor.getValue().getAlertStatus()).isEqualTo(AlertStatus.FAILED);
    assertThat(captor.getValue().getRetryCount()).isEqualTo(1);
}
```

**Step 2: Run to verify failure**

Run: `mvn test -Dtest=AlertHandlerServiceTest`
Expected: FAIL — current `sendAlert` rethrows.

**Step 3: Implement**

In `AlertHandlerService`:
1. Delete `@Transactional(propagation = Propagation.REQUIRES_NEW)` from `handleSlaBreachEvent` (and the now-unused `Propagation`/`Transactional` imports).
2. Replace `sendAlert`'s catch blocks:

```java
private void sendAlert(SlaBreachEvent breach, CalculatorRun run) {
    try {
        alertSender.send(breach);
        breach.setAlerted(true);
        breach.setAlertedAt(Instant.now());
        breach.setAlertStatus(AlertStatus.SENT);
        breachRepository.update(breach);

        meterRegistry.counter(SLA_ALERT_SENT,
                "band", run.getSlaBand() != null ? run.getSlaBand().name() : "NONE",
                "frequency", run.getFrequency().name(),
                "channel", alertSender.channelName()
        ).increment();

        lifecycleLogger.emit(LifecycleEvent.SLA_ALERT_SENT, kv("breachId", breach.getBreachId()));

    } catch (Exception e) {
        // Do NOT rethrow: the breach row and this FAILED update must stay committed —
        // rethrowing under a listener transaction previously rolled back the audit trail.
        markFailed(breach, run, e);
    }
}
```

**Step 4: Run tests**

Run: `mvn test -Dtest=AlertHandlerServiceTest` → PASS (all, including pre-existing).

**Step 5: Commit**

```bash
git add src/main/java/com/company/observability/service/AlertHandlerService.java \
        src/test/java/com/company/observability/service/AlertHandlerServiceTest.java
git commit -m "fix: keep breach record + FAILED status durable when alert sender fails (H1)"
```

---

### Task P1.5: `sla_breach_events` composite uniqueness (MAJOR H2)

**Defect:** V4 declares `run_id UNIQUE`, but the runs PK is `(run_id, reporting_date)` — the same `run_id` may legitimately recur on a different reporting date. The second breach then hits `DuplicateKeyException`, which `AlertHandlerService` treats as an idempotent duplicate → **silent alert loss**.

**Files:**
- Create: `src/main/resources/db/migration/V12__sla_breach_events_composite_unique.sql`
- Test: `src/test/java/com/company/observability/repository/SlaBreachEventRepositoryJdbcTest.java` (extend)

**Step 1: Write the failing test** (in `SlaBreachEventRepositoryJdbcTest`, reuse its builder helpers):

```java
@Test
void save_allowsSameRunIdOnDifferentReportingDate() {
    repository.save(breach("run-x", LocalDate.of(2026, 7, 1)));
    // Currently throws DuplicateKeyException because of the run_id-only UNIQUE
    assertThatCode(() -> repository.save(breach("run-x", LocalDate.of(2026, 7, 2))))
            .doesNotThrowAnyException();
}

@Test
void save_rejectsDuplicateRunIdAndReportingDate() {
    repository.save(breach("run-y", LocalDate.of(2026, 7, 1)));
    assertThatThrownBy(() -> repository.save(breach("run-y", LocalDate.of(2026, 7, 1))))
            .isInstanceOf(DuplicateKeyException.class);
}
```

**Step 2: Run to verify failure**

Run: `mvn test -Dtest=SlaBreachEventRepositoryJdbcTest`
Expected: first test FAILS (DuplicateKeyException on the second date).

**Step 3: Migration**

```sql
-- V12__sla_breach_events_composite_unique.sql
-- run_id alone is not unique in the domain: the runs PK is (run_id, reporting_date).
-- A run_id recurring on a later reporting date must produce its own breach row instead of
-- being swallowed as a duplicate by AlertHandlerService.
-- NULLS NOT DISTINCT (PG15+): reporting_date is nullable in this table; two NULL-date rows
-- for one run_id must still be deduplicated.
ALTER TABLE sla_breach_events DROP CONSTRAINT IF EXISTS sla_breach_events_run_id_key;
ALTER TABLE sla_breach_events
    ADD CONSTRAINT sla_breach_events_run_reporting_uq
    UNIQUE NULLS NOT DISTINCT (run_id, reporting_date);
```

⚠️ Check the Testcontainers image in `PostgresJdbcIntegrationTestBase` is PG 15+ (prod is PG 17). If older, bump the image (preferred) — do not silently drop `NULLS NOT DISTINCT`.

**Step 4: Run tests**

Run: `mvn test -Dtest=SlaBreachEventRepositoryJdbcTest` → PASS.
Also: `mvn test -Dtest=AlertHandlerServiceTest` (duplicate handling unchanged).

**Step 5: Commit**

```bash
git add src/main/resources/db/migration/V12__sla_breach_events_composite_unique.sql \
        src/test/java/com/company/observability/repository/SlaBreachEventRepositoryJdbcTest.java
git commit -m "fix: breach uniqueness on (run_id, reporting_date), not run_id alone (H2)"
```

---

### Task P1.6: Atomic `startRun` — close the check-then-insert race (MAJOR H3)

**Defect:** `doStartRun` does `findById` → build → `upsert` ([RunIngestionService.java:73-179](../../src/main/java/com/company/observability/service/RunIngestionService.java)). Two concurrent starts (Airflow retry after a network timeout) both pass the existence check → both return 201/`created=true`, both publish `RunStartedEvent` (double async listeners/metrics), and the loser's `ON CONFLICT DO UPDATE` rewrites mutable columns. Fix: insert with `DO NOTHING RETURNING *`; derive `created` from whether a row came back.

**Files:**
- Modify: `src/main/java/com/company/observability/repository/CalculatorRunRepository.java`
- Modify: `src/main/java/com/company/observability/service/RunIngestionService.java`
- Test: `src/test/java/com/company/observability/repository/CalculatorRunRepositoryJdbcTest.java` (extend)
- Test: `src/test/java/com/company/observability/service/RunIngestionServiceTest.java` (extend)

**Step 1: Failing repository test** (`CalculatorRunRepositoryJdbcTest`):

```java
@Test
void insertIfAbsent_secondCallReturnsEmpty() {
    CalculatorRun run = TestFixtures.runningRun("race-run", LocalDate.now());
    assertThat(repository.insertIfAbsent(run)).isPresent();
    assertThat(repository.insertIfAbsent(run)).isEmpty();   // conflict → no row returned
}
```

Run: `mvn test -Dtest=CalculatorRunRepositoryJdbcTest` → COMPILE ERROR.

**Step 2: Repository — extract shared params, add `insertIfAbsent`**

In `CalculatorRunRepository`:
1. Extract the parameter-building block from `upsert` (lines building `MapSqlParameterSource`) into `private MapSqlParameterSource insertParams(CalculatorRun run)` and reuse it in `upsert`.
2. Add:

```java
/**
 * Insert-only variant for run start: ON CONFLICT DO NOTHING. Returns the inserted row,
 * or empty when the (run_id, reporting_date) row already exists — the caller treats that
 * as an idempotent replay and must NOT publish start events for it. Closes the
 * check-then-insert race between concurrent duplicate /start calls.
 */
public Optional<CalculatorRun> insertIfAbsent(CalculatorRun run) {
    Instant now = Instant.now();
    if (run.getCreatedAt() == null) {
        run.setCreatedAt(now);
    }
    run.setUpdatedAt(now);

    String sql = """
        INSERT INTO calculator_runs (
            run_id, calculator_id, calculator_name, tenant_id, frequency, reporting_date,
            start_time, end_time, duration_ms,
            status, sla_time, expected_duration_ms,
            estimated_start_time, estimated_end_time,
            sla_band, sla_breached, sla_breach_reason,
            run_number, run_type, region, correlation_id,
            run_parameters, additional_attributes,
            created_at, updated_at
        ) VALUES (
            :runId, :calculatorId, :calculatorName, :tenantId, :frequency, :reportingDate,
            :startTime, :endTime, :durationMs,
            :status, :slaTime, :expectedDurationMs,
            :estimatedStartTime, :estimatedEndTime,
            :slaBand, :slaBreached, :slaBreachReason,
            :runNumber, :runType, :region, :correlationId,
            :runParameters, :additionalAttributes,
            :createdAt, :updatedAt
        )
        ON CONFLICT (run_id, reporting_date) DO NOTHING
        RETURNING *
        """;

    Timer.Sample sample = Timer.start(meterRegistry);
    List<CalculatorRun> results = jdbcTemplate.query(sql, insertParams(run), new CalculatorRunRowMapper(true));
    sample.stop(Timer.builder(DB_QUERY_DURATION).tag("query", "insert_if_absent").register(meterRegistry));

    CalculatorRun saved = DataAccessUtils.singleResult(results);
    if (saved != null) {
        try {
            redisCache.trackRunningState(saved);
        } catch (Exception cacheEx) {
            log.warn("event=cache.write outcome=failure run_id={} error={}", saved.getRunId(), cacheEx.getMessage(), cacheEx);
        }
    }
    return Optional.ofNullable(saved);
}
```

Run: `mvn test -Dtest=CalculatorRunRepositoryJdbcTest` → PASS.

**Step 3: Failing service test** (`RunIngestionServiceTest`):

```java
@Test
void startRun_concurrentDuplicate_returnsExistingWithoutEvents() {
    when(runRepository.findById(anyString(), any())).thenReturn(Optional.empty());   // race: not visible yet
    when(runRepository.insertIfAbsent(any())).thenReturn(Optional.empty());          // conflict lost
    CalculatorRun existing = TestFixtures.runningRun("run-1", REPORTING_DATE);
    // second lookup after the conflict sees the winner's row
    when(runRepository.findById(eq("run-1"), eq(REPORTING_DATE)))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(existing));

    var outcome = service.startRun(startRequest("run-1"), TENANT);

    assertThat(outcome.created()).isFalse();
    assertThat(outcome.run()).isSameAs(existing);
    verify(eventPublisher, never()).publishEvent(any(RunStartedEvent.class));
    verify(slaMonitoringCache, never()).registerForSlaMonitoring(any());
}
```

Run: `mvn test -Dtest=RunIngestionServiceTest` → FAIL/COMPILE ERROR.

**Step 4: Service change** — in `doStartRun`, replace `run = runRepository.upsert(run);` with:

```java
Optional<CalculatorRun> inserted = runRepository.insertIfAbsent(run);
if (inserted.isEmpty()) {
    // Lost a concurrent-duplicate race after the existence check — same semantics as the
    // fast-path replay above: return the winner's row, publish nothing.
    lifecycleLogger.emit(LifecycleEvent.RUN_START_REJECTED, kv("reason", "duplicate_race"));
    meterRegistry.counter(INGESTION_RUN_DUPLICATE, "phase", "start").increment();
    CalculatorRun existing = runRepository
            .findById(request.getRunId(), request.getReportingDate())
            .orElseThrow(() -> new IllegalStateException(
                    "Run disappeared after insert conflict: " + request.getRunId()));
    return new StartRunOutcome(existing, false);
}
run = inserted.get();
```

The rest of the method (SLA registration, `RunStartedEvent`, counters) is now reachable only on genuine creation. `completeRun` keeps using `upsert` unchanged.

**Step 5: Run tests**

Run: `mvn test -Dtest=RunIngestionServiceTest,CalculatorRunRepositoryJdbcTest,RunIngestionControllerTest` → PASS.

**Step 6: Commit**

```bash
git add src/main/java/com/company/observability/repository/CalculatorRunRepository.java \
        src/main/java/com/company/observability/service/RunIngestionService.java \
        src/test/java/com/company/observability/repository/CalculatorRunRepositoryJdbcTest.java \
        src/test/java/com/company/observability/service/RunIngestionServiceTest.java
git commit -m "fix: atomic run start via ON CONFLICT DO NOTHING; single event per created run (H3)"
```

---

### Task P1.7: SUCCESS-only SLA baselines in the nightly aggregate (MAJOR H4)

**Defect:** `recomputeForDateRange` filters only `end_time IS NOT NULL` ([DailyAggregateRepository.java:84](../../src/main/java/com/company/observability/repository/DailyAggregateRepository.java)) — FAILED/TIMEOUT/CANCELLED durations pollute `sum_duration_ms`, while the tier-2 fallback `findRecentExact` already filters `status = 'SUCCESS'` (line 445). Failed runs are typically short-circuits → baselines skew low → false-tight SLA fallback deadlines and wrong estimates.

**Pre-check (do first):** `grep -rn "findRecentAggregates\|findByReportingDates" src/main/java` — confirm these two aggregate readers have no production callers (believed dead since `/runtime`,`/sla-summary`,`/trends` were removed). If dead, LIST them for the user and ask approval to delete (pre-existing code — core-behaviors rule 6); do not delete unilaterally. The filter change below is safe regardless because profiles are the only live reader.

**Files:**
- Modify: `src/main/java/com/company/observability/repository/DailyAggregateRepository.java`
- Test: `src/test/java/com/company/observability/repository/DailyAggregateRepositoryJdbcTest.java` (extend)

**Step 1: Failing test**

```java
@Test
void recompute_excludesNonSuccessRunsFromBaselineSums() {
    insertCompletedRun("ok-run", CALC, DATE, RunStatus.SUCCESS, 100_000L);
    insertCompletedRun("failed-run", CALC, DATE, RunStatus.FAILED, 5_000L);   // short-circuit failure

    repository.recomputeForDateRange(DATE, DATE, Frequency.DAILY);

    CalculatorProfile profile = repository.findProfile(CALC, "DAILY", 30);
    assertThat(profile.totalRuns()).isEqualTo(1);
    assertThat(profile.avgDurationMs()).isEqualTo(100_000L);
}
```

Run: `mvn test -Dtest=DailyAggregateRepositoryJdbcTest` → FAIL (totalRuns=2, avg=52500).

**Step 2: Implement** — in the `recomputeForDateRange` INSERT's WHERE clause:

```sql
WHERE end_time IS NOT NULL
  AND status = 'SUCCESS'
  AND frequency = :frequency
  AND reporting_date BETWEEN :from AND :to
```

Update the method javadoc: *"Only SUCCESS completions feed the aggregate — it exists solely to supply CalculatorProfile baselines/estimates, and failed runs' (typically short-circuited) durations poison the averages. Aligned with findRecentExact's tier-2 filter. success_runs is therefore equal to total_runs; failure analytics must read calculator_runs directly."*

**Step 3: Run tests**

Run: `mvn test -Dtest=DailyAggregateRepositoryJdbcTest,DailyAggregationJobTest,CalculatorProfileServiceTest` → PASS (fix any fixture that relied on failed runs being counted).

**Step 4: Commit**

```bash
git add src/main/java/com/company/observability/repository/DailyAggregateRepository.java \
        src/test/java/com/company/observability/repository/DailyAggregateRepositoryJdbcTest.java
git commit -m "fix: SLA baseline aggregate counts SUCCESS runs only, aligned with tier-2 (H4)"
```

---

### Phase 1 checkpoint

- Run: `SPRING_PROFILES_ACTIVE=local mvn clean test` → full suite green.
- REQUIRED SUB-SKILL: superpowers:requesting-code-review on the Phase-1 diff before starting Phase 2.
- Update `tasks/todo.md` checkboxes.

---

## Phase 2 — Remaining code & config issues

### Task P2.1: Circular mean for start/end minute-of-day (H5 — midnight wraparound)

**Defect:** `sum_start_min_utc` linearly averages minute-of-day; a calculator straddling UTC midnight (23:50, 00:10) averages to ~12:00 → NOT_STARTED estimates are hours wrong. Fix with a circular mean: aggregate SIN/COS component sums; derive the mean angle at read time. Keeps "minute of day" semantics so all profile consumers stay unchanged.

**Files:**
- Create: `src/main/resources/db/migration/V13__sli_daily_circular_minutes.sql`
- Modify: `src/main/java/com/company/observability/repository/DailyAggregateRepository.java` (recompute INSERT + all 6 profile queries + `findRecentExact`)
- Modify: `src/main/java/com/company/observability/domain/CalculatorProfile.java` (`fromSums`)
- Test: `src/test/java/com/company/observability/repository/DailyAggregateRepositoryJdbcTest.java`

**Step 1: Failing test**

```java
@Test
void profile_averagesStartMinutesAcrossMidnightCorrectly() {
    insertSuccessRunStartingAt(CALC, DATE, "23:50");
    insertSuccessRunStartingAt(CALC, DATE.plusDays(1), "00:10");

    repository.recomputeForDateRange(DATE, DATE.plusDays(1), Frequency.DAILY);
    CalculatorProfile profile = repository.findProfile(CALC, "DAILY", 30);

    // Circular mean of 23:50 and 00:10 is 00:00 (1440-min clock), not 12:00
    assertThat(profile.avgStartMinUtc()).isIn(0, 1440 % 1440);
}
```

**Step 2: Migration**

```sql
-- V13__sli_daily_circular_minutes.sql
-- Minute-of-day is a cyclic quantity: linear averaging breaks across UTC midnight
-- (23:50 & 00:10 must average to 00:00, not 12:00). Store unit-circle component sums;
-- the mean angle is recovered at read time via atan2. Legacy linear sums are kept as a
-- fallback until the nightly recompute has repopulated the lookback window.
ALTER TABLE calculator_sli_daily
    ADD COLUMN IF NOT EXISTS sum_start_sin DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS sum_start_cos DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS sum_end_sin   DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS sum_end_cos   DOUBLE PRECISION NOT NULL DEFAULT 0;
```

**Step 3: Recompute INSERT** — add four expressions (and matching column list entries); the minute expression is the existing one, abbreviated here as `start_min` / `end_min`:

```sql
COALESCE(SUM(SIN(2 * PI() * (<start_min>) / 1440.0)), 0),
COALESCE(SUM(COS(2 * PI() * (<start_min>) / 1440.0)), 0),
COALESCE(SUM(CASE WHEN end_time IS NOT NULL THEN SIN(2 * PI() * (<end_min>) / 1440.0) ELSE 0 END), 0),
COALESCE(SUM(CASE WHEN end_time IS NOT NULL THEN COS(2 * PI() * (<end_min>) / 1440.0) ELSE 0 END), 0),
```

**Step 4: Read path** — every profile query (`findProfile`, `findAllProfiles`, `findProfileByRunNumber`, `findAllProfilesByRunNumber`, `findProfileByRunNumberAndDimension`, `findAllProfilesByRunNumberAndDimension`) additionally selects `SUM(sum_start_sin) …` (4 columns), and `findRecentExact` computes `SUM(SIN(…))/SUM(COS(…))` directly from timestamps. Extend `CalculatorProfile.fromSums` with the four sums and compute:

```java
private static int circularMeanMinute(double sumSin, double sumCos, long linearSum, int totalRuns) {
    if (sumSin == 0.0 && sumCos == 0.0) {
        // Legacy rows (pre-V13) — fall back to the linear average until recompute catches up.
        return totalRuns > 0 ? (int) (linearSum / totalRuns) : 0;
    }
    double angle = Math.atan2(sumSin, sumCos);
    if (angle < 0) angle += 2 * Math.PI;
    return (int) Math.round(angle / (2 * Math.PI) * 1440) % 1440;
}
```

**Step 5: Run tests** — `mvn test -Dtest=DailyAggregateRepositoryJdbcTest,CalculatorProfileServiceTest,DailyAggregationJobTest` → PASS. Then full suite.

**Step 6: Commit** — `fix: circular mean for profile start/end minutes (midnight wraparound)`

**Step 7: Retiring `sum_start_min_utc`/`sum_end_min_utc`**

`ADD COLUMN ... DEFAULT 0` does not backfill existing rows, and the nightly job only recomputes a narrow trailing window (`observability.aggregation.recompute-window`: 7d DAILY / 20d MONTHLY — sized to completion lag) that is much shorter than the profile read lookback (`observability.sla.lookback`: 30d DAILY / 395d MONTHLY — sized to SLA baseline relevance). Left alone, MONTHLY rows aged 21–395 days keep `sum_start_sin = sum_start_cos = 0` indefinitely under normal operation — they're inside the read lookback but outside the automatic recompute window — so profiles silently run on a ~20-day sample instead of the intended ~13-month one, and the legacy columns can't be dropped on any near-term timeline. Two options:

- **Option 1 — passive:** Do nothing; let stale rows age out of the lookback window on their own. Zero extra work, but MONTHLY profiles are degraded for up to ~13 months, and `sum_start_min_utc`/`sum_end_min_utc` must stay until that full window has elapsed.
- **Option 2 — active backfill (recommended):** Immediately after this task ships, drive the existing admin endpoint `POST /api/v1/admin/aggregation/recompute?from=...&to=...` (`AdminAggregationController`, wraps `DailyAggregationJob.recomputeRange`) to force-populate the new sin/cos columns across the *full* MONTHLY lookback, not just the trailing recompute window.

  **Endpoint facts that shape how to call it:**
  - Requires the **ADMIN** role, not the default app user — `/api/v1/admin/**` is `hasRole("ADMIN")` in `BasicSecurityConfig`. Default creds are `ops`/`ops` (`observability.security.admin.username` / `...password`), distinct from the regular `admin`/`admin` app user.
  - One call recomputes **both DAILY and MONTHLY** over the same `[from, to]` range (`recomputeRange` calls `recomputeForDateRange` for each frequency with identical bounds) — no separate DAILY call is needed; the first backfill chunk already covers the 30-day DAILY lookback.
  - `to` is optional and defaults to today; `from` is required.
  - Server-enforced cap: `MAX_SPAN_DAYS = 800`, so a single `from=today-395d&to=today` call is technically *allowed* — chunking below is a self-imposed operational choice to bound per-call cost against the ~395 MONTHLY partitions (TD-8), not an API requirement.

  **Exact steps:**
  1. Deploy the P2.1 migration + code (Steps 1–6) first — the endpoint recomputes from `calculator_runs`/writes `calculator_sli_daily` using whatever columns exist at call time.
  2. Add a counter/log on the `circularMeanMinute` legacy-fallback branch (`sumSin == 0 && sumCos == 0`) *before* backfilling, so "safe to drop" is later confirmed by observed evidence (fallback stopped firing), not a calendar guess.
  3. Run the backfill in ~30-day chunks covering the full 395-day MONTHLY lookback, oldest first, during low-traffic hours:
     ```bash
     today=$(date -u +%F)
     from=$(date -u -d "$today - 395 days" +%F)
     while [ "$from" \< "$today" ]; do
         to=$(date -u -d "$from + 30 days" +%F)
         [ "$to" \> "$today" ] && to="$today"
         curl -u ops:ops -X POST \
             "https://<host>/api/v1/admin/aggregation/recompute?from=${from}&to=${to}"
         from=$(date -u -d "$to + 1 day" +%F)
     done
     ```
     Each response is `{"from", "to", "rowsRecomputed", "profilesWarmed"}` — confirm `rowsRecomputed > 0` per chunk (0 across a chunk with known historical runs signals a problem, not "nothing to do").
  4. Once the fallback counter from step 2 confirms zero hits for at least one full nightly cycle, ship a follow-up migration (`DROP COLUMN sum_start_min_utc, sum_end_min_utc`) and remove `linearSum`/`totalRuns` from `CalculatorProfile.fromSums`/`circularMeanMinute` and the corresponding SELECT/INSERT columns.

---

### Task P2.2: 400 (not 500) for query-param type mismatches

**Files:** `GlobalExceptionHandler.java`, `GlobalExceptionHandlerTest.java`

Failing test: MockMvc-style or direct-invoke test asserting `handleTypeMismatch` returns 400 with a message naming the parameter. Then add:

```java
@ExceptionHandler(MethodArgumentTypeMismatchException.class)
public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
    HttpStatus status = HttpStatus.BAD_REQUEST;
    meterRegistry.counter(API_ERROR, "exception", ex.getClass().getSimpleName(), "status", String.valueOf(status.value())).increment();
    String message = "Invalid value '" + ex.getValue() + "' for parameter '" + ex.getName() + "'";
    log.warn("event=api.error status={} exception={} message={}", status.value(), ex.getClass().getSimpleName(), message);
    return buildErrorResponse(status, message);
}
```

(import `org.springframework.web.method.annotation.MethodArgumentTypeMismatchException`). Verify manually: `GET /api/v1/calculators/batch/runs?reporting_date=not-a-date&keys=x` → 400.
Commit: `fix: map MethodArgumentTypeMismatchException to 400`

### Task P2.3: Security/actuator hardening

**Files:** `BasicSecurityConfig.java`, `application.yml`

1. Permit K8s probes: add `"/actuator/health/**"` to the `permitAll` matchers (liveness/readiness currently get 401 with `probes.enabled: true`). Keep `/actuator/prometheus` authenticated; document scrape credentials in README.
2. Stateless API: `.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))` — stops per-request `JSESSIONID` session creation.
3. `application.yml`: `management.endpoint.health.show-details: when-authorized` (currently `always`).
4. TD-7 (`{noop}` passwords) stays open — needs a secret-management decision, out of scope; note in commit body.

Test: extend existing security-adjacent tests or add a `@WebMvcTest`-style check that `/actuator/health` is reachable unauthenticated. Full suite green.
Commit: `fix: permit health probes, stateless sessions, gate health details`

### Task P2.4: `getState` ordered result + duplicate-safe collector

**Files:** `CalculatorStateService.java:98-102`, `CalculatorStateServiceTest.java`

Failing test: request names `["b","a"]`, assert iteration order `b,a`. Fix:

```java
return calculatorNames.stream().collect(Collectors.toMap(
        name -> name, cached::get, (a, b) -> a, LinkedHashMap::new));
```

Commit: `fix: preserve requested calculator order in getState result`

### Task P2.5: Batch Redis round-trips (MGET / HMGET)

**Files:** `CalculatorStateCacheService.java` (`getEntries` → single `opsForValue().multiGet(keys)`, zip results with names), `SlaMonitoringCache.java` (`getBreachedRuns`/`getApproachingSlaRuns` → one `opsForHash().multiGet(SLA_RUN_INFO_HASH, new ArrayList<>(runKeys))` instead of per-key `get`). Keep per-entry deserialization failures degrading to a miss (existing behavior).

Tests: `CalculatorStateCacheServiceTest` + `SlaMonitoringCacheIntegrationTest` must stay green; add one test asserting a multiGet with a null slot (expired key) is treated as a miss.
Commit: `perf: batch Redis reads for state cache and SLA monitoring`

### Task P2.6: `obs:running` gauge correctness

**Files:** `RedisCalculatorCache.java`, `RedisCalculatorCacheTest.java`, `RedisCalculatorCacheIntegrationTest.java`

Defects: member key `calculatorId:frequency` collapses parallel regional runs — the first completing region removes the member while nine still run; the 2h whole-set `expire` silently drops long runs; Redis counts calc-pairs while the DB fallback counts rows.

Fix: member key `calculatorId + ":" + frequency + ":" + runId` (counts runs, consistent with the DB fallback), sliding TTL bumped to 24h. Failing test: track two RUNNING runs of the same calculator, complete one, assert `getRunningCalculators()` still has one member.
Commit: `fix: per-run members in obs:running so parallel splits don't vanish from the gauge`

### Task P2.7: DST-safe overnight roll in `clockTimeDeadline`

**Files:** `TimeUtils.java:89-97`, `TimeUtilsTest.java`

`deadline.plus(Duration.ofDays(1))` adds a fixed 24h — across a DST transition the local cutoff shifts by an hour. Failing test: zone `Europe/Amsterdam`, start `2026-03-28T22:00Z` (23:00 CET, night of spring-forward), cutoff `06:00` → expect `2026-03-29T04:00Z` (06:00 CEST), not `05:00Z`. Fix:

```java
public static Instant clockTimeDeadline(Instant startTime, LocalTime slaClockTime, ZoneId zone) {
    if (startTime == null || slaClockTime == null || zone == null) return null;
    ZonedDateTime deadline = ZonedDateTime.of(startTime.atZone(zone).toLocalDate(), slaClockTime, zone);
    if (!deadline.toInstant().isAfter(startTime)) {
        deadline = deadline.plusDays(1);   // calendar day in-zone, not fixed 24h
    }
    return deadline.toInstant();
}
```

Note: `nextBusinessDay` still has no holiday calendar — known limitation, needs a business-owned calendar source; do not invent one (YAGNI).
Commit: `fix: DST-correct overnight roll for clock-time SLA deadlines`

### Task P2.8: Async executor resilience + dead yml removal

**Files:** `AsyncConfig.java`, `application.yml`

The `taskExecutor` @Bean shadows the entire `spring.task.execution.*` yml block (dead config), and the default AbortPolicy drops AFTER_COMMIT listeners (alert persistence, cache evictions) when the 100-slot queue fills; nothing waits for in-flight listeners on shutdown.

```java
executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
executor.setWaitForTasksToCompleteOnShutdown(true);
executor.setAwaitTerminationSeconds(20);
```

Delete the `spring.task.execution` block from `application.yml` (keep `spring.task.scheduling`). Full suite green.
Commit: `fix: caller-runs backpressure + graceful shutdown for async listeners`

### Task P2.9: Config truth-restoration (Redis props, prod logging, dead config, live-SLA warning)

**Files:** `RedisCacheConfig.java`, `application.yml`, `application-local.yml`, `application-dev.yml`, `application-prod.yml`, `pom.xml`, `SlaProperties.java`, `RunIngestionService.java`, `SlaMonitoringCache.java`

1. **RedisCacheConfig ignores Spring config:** it reads `System.getenv()` directly, so `spring.data.redis.*` (host/port/password/timeout/pool) is silently dead. Inject `RedisProperties` and build the factory from it (`props.getHost()`, `getPort()`, `getPassword()`, `getTimeout()` for `commandTimeout`). Delete the unused `lettuce.pool` yml block (requires commons-pool2 anyway) or add the dependency — delete, YAGNI.
2. **Logging:** move the noisy levels (`org.springframework.jdbc: DEBUG`, `io.lettuce.core: DEBUG`, `org.springframework.cache*: TRACE`, `org.flywaydb: DEBUG`, `com.company.observability: DEBUG`) OUT of base `application.yml` into `application-local.yml`; base stays INFO. Remove `org.hibernate.SQL` from `application-dev.yml` and `spring.jpa.show-sql` from `application-prod.yml` (TD-10 — no JPA exists).
3. **Boot-3 key:** replace `management.metrics.export.prometheus.enabled` with `management.prometheus.metrics.export.enabled` (old key silently ignored since Boot 3.0).
4. **Dead dependency:** remove `spring-boot-starter-cache` + the `spring.cache.*` yml block — no `@Cacheable`/`CacheManager` usage anywhere (verify first: `grep -rn "Cacheable\|CacheManager\|@EnableCaching" src/main/java` must return nothing).
5. **Live-SLA flag hygiene (user: intentionally off):** move `liveTrackingEnabled` reads into `SlaProperties` (single source; today `RunIngestionService` and `SlaMonitoringCache` each re-read the `@Value`), and log one startup WARN when disabled: `"Live SLA breach detection is DISABLED (observability.sla.live-tracking.enabled=false) — hung runs will not breach until completion"`. Do NOT change the flag values.

Verification: `SPRING_PROFILES_ACTIVE=local mvn spring-boot:run` boots; Redis connects using yml values (change `spring.data.redis.port` to a wrong port locally and confirm the connection now fails — proves the properties are honored); `/actuator/prometheus` still serves metrics.
Commit: `fix: honor spring.data.redis properties; quiet prod logging; remove dead config`

### Task P2.10: Query-path efficiency

**Files:** `CalculatorRunRepository.java`, new `src/main/resources/db/migration/V14__running_partial_index.sql`

1. `findAllRunsByDateAndDimension` uses `SELECT *` but maps with `includeJsonb=false` — JSONB fetched and discarded on the dashboard hot path. Replace `SELECT *` with the existing `SELECT_BASE` column list.
2. Partial index for the sweep/count paths (`findOverdueRunningRuns`, `countRunning` — currently seq-scan per partition):

```sql
-- V14__running_partial_index.sql
-- findOverdueRunningRuns / countRunning filter status='RUNNING' over recent partitions;
-- RUNNING rows are a tiny fraction, so a partial index keeps these sweeps index-only.
CREATE INDEX IF NOT EXISTS calculator_runs_running_partial_idx
    ON calculator_runs (reporting_date)
    WHERE status = 'RUNNING';
```

Tests: existing `CalculatorRunRepositoryJdbcTest`/`CalculatorRunRepositoryDimensionalTest` green; optionally assert `EXPLAIN` uses the index in a JDBC test (skip if brittle).
Commit: `perf: trim dashboard query columns; partial index for RUNNING sweeps`

### Task P2.11: Honest `UNGRADED` SLA status ⚠️ needs consumer sign-off

**Files:** `AnalyticsService.java:206-209`, `CalculatorStateService.java:358`, `docs/consumer-api.md`

Terminal runs with a null band (no derivable deadline) are currently reported `slaStatus: "ON_TIME"`, inflating compliance. Change both mappings to emit `"UNGRADED"` and exclude UNGRADED from `slaMetCount` in `buildRunPerformanceDataEnvelope`. **This is an API contract change** — consumer-api.md documents `slaStatus ∈ {ON_TIME, LATE, VERY_LATE}`. Gate: get dashboard-team sign-off before merging; if declined, fall back to adding an `slaGraded: boolean` field instead. Update consumer-api.md either way.
Commit: `feat: report UNGRADED instead of ON_TIME for runs without a derivable SLA`

### Task P2.12: Small fixes bundle

**Files:** `RunIngestionController.java`, `StartRunRequest.java`, `CalculatorNameResolver.java`, `LiveSlaBreachDetectionJob.java`, `AlertHandlerService.java`, `ExpectedRunsService.java`

1. **Location header encoding:** `URI.create("/api/v1/runs/" + runId)` throws on reserved chars → `UriComponentsBuilder.fromPath("/api/v1/runs/{id}").buildAndExpand(runId).toUri()`.
2. **`@Size` validation** on `StartRunRequest` matching column widths (`runId`/`calculatorId` ≤ 100, `calculatorName` ≤ 255, `runNumber` ≤ 10, `runType`/`region` ≤ 20, `correlationId` ≤ 100 — confirm against V2/V6) so oversize input gets a clean 400 instead of a raw PG error.
3. **Reverse-alias map:** `findAliasFor` linearly scans every alias per event; precompute `Map<String,String> realToAlias` in `CalculatorNameResolver` (constructor or `@PostConstruct`).
4. **Clock injection:** replace `Instant.now()` with the `ClockConfig` clock in `LiveSlaBreachDetectionJob` (`buildBreachReason`, `determineBand`, `getBreachedRuns` boundary is in `SlaMonitoringCache` — inject there too) and `AlertHandlerService.createdAt` — consistent with `CalculatorStateService`/`ExpectedRunsService`, and makes SLA-timing tests deterministic.

One failing test per change where practical (URI encoding and `@Size` at minimum). Full suite green.
Commit: `fix: URI encoding, request size validation, reverse-alias map, clock injection`

### Task P2.13: Dead-code audit (report, then delete only with approval)

**Files:** none yet — this task PRODUCES a list.

Run: `grep -rn "<method>" src/main/java src/test/java` for each suspect and record callers:
- `SlaBreachEventRepository`: `countByBand`, `countByType`, `findWorstDayHealthByDay`, `findByCalculatorIdPaginated`, `findByCalculatorIdKeyset`, `countByCalculatorIdAndPeriod`, `findUnalertedBreaches` (orphans of removed endpoints; several also join `calculator_runs` on `run_id` alone — TD-1-style partition scans — and `findUnalertedBreaches` excludes `RETRYING`, TD-4)
- `DailyAggregateRepository`: `findRecentAggregates`, `findByReportingDates` (see P1.7 pre-check)
- `CalculatorRunRepository.findRunsWithSlaStatus` (both overloads), `getPartitionStatistics` (Java caller — the job queries SQL directly)
- `Frequency.lookbackDays/getLookbackDuration` (TD-6), `TimeUtils.calculateSlaDeadline`, `RunPerformanceData` unused fields
- `cleanup_expired_idempotency_keys()` PL/pgSQL function (TD-2) — proposal: drop via migration

Present the list to the user with **"Should I remove these now-unused elements: […]?"** (core-behaviors rule 6). Delete only what's approved; one commit.
Commit: `chore: remove dead code (approved list)`

### Task P2.14: Documentation alignment

**Files:** `docs/architecture.md`, `CLAUDE.md`, `docs/consumer-api.md`, `README.md`

- `architecture.md` describes removed components (ProjectionController, DashboardService, RegionalBatchService, CacheWarmingService, CacheEvictionService, RunStatusClassifier, "running-average upsert on every completion", `RunQueryService`) — rewrite the stale sections to match the current controller/service/listener set (use CLAUDE.md's architecture section as the accurate baseline).
- `CLAUDE.md`: terminal-clean state-cache TTL says "4h", code is 1h (`CalculatorStateCacheService.TTL_TERMINAL_CLEAN`) — fix to 1h (or bump the constant to 4h if that was the intent — ask user); partition-job description becomes true after P1.1 (update wording: creation 1 AM daily, drop weekly Sun 2 AM, monitor 6 AM); note live-SLA defaults are `false` by config in all environments (intentional).
- `consumer-api.md`: UNGRADED (per P2.11 outcome); note `Cache-Control: max-age=5` for live responses (doc only shows 30).
- `README.md`: Prometheus scrape + probe auth notes from P2.3.

No tests; proofread. Commit: `docs: align architecture/CLAUDE/consumer docs with current code`

---

## Final verification (after each phase)

1. `docker compose up -d`
2. `SPRING_PROFILES_ACTIVE=local mvn clean test` → green
3. Boot the app (`SPRING_PROFILES_ACTIVE=local mvn spring-boot:run`) and exercise end-to-end:
   - `POST /api/v1/runs/start` (new run → 201; identical replay → 200; two parallel identical starts via `xargs -P2` → exactly one 201)
   - `POST /api/v1/runs/{id}/complete` → 200; repeat → 200; different status → 409
   - `GET /api/v1/calculators/batch/runs?...` and `GET /api/v1/analytics/calculators/{name}/executions?...` → sane payloads
   - `POST /api/v1/admin/partitions/ensure?from=2024-01-01&to=2024-01-05` (admin creds) → `partitionsCreated: 5`; then start a run with `reporting_date=2024-01-03` → 201 (backfill proof)
   - `psql`: `SELECT * FROM shedlock;` shows lock rows after a scheduled tick
4. `GET /actuator/health` unauthenticated → 200 (P2.3)
5. Update `tasks/todo.md` checkboxes and its Review section.
