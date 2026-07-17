# Task: Production Hardening — Phased Remediation

**Plan:** `docs/plans/2026-07-13-production-hardening-plan.md` (full detail: files, code, tests, commits per task)
**Source:** 2026-07-13 architecture review. Decisions: multi-replica AKS deployment (locking is mandatory); live SLA detection stays OFF (intentional).

---

## Phase 1 — Critical & Major

- [ ] P1.1 Runtime partition creation + retention jobs (`PartitionManagementJob`) — fixes ingestion hard-fail ~60d after deploy (C1)
- [ ] P1.2 V10 partition range function + `POST /api/v1/admin/partitions/ensure` for Airflow backfills (C1)
- [ ] P1.3 ShedLock on all `@Scheduled` jobs (pom + V11 + `SchedulingConfig` + annotations) (C2)
- [ ] P1.4 `AlertHandlerService`: drop REQUIRES_NEW, stop rethrow-after-markFailed — breach record must survive sender failure (H1)
- [ ] P1.5 V12: `sla_breach_events` UNIQUE NULLS NOT DISTINCT `(run_id, reporting_date)` (H2)
- [ ] P1.6 Atomic `startRun` via `insertIfAbsent` (ON CONFLICT DO NOTHING RETURNING) — no double events/201s (H3)
- [ ] P1.7 Nightly aggregate counts SUCCESS runs only (baseline data quality, aligns tier-1/tier-2) (H4)
- [ ] Phase-1 checkpoint: full suite green + code review of the diff

## Phase 2 — Remaining issues

- [ ] P2.1 Circular mean for profile start/end minutes (V13) — midnight wraparound (H5)
- [ ] P2.2 `MethodArgumentTypeMismatchException` → 400 (was 500)
- [ ] P2.3 Permit `/actuator/health/**`, STATELESS sessions, `show-details: when-authorized`
- [ ] P2.4 `getState`: LinkedHashMap collector (ordered, duplicate-safe)
- [ ] P2.5 Batch Redis reads (MGET state cache, HMGET SLA monitoring)
- [ ] P2.6 `obs:running`: per-run members + 24h TTL (parallel splits no longer vanish)
- [ ] P2.7 DST-safe overnight roll in `TimeUtils.clockTimeDeadline`
- [ ] P2.8 Async executor: CallerRunsPolicy + graceful shutdown; delete dead `spring.task.execution` yml
- [ ] P2.9 Config truth: `RedisCacheConfig` honors `spring.data.redis.*`; prod logging quiet; Boot-3 prometheus key; remove starter-cache; live-SLA startup WARN + flag consolidation into `SlaProperties`
- [ ] P2.10 `SELECT_BASE` in `findAllRunsByDateAndDimension`; V14 partial index for RUNNING sweeps
- [ ] P2.11 `UNGRADED` sla status ⚠️ consumer sign-off required before merge
- [ ] P2.12 Small fixes: Location-header URI encoding, `@Size` validation, reverse-alias map, Clock injection
- [ ] P2.13 Dead-code audit → present list → delete only approved items
- [ ] P2.14 Docs: architecture.md rewrite, CLAUDE.md TTL/partition/live-SLA corrections, consumer-api.md, README

---

## Review

_(to be filled after implementation)_

---

## Previously completed

- [x] Simplify runNumber-aware declaration (Map → flat list) — verified implemented: `application.yml` has `run-number-aware: [capital, portfolio]`; `CalculatorNameResolver.isRunNumberAware` uses `getRunNumberAware()`.

---

# Task: Effective run_number normalization for synthetic NOT_STARTED entries

**Plan:** `docs/plans/2026-07-17-effective-run-number-normalization.md`

- [x] Task 1 — failing test (`notStartedEntry_agnosticCalculator_ignoresRunNumberFilter`) added, confirmed compile error
- [x] Task 2 — `CalculatorNameResolver` injected into `CalculatorStateService`, confirmed test moved to real assertion failure
- [x] Task 3 — `effRn` normalization applied in `buildNotStartedEntry`; new test passes; full `CalculatorStateServiceTest` (25 tests) passes
- [x] Task 4 — `RunQueryControllerTest` coverage for RUN_TYPE placeholders keeping `sla` when queried with `run_number`; full class (20 tests) passes
- [x] Task 5 — full suite + manual verification (partial — see below)

## Review

Implemented exactly as planned: `buildNotStartedEntry` now computes `String effRn = nameResolver.isRunNumberAware(name) ? runNumber : null;` and uses `effRn` for both the latest-run lookup and the WP11 unknown-run-number guard, mirroring the existing idiom in `CalculatorProfileService.getProfile` and `RunQueryController.mergeEntries`. No repository, schema, or config changes.

Full suite (`mvn test`, no `SPRING_PROFILES_ACTIVE=local`): **451 tests, 449 passed, 2 errors, 34 skipped.** The 2 errors are both in `CalculatorRunRepositoryDimensionalTest` (`@SpringBootTest` context load failure) — Docker/Rancher Desktop was not running locally, so there was no live Postgres for that DB-backed integration suite to connect to. This is an environment limitation, not a regression: every test that doesn't require a live DB passed, including all four suites the plan calls out as touching the same run_number semantics (`CalculatorProfileServiceTest`, `ExpectedRunsServiceTest`, `RunQueryControllerTest`, `CalculatorStateServiceTest`).

**Not done:** Task 5 Step 2 (manual `curl` verification against a running app) requires the same Docker infra and was skipped by explicit user decision rather than run once Docker was available. If `marketriskrwacalcdev`/`modelledexposurecalcdev`/`geminihedgefundcalcdev` still show the reported symptom in a live environment after this deploys, re-run the three `curl` checks in the plan's Task 5 (`nocache=true` required — the empty-entry TTL is 60s) to confirm end-to-end before treating this as fully closed.

No corrections were needed mid-implementation; nothing to add to `tasks/lessons.md`.
