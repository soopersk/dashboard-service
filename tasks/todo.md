# Task: Fix Ingestion-Path (Phase 1) + Independent Query-Path (Phase 2) Review Findings

Plan: `C:\Users\SOOPER\.claude\plans\create-a-separate-detailed-dynamic-spark.md` (approved).
Deferred: 1.3-A/1.3-B (doc-only), 2.3-A event-driven state-cache invalidation (follow-up plan).

## Checklist

- [ ] WP1 — `findByIdForUpdate` + rework `doCompleteRun` (on-write grading authoritative; clear false-positive live breaches; after-commit Redis deregister)
- [ ] WP2 — DB fallback sweep: `findOverdueRunningRuns()` + `sweepOverdueRunsFromDb()` (interval `observability.sla.live-detection.db-sweep-interval-ms`, default 120000)
- [ ] WP3 — Drop job-level `@Transactional`; per-run `TransactionTemplate` (events still AFTER_COMMIT); deregister only after successful tx
- [ ] WP4 — Scoped profiles at ingestion: rn-scoped for SLA baseline, dim-scoped for estimates
- [ ] WP5 — Strict `Frequency` JSON binding (+ `HttpMessageNotReadableException` → 400), reject non-EOM MONTHLY, normalize numeric run_number
- [ ] WP6 — Remove structure-level TTL on `obs:sla:deadlines` / `obs:sla:run_info`
- [ ] WP7 — 200 on duplicate `/start` (201 only when created), 409 on conflicting `/complete`, consistent tenant check
- [ ] WP8 — `clockTimeDeadlineUtc(ZoneId)` for MONTHLY clock specs (resolver + projection)
- [ ] WP9 — `ExpectedRunsService.pad()` never drops real runs (template filter, undeclared dims appended)
- [ ] WP10 — run_number in sequential-dedup group key; truthful `isRerun`
- [ ] WP11 — Suppress NOT_STARTED projection when rn-scoped history is empty
- [ ] WP12 — Bound `findLatestRunEstimatesByName` by `slaProperties.lookbackDays(frequency)`
- [ ] Tests for all WPs
- [ ] Verify: `SPRING_PROFILES_ACTIVE=local mvn clean test` (needs Docker)

## Review

(to be filled at completion)
