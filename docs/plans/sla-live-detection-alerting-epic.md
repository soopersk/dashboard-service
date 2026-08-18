# GitLab Epic + Issues — SLA Live Detection, Breach Tracking & Alerting

Copy-paste source for GitLab. Epic first, then issues in implementation priority order
(each issue is a shippable, demoable slice — build top to bottom).

---

## EPIC

**Title:**
```
SLA Live Detection, Breach Tracking & Alerting
```

**Description:**
```markdown
## Summary
End-to-end SLA breach detection for calculator runs: derive a frozen deadline per run,
grade actual outcomes against it (on completion, and — for still-running runs — live via
Redis), persist breach records, and alert on breach.

## Approach
Built incrementally. Each child issue ships a working, demoable slice. Later issues never
block earlier ones — on-write (completion-time) grading ships and works standalone before
live (real-time, concurrent) detection is added on top of it.

## Scope
DAILY and MONTHLY calculator runs.

## Explicitly out of scope (do not build speculatively)
- Alert retry queue / backoff for failed alert delivery
- External alert channels (Slack / email / PagerDuty) — ship log-only first
- Multi-tenant query filtering on breach data
- DB sweep backstop (Issue 6) until live detection (Issue 5) has run in a real
  environment long enough to justify it

## Definition of done (epic)
- A run that breaches its SLA — whether caught on completion or while still running —
  produces exactly one breach record and exactly one alert.
- No breach is ever recorded twice for the same run, and no false-positive live breach
  survives past the run's actual completion grade.
```

**Labels:** `~epic` `~sla` `~observability`

---

## ISSUE 1 — Derive and freeze SLA deadline at run start

**Title:**
```
SLA: derive and freeze deadline at run start (SlaBaselineResolver)
```

**Description:**
```markdown
Implement deadline derivation from the self-describing `slaTime` spec on
`POST /runs/start` and freeze the result onto the run. No grading, no live detection,
no alerting yet — this issue only computes and persists the deadline.

### Spec forms to support
| Form | DAILY | MONTHLY |
|---|---|---|
| `T+N@HH:mm` | `nextBusinessDay(reportingDate, N)` at `HH:mm` in `slaTimezone` | rejected (400) |
| `HH:mm` (bare clock) | offset from `parseRunNumber(runNumber)` (run 1→T+1, run 2→T+2, null/invalid→T+2) | clock-time deadline off `startTime`'s date, rolled +1 day if at/before startTime |
| `PT2H30M` (ISO-8601 duration) | `startTime + duration×(1+thresholdPercent/100) + lateBand` | same |
| blank/null | fallback chain: `expectedDurationMs` → profile avg → ungraded | same |

### Dependencies
None — first issue in the epic.
```

**Acceptance Criteria:**
```markdown
- [ ] Migration adds `sla_time TIMESTAMPTZ` to `calculator_runs`
- [ ] `SlaBaselineResolver` resolves all four spec forms above, correctly branched by frequency
- [ ] MONTHLY + `T+N@HH:mm` is rejected with 400 (DomainValidationException)
- [ ] MONTHLY bare-clock deadline uses a zone-aware overnight roll (explicit `ZoneId`, not server-local)
- [ ] `sla_time` is persisted on `/runs/start` and excluded from the `ON CONFLICT UPDATE` clause (immutable after first insert)
- [ ] Raw spec string is logged at ingestion (`event=run.start.persist slaSpec=…`) but not persisted as a column
- [ ] Unit tests cover each spec form × DAILY/MONTHLY, including invalid combinations and the blank/null fallback chain
- [ ] No behavior change to grading, live detection, or alerting — this issue is deadline computation only
```

**Labels:** `~backend` `~sla` `~database`
**Suggested weight:** 3

---

## ISSUE 2 — Grade run outcome against SLA deadline on completion

**Title:**
```
SLA: grade completed runs against frozen deadline (SlaEvaluationService)
```

**Description:**
```markdown
On `POST /runs/{runId}/complete`, compare actual duration to the frozen `sla_time`
and classify the run's timing. This is the first working breach *signal* in the
system — synchronous, deterministic, no background jobs or infrastructure required.

### Classification
- `actual <= lateEdgeMs` → `ON_TIME` (no breach)
- `actual <= veryLateEdgeMs` → `LATE` (breach)
- `actual > veryLateEdgeMs` → `VERY_LATE` (breach)
- `sla_time` / `startTime` / `durationMs` null → ungraded (null band, no breach)

Failure is a separate dimension: `FAILED` / `TIMEOUT` status is always a breach,
independent of timing band.

### Dependencies
Requires Issue 1 (`sla_time` must exist and be frozen).
```

**Acceptance Criteria:**
```markdown
- [ ] Migration adds `sla_band`, `sla_breached`, `sla_breach_reason` to `calculator_runs`
- [ ] `SlaEvaluationService.evaluateSla` implements the classification above
- [ ] `lateEdgeMs` = duration between `startTime` and `sla_time`; `veryLateEdgeMs` = `lateEdgeMs + bandGapMs`
- [ ] `FAILED` / `TIMEOUT` runs are marked breached regardless of timing band
- [ ] `sla_breached` is persisted on every `/complete` call (including idempotent replays — no re-grading on replay)
- [ ] Unit tests: ON_TIME, LATE, VERY_LATE, FAILED, TIMEOUT, and ungraded (null deadline/duration) cases
- [ ] `/batch/runs` and `/executions` responses expose the new band/breach fields (no separate issue needed if these endpoints already surface raw run columns)
```

**Labels:** `~backend` `~sla` `~database`
**Suggested weight:** 3

---

## ISSUE 3 — Persist breach events on completion-triggered breach

**Title:**
```
SLA: persist breach audit record on completion breach (sla_breach_events)
```

**Description:**
```markdown
Create the `sla_breach_events` table and the event plumbing that turns "a completed
run graded as breached" into a durable audit record. Alert delivery itself is stubbed
out to the next issue — this issue only proves the trigger → persist path.

### Schema (deliberately minimal)
Only columns something in this issue or Issue 4 actually reads/writes. Do not add
`retry_count`, `last_error`, `expected_value`, `actual_value`, or an `alerted` boolean
speculatively — add them in a future issue if and when a feature needs them.

```sql
CREATE TABLE sla_breach_events (
    breach_id       BIGSERIAL       PRIMARY KEY,
    run_id          VARCHAR(100)    NOT NULL UNIQUE,
    calculator_id   VARCHAR(100)    NOT NULL,
    calculator_name VARCHAR(255)    NOT NULL,
    tenant_id       VARCHAR(50),
    reporting_date  DATE,
    breach_type     VARCHAR(50)     NOT NULL,
    alert_status    VARCHAR(20)     NOT NULL DEFAULT 'PENDING'
                    CHECK (alert_status IN ('PENDING', 'SENT', 'FAILED')),
    alerted_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);
```

### Components to build
Two distinct types are involved — don't conflate them:

| Type | Package | Lifetime | Purpose |
|---|---|---|---|
| `SlaBreachedEvent` | `event` | transient, in-memory only | Spring application event carrying `CalculatorRun` + `SlaEvaluationResult`; published `AFTER_COMMIT` |
| `SlaBreachEvent` | `domain` | persisted | 1:1 row model for `sla_breach_events`, built from the above and saved by the repository |

- **`SlaBreachEvent`** (domain model) — fields matching the table columns 1:1
- **`BreachType`** enum — `FAILED`, `TIMEOUT`, `TIME_EXCEEDED`, `UNKNOWN` (only the first three are reachable from this issue; `UNKNOWN` is a defensive default)
- **`AlertStatus`** enum — `PENDING`, `SENT`, `FAILED` (matches the CHECK constraint above — no `RETRYING` yet, that's backlog)
- **`SlaBreachEventRepository`** — `save()` only for this issue (INSERT via `NamedParameterJdbcTemplate` + `RowMapper`); lets `DuplicateKeyException` propagate to the caller rather than swallowing it — the listener decides how to handle a duplicate, not the repository
- **`AlertHandlerService.handleSlaBreachEvent(SlaBreachedEvent)`** — the listener that turns the Spring event into a persisted `SlaBreachEvent` row

### Dependencies
Requires Issue 2 (needs a breach signal to trigger on).
```

**Acceptance Criteria:**
```markdown
- [ ] Migration creates `sla_breach_events` with the minimal column set above
- [ ] `run_id UNIQUE` constraint in place — this is the idempotency guard, required before Issue 5 adds a second (racing) trigger source
- [ ] Domain model `SlaBreachEvent` (`com.company.observability.domain`) with fields matching the table 1:1
- [ ] `BreachType` enum (`FAILED`, `TIMEOUT`, `TIME_EXCEEDED`, `UNKNOWN`) and `AlertStatus` enum (`PENDING`, `SENT`, `FAILED`)
- [ ] `SlaBreachEventRepository.save()` — INSERT + `RowMapper`, propagates `DuplicateKeyException` to the caller unhandled
- [ ] Application event `SlaBreachedEvent` (`com.company.observability.event`) published `AFTER_COMMIT` when a completion is newly breached (not on idempotent replay, not on an already-breached run)
- [ ] `AlertHandlerService.handleSlaBreachEvent(SlaBreachedEvent)` — `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async` + `@Transactional(propagation = REQUIRES_NEW)`, so the listener's write runs in its own transaction, not the completion transaction
- [ ] `determineBreachType(run)` implemented: `FAILED` status → `BreachType.FAILED`; `TIMEOUT` status → `BreachType.TIMEOUT`; breached timing band (`LATE`/`VERY_LATE`) → `BreachType.TIME_EXCEEDED`
- [ ] Listener catches `DuplicateKeyException` from `save()`, logs it, increments a `duplicate` counter, and returns without throwing — a second publish for the same `run_id` must not fail the async listener
- [ ] Integration test: complete a run past its deadline → exactly one `sla_breach_events` row, correct `breach_type`; completing it again (replay) does not add a second row
```

**Labels:** `~backend` `~sla` `~database`
**Suggested weight:** 5

---

## ISSUE 4 — Alert delivery on breach

**Title:**
```
SLA: log-only alert delivery on breach (AlertSender)
```

**Description:**
```markdown
Define the alert delivery seam and ship the simplest possible implementation — a
structured log line. Real external channels (Slack/email/PagerDuty) are a separate,
later decision and out of scope here.

### Dependencies
Requires Issue 3 (needs a persisted breach row to send an alert for).
```

**Acceptance Criteria:**
```markdown
- [ ] `AlertSender` interface: `send(SlaBreachEvent)`, `channelName()`
- [ ] `StructuredLogAlertSender` emits a structured warning log with calculator, breach type, and tenant context
- [ ] On send success: `alert_status = SENT`, `alerted_at` set, row updated
- [ ] On send failure: `alert_status = FAILED`, row updated — no retry loop (explicitly out of scope for this issue)
- [ ] Metrics: counters for breach created / alert sent / alert failed
- [ ] Test: a forced sender failure results in `FAILED` status and does not throw out of the run-completion transaction
```

**Labels:** `~backend` `~sla` `~alerting`
**Suggested weight:** 2

---

## ISSUE 5 — Live SLA breach detection for still-running runs

**Title:**
```
SLA: live breach detection for still-running runs (Redis + scheduled job)
```

**Description:**
```markdown
Everything so far only detects a breach when a run completes. This issue adds
real-time detection for runs that are still `RUNNING` past their deadline — the
concurrency-sensitive part of the feature, so it's built last and on top of an
already-proven completion path.

### Design constraints
- A run must never be double-breached by the live poller and `/complete` racing each other.
- `/complete`'s on-write grade is authoritative and must be able to supersede a live-set
  band (clear a false positive if the run actually finished before the deadline, or
  upgrade a stale live `LATE` to `VERY_LATE`).

### Dependencies
Requires Issues 1–4 (deadline, grading, persistence, and alerting must all already work
for the completion-triggered path).
```

**Acceptance Criteria:**
```markdown
- [ ] `SlaMonitoringCache`: register `RUNNING` run with a frozen deadline into Redis at start (sorted set by deadline + hash of run metadata); deregister on completion, after commit
- [ ] Scheduled job polls Redis every N seconds (configurable, default 15s) for runs past deadline
- [ ] Breach write is a **conditional UPDATE** (`WHERE sla_band IS NULL AND status = 'RUNNING'`) — the idempotency guard against a race with `/complete`
- [ ] `/complete` takes a row lock (`FOR UPDATE`) so its on-write grade is authoritative over any live-set band
- [ ] Each run's breach write + event publish runs in its own short transaction, isolated from the rest of the polling batch (one bad run can't roll back others)
- [ ] Reuses the Issue 3/4 persistence + alert pipeline — no separate alert path for live breaches
- [ ] `determineBreachType` extended with a still-running branch: `endTime == null` → `BreachType.TIME_EXCEEDED` (distinct from the completion-time branches added in Issue 3, which all assume `endTime` is set)
- [ ] Feature flag: `observability.sla.live-tracking.enabled` (default `true`)
- [ ] Integration test: run breaches live, then completes early with an on-time actual duration → final band reflects the real outcome, not the live guess
- [ ] Integration test: run breaches live and stays running for the rest of the test window → breach + alert fire exactly once, not once per poll cycle
```

**Labels:** `~backend` `~sla` `~redis` `~concurrency`
**Suggested weight:** 8

---

## ISSUE 6 — DB sweep backstop for missed live detections (defer until Issue 5 is observed in production)

**Title:**
```
SLA: DB sweep backstop for breaches Redis missed
```

**Description:**
```markdown
Backstop for breaches the Redis-based live detection could miss: registration failure
at start, a Redis outage, or evicted/flushed cache entries. Queries `calculator_runs`
directly on a slower interval and reuses the same conditional-update + event path as
Issue 5.

**Do not start this issue until Issue 5 has been running in a real environment long
enough to demonstrate Redis actually loses entries.** Building it earlier is a guess at
a failure mode that may not be worth the complexity.

### Dependencies
Requires Issue 5.
```

**Acceptance Criteria:**
```markdown
- [ ] `findOverdueRunningRuns()` query: `status = 'RUNNING' AND sla_time < now`
- [ ] Supporting partial index: `(status, sla_time) WHERE status = 'RUNNING'`
- [ ] Sweep runs on its own, slower schedule (default 120s), independent of the fast Redis path
- [ ] Sweep reuses `markBreachAndPublish`-equivalent logic from Issue 5 — same conditional update, same event, same alert pipeline
- [ ] Sweep clears any stale Redis entry for a run it breaches
- [ ] Test: a run breached only via simulated Redis data loss (entry manually removed) is still caught by the sweep within one interval
```

**Labels:** `~backend` `~sla` `~resilience`
**Suggested weight:** 3

---

## Backlog (documented decision, not planned)

**Alert retry queue** — `retry_count` / `last_error` / a scheduled retry job for failed
alert deliveries. Do not build until a real delivery channel exists that can fail
transiently (a log line effectively never does). If this becomes needed later, open a
new issue rather than adding the columns speculatively now.
