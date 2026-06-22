# EPIC — Integration Testing: Observability Service ↔ Capital Calculation Dashboard UI

**Labels:** `epic` · `integration-testing` · `observability` · `dashboard-ui` · `regulatory-capital`
**Target milestone:** _TBD_
**Owners:** QA / Dashboard squad

---

## 1. Summary

The observability service exposes two REST endpoints that drive the Capital Calculation Dashboard UI:

| # | Endpoint | Purpose | Consumed by |
|---|----------|---------|-------------|
| 1 | **Dashboard endpoint** | Calculator run information for all sections (status, SLA, timings, per-region / per-run-type breakdown) | Dashboard grid (all sections) |
| 2 | **Performance card endpoint** | Per-calculator performance card (metrics/timings for a single calculator or region run) | Expanded performance card per calculator |

The UI is divided into four sections, each with a distinct shape that must be exercised independently:

| Section | Shape | SLA flag (example) |
|---------|-------|--------------------|
| **Regional** | N subsections (calculators); each subsection renders a **10-region matrix** (WMAP, WMDE, ASIA, WMUS, AUNZ, WMCH, ZURI, LDNL, AMER, EURO) | 15:00 |
| **Portfolio** | List of **single-status** calculators (e.g. 0/6); depends on Regional completion | 17:00 |
| **Group portfolio** | List of **single-status** calculators (mirrors Portfolio) | _TBD_ |
| **Risk governed** | 2 calculators (Modelled Exposure, Gemini Hedge); each renders a **3-run-type matrix** (ETD, OTC, SFT) | 19:30 |

This epic delivers **end-to-end integration testing** between both endpoints and the Dashboard UI, with full coverage of SLA breaches, reruns, failures, dependencies and edge cases.

## 2. Goals

- Verify the Dashboard UI correctly consumes, maps and renders every field of both endpoints for every section.
- Validate status, SLA/lateness and roll-up logic against endpoint payloads (catch UI-vs-service computation mismatches).
- Prove correct behaviour across the full lifecycle: not started → in progress → completed / failed, including reruns.
- Cover all SLA states (on time / late / very late), all failure modes, partial completion and upstream dependencies.
- Confirm resilient behaviour for degraded endpoints (errors, timeouts, partial/missing/null data) and for live refresh/polling.

## 3. Out of scope

- Unit testing of internal service logic and of isolated UI components (covered elsewhere).
- Load / performance / soak testing (tracked separately).
- Authentication / authorization hardening (separate security epic).
- Correctness of the underlying capital calculations themselves (data quality is owned by the calc teams).

## 4. Shared state model (reference for all issues)

**Calculator status:** `Not started` · `In progress` · `Completed` · `Failed`

**SLA badge:** `On time` · `Late {m}m` · `Very late {m}m` · `Very late {h}h {m}m`
- Late→Very-late threshold is **unconfirmed** (observed: 14m = late, 38m = very late). Treat as a test target.

**Regional matrix icon (per region) / Run-type icon (per ETD·OTC·SFT):**

| Icon | Meaning |
|------|---------|
| Green check | Success / completed |
| Amber clock | Running — late |
| Red clock | Running — very late |
| Red X | Failed |
| Empty circle | Not started |

**Section header:** `X/Y Completed` + section SLA badge + flag deadline + optional dependency note.

**Key derivation rule to verify:** a subsection/section can be `Failed` even when most regions are green (a single failed region fails the calculator — observed on *Eligible TLAC* where LDNL\* failed while 8 regions succeeded).

## 5. Definition of Done (epic)

- [ ] All child issues closed.
- [ ] Every section verified against both endpoints across happy path + all edge cases below.
- [ ] SLA / lateness / roll-up logic validated (UI matches endpoint contract).
- [ ] Reruns, failures, partial completion and dependency chains covered.
- [ ] Degraded-endpoint and refresh/polling behaviour verified.
- [ ] Automated integration suite added to CI and green; coverage report attached.
- [ ] Defects raised, triaged and either fixed or accepted with sign-off.

## 6. Child issues & relationships

| # | Issue | Depends on |
|---|-------|-----------|
| 1 | Foundation — test harness, endpoint contracts & shared rendering | — |
| 2 | Regional — section aggregation & SLA roll-up | 1 |
| 3 | Regional — per-region run-status matrix & tooltips | 1 |
| 4 | Regional — SLA breaches, failures & reruns | 1, 2, 3 |
| 5 | Portfolio — section integration & upstream dependency | 1 |
| 6 | Group portfolio — section integration | 1, 5 |
| 7 | Risk governed — Modelled Exposure & Gemini Hedge (ETD/OTC/SFT) | 1 |

---

# ISSUE 1 — Foundation: test harness, endpoint contracts & shared rendering

**Labels:** `integration-testing` · `foundation` · `contract-test`
**Blocks:** Issues 2–7

### Objective
Stand up the shared integration-test foundation that every section issue builds on: a controllable mock/stub of the observability service for both endpoints, contract validation, and tests for the rendering elements common to all sections (status badge, SLA badge, time fields, performance card shell, loading/empty/error states, refresh).

### Scope
- **In:** mock service, contract tests for both endpoints, shared component rendering, error/empty/loading, timezone, polling/refresh.
- **Out:** section-specific layouts (matrix, single-status list, run-type matrix) — owned by their issues.

### Test data / preconditions
- Mock observability service capable of returning scripted payloads, error codes, latency and malformed bodies for a configurable **business date**.
- Reference fixtures for each calculator status and SLA badge state.

### Test scenarios
1. **Dashboard endpoint contract** — schema/field-presence validation: section id, calculator id/name, status, SLA badge type + value, flag deadline, start/est-start, end/est-end, started/ended, duration, dependency note, region list & per-region status, run-type list & per-run-type status. Unknown fields ignored gracefully; missing required fields surfaced (not silently dropped).
2. **Performance-card endpoint contract** — schema/field-presence for the per-calculator card (metrics, timings, status, region/run-type identifier). Card request keyed correctly to the selected calculator/region.
3. **Status badge rendering** — each of `Not started / In progress / Completed / Failed` maps to the correct label, colour and icon.
4. **SLA badge rendering** — `On time`, `Late {m}m`, `Very late {m}m`, `Very late {h}h {m}m` render correctly; verify the late→very-late threshold boundary (e.g. 14m vs 38m, and the exact cutoff once confirmed).
5. **Time-field rendering** — start/end vs est-start/est-end vs started/ended chosen correctly per status; date+time format; **timezone** (`CET` in tooltips) consistent and correct.
6. **Loading state** — skeleton/spinner while endpoint pending.
7. **Empty state** — endpoint returns no calculators / empty section.
8. **Error states** — 400, 401/403, 404, 500, 503 and network timeout each produce a clear UI error/fallback without crashing the dashboard; one section's failure does not break the others.
9. **Malformed / null / partial payload** — null timestamps, missing badge, partial region list → graceful degradation, no console crash.
10. **Refresh / polling** — UI re-fetches on the configured interval; in-flight → next-poll transitions update without fl: stale data replaced, no duplicate rows.
11. **Business-date selection** — switching business date re-queries both endpoints and rebuilds all sections.

### Acceptance criteria
- [ ] Mock service supports scripted payloads, errors, latency, malformed bodies, per business date.
- [ ] Both endpoint contracts validated; breaking-change test fails on schema drift.
- [ ] Shared badge/time/card/loading/empty/error rendering verified per the state model.
- [ ] Timezone, refresh/polling and business-date switching verified.
- [ ] Suite runs in CI and is green.

---

# ISSUE 2 — Regional: section aggregation & SLA roll-up

**Labels:** `integration-testing` · `section:regional`
**Depends on:** Issue 1

### Objective
Verify the Regional **section header and subsection roll-up** correctly reflect the dashboard endpoint: `X/6 Completed`, section SLA status, the 15:00 flag deadline, and per-subsection (calculator) status/SLA — independent of the per-region matrix detail (Issue 3).

### Scope
- **In:** section header, `X/Y Completed` counter, section-level SLA badge, flag deadline, subsection list, per-subsection status + SLA badge + timings, ordering, expand/collapse.
- **Out:** per-region icons/tooltips (Issue 3); breach/failure/rerun lifecycle (Issue 4).

### Test data / preconditions
- Payload reproducing the captured state: 6 subsections (Credit commitments & contingent claims; Credit risk migration; Credit risk (loans); Eligible TLAC; Equity investments in funds; Failed trades & unsettled spot transactions), `3/6 Completed`, section `Very late`, flag `15:00`.

### Test scenarios
1. **Completed counter** — `X/6 Completed` matches the count of `Completed` subsections; recompute for 0/6, partial, 6/6.
2. **Section SLA roll-up** — section badge (`On time / Late / Very late`) derives from the worst subsection state and the 15:00 flag; verify the boundary at exactly 15:00.
3. **Flag deadline** — 15:00 rendered from payload, not hard-coded.
4. **Per-subsection status** — `In progress`, `Completed`, `Failed` each render correctly for the right subsection.
5. **Per-subsection SLA badge** — `Late 14m`, `Very late 38m`, `Very late 1h 58m`, `On time` map to the correct subsection.
6. **Timings per status** — `In progress` shows Start + Est. end; `Completed` shows Started + Ended; `Failed` shows Started + Ended; correct field selection per status.
7. **Subsection ordering & count** — order and number of subsections match payload; adding/removing a subsection reflows correctly.
8. **Expand/collapse** — section and subsection chevrons expand/collapse without losing or re-ordering data.
9. **Performance card (per calculator)** — opening a subsection's performance card requests the performance-card endpoint for that calculator and renders its metrics; verify for in-progress, completed and failed calculators.
10. **Edge:** all subsections `Completed` and on time → section `On time`, `6/6`; all `Not started` → `0/6`.

### Acceptance criteria
- [ ] Header counter, section SLA badge and flag verified against payload across 0/6, partial, 6/6.
- [ ] Per-subsection status/SLA/timings verified for in-progress, completed and failed.
- [ ] Roll-up derivation (worst-state + flag) validated, including the SLA boundary.
- [ ] Performance card verified for each subsection state.

---

# ISSUE 3 — Regional: per-region run-status matrix & tooltips

**Labels:** `integration-testing` · `section:regional` · `region-matrix`
**Depends on:** Issue 1

### Objective
Verify the **10-region status matrix** rendered for each Regional subsection: every region maps to the correct column and icon, tooltips carry correct timing data, special markers render, and mixed-state rows are handled.

### Scope
- **In:** the 10 region columns (WMAP, WMDE, ASIA, WMUS, AUNZ, WMCH, ZURI, LDNL, AMER, EURO), per-region icon state, tooltip content, special markers (e.g. `LDNL*`), per-region performance card.
- **Out:** lifecycle transitions / reruns (Issue 4); section roll-up (Issue 2).

### Test data / preconditions
- Payload reproducing the *Eligible TLAC* matrix: WMAP ✓, WMDE ✓, ASIA ✓, WMUS ✓, AUNZ red-clock, WMCH amber-clock, ZURI ✓, LDNL\* red-X (failed), AMER ✓, EURO ✓; LDNL tooltip = Run failed, 13:02 CET → 14:58 CET, 1h 56m.

### Test scenarios
1. **Column mapping** — all 10 regions render in the fixed expected order; each region's status binds to the correct column (no off-by-one).
2. **Icon state per region** — success (green check), running-late (amber clock), running-very-late (red clock), failed (red X), not started (empty circle) each render for the correct region.
3. **Tooltip content** — hover shows status + Start time + End time + Duration with correct values and timezone (e.g. LDNL: Run failed / 13:02 CET / 14:58 CET / 1h 56m).
4. **Special marker** — `LDNL*` asterisk/marker renders and its meaning (e.g. footnote/special region) is exposed; verify the marker comes from payload.
5. **Mixed-state row** — a single subsection with a blend of success / late / very-late / failed / not-started renders all icons correctly side by side.
6. **All-success row** — 10/10 green renders with no false warnings.
7. **All-failed / all-not-started rows** — render correctly.
8. **Missing region in payload** — fewer than 10 regions returned → UI handles gracefully (placeholder / not-started) without shifting other columns.
9. **Unknown/extra region** — region not in the expected 10 is handled per contract (ignored or flagged), no layout break.
10. **Per-region performance card** — clicking a region opens the performance-card endpoint for `{calculator, region}`; verify card content for success, late and failed regions; verify failed-region card shows failure detail.
11. **Tooltip for in-progress region** — running region tooltip shows Start + Est. end + elapsed/expected duration (no end time yet).

### Acceptance criteria
- [ ] All 10 regions map to correct columns with correct icons across all states.
- [ ] Tooltips show correct Start/End/Duration + timezone for success, late, failed and in-progress regions.
- [ ] `LDNL*` special marker verified.
- [ ] Mixed, all-success, all-failed, all-not-started and missing/extra-region cases handled.
- [ ] Per-region performance card verified.

---

# ISSUE 4 — Regional: SLA breaches, failures & reruns (lifecycle)

**Labels:** `integration-testing` · `section:regional` · `sla` · `failure` · `rerun`
**Depends on:** Issues 1, 2, 3

### Objective
Exercise the **dynamic lifecycle** of Regional calculators end-to-end: SLA breach progression, failures (region and calculator level), reruns after failure, partial completion, and live in-progress updates — the highest-risk Regional behaviour.

### Scope
- **In:** state transitions, SLA late/very-late progression, single- and multi-region failures, calculator-level failure derivation, reruns and their effect on status/SLA/timings/duration, partial completion, live refresh during in-progress.
- **Out:** static matrix mapping (Issue 3); header counter rendering (Issue 2).

### Test scenarios
1. **Lifecycle transition** — drive a region/calculator through `Not started → In progress → Completed`; UI updates on each poll without duplicate rows or stale icons.
2. **Failure transition** — `In progress → Failed` (red X); calculator badge flips to `Failed`; tooltip shows failure timings.
3. **Failed-calculator derivation** — a single failed region forces the calculator/subsection to `Failed` even with other regions green (reproduce *Eligible TLAC*: 8 green + 1 late + 1 amber + LDNL failed → subsection `Failed`).
4. **Multi-region failure** — 2+ regions failed; calculator `Failed`; all failed icons + tooltips correct.
5. **SLA progression: on time → late** — region/calculator crosses the late threshold; badge changes `On time → Late {m}m` and clock icon turns amber.
6. **SLA progression: late → very late** — crosses the very-late threshold; badge `Late → Very late`, clock turns red; **validate the exact threshold boundary** (observed 14m late, 38m very late).
7. **Very-late hours formatting** — `Very late 1h 58m` style renders correctly past 60 minutes.
8. **Rerun after failure** — a failed regional calculator is rerun: status returns to `In progress` then `Completed`/`Failed`; **verify SLA recomputation, new Start/End and Duration**, and that the `X/6 Completed` counter and section roll-up update accordingly.
9. **Partial rerun (single region)** — rerun of one failed region while others stay completed; only that region's icon/timings change; calculator status re-derives.
10. **Rerun still breaches SLA** — rerun completes after the 15:00 flag → remains `Very late`; verify lateness is measured against the flag, not the rerun start.
11. **Partial completion mid-run** — `3/6 Completed` while others in progress/late; counter and section SLA reflect real-time mix.
12. **Est. vs actual end** — while in progress, Est. end shown; on completion, actual Ended replaces it; verify no lingering estimate.
13. **Failure → performance card** — failed region/calculator performance card surfaces failure reason/metrics (ties to Issue 3 #10 for the failed path).
14. **Degraded endpoint mid-lifecycle** — endpoint errors during an in-progress run → UI keeps last-known state and shows refresh error, then recovers on next successful poll.

### Acceptance criteria
- [ ] Full not-started→in-progress→completed/failed transitions verified via live refresh.
- [ ] Single- and multi-region failure → calculator `Failed` derivation verified.
- [ ] SLA late and very-late progression + threshold boundary verified.
- [ ] Reruns (full and single-region) verified: status, SLA, timings, duration and counter all recompute correctly, including reruns that still breach SLA.
- [ ] Partial completion and est-vs-actual end verified.
- [ ] Failure performance card and degraded-endpoint recovery verified.

---

# ISSUE 5 — Portfolio: section integration & upstream dependency

**Labels:** `integration-testing` · `section:portfolio` · `dependency` · `sla`
**Depends on:** Issue 1

### Objective
Verify the **Portfolio** section: a list of **single-status** calculators (no region matrix), the `0/6 Completed` counter, section SLA + 17:00 flag, the **dependency on Regional completion**, and projected-lateness for not-yet-started calculators.

### Scope
- **In:** single-status calculator rows, `X/6` counter, section SLA badge + 17:00 flag, dependency note ("Dependent on regional calculators completion"), not-started lateness projection, est-start/est-end timings, lifecycle to completion, per-calculator performance card.
- **Out:** region matrix (Regional only); risk-governed run types.

### Test data / preconditions
- Payload reproducing capture: `0/6 Completed`, section `Late`, note "Dependent on regional calculators completion", flag `17:00`; calculators Business indicator component, Counterparty credit risk on derivatives and SFTs, Credit Valuation Adjustments, Leverage Ratio Denominator, Securitization (+1 not shown) all `Not started`, `Late 8m`, Est. start 16:02 / Est. end 17:05.

### Test scenarios
1. **Single-status rendering** — each calculator shows exactly one status badge (no 10-region matrix); confirms Portfolio shape differs from Regional.
2. **Counter** — `X/6 Completed` matches completed calculators; verify 0/6, partial, 6/6. Confirm the 6th (off-screen) calculator is present in payload and rendered.
3. **Section SLA + flag** — section `Late` derives from calculators + 17:00 flag; 17:00 rendered from payload.
4. **Dependency note** — "Dependent on regional calculators completion" renders when present; verify behaviour when Regional is incomplete vs complete (does the note clear / do calculators start?).
5. **Not-started lateness projection** — `Late 8m` for not-started calculators is projected from Est. start vs expected start; verify the projection math and that it updates as time/dependency changes. (Note the Est-end 17:05 vs flag 17:00 vs badge 8m relationship — confirm the lateness reference.)
6. **Est. timings** — Est. start 16:02 / Est. end 17:05 render for not-started; switch to actual Started/Ended on run.
7. **Lifecycle** — drive a Portfolio calculator `Not started → In progress → Completed/Failed`; counter and section SLA update.
8. **Dependency gating** — Portfolio calculators remain `Not started` until upstream Regional completes; once Regional completes, verify they transition (or that the UI reflects readiness).
9. **Failure** — a Portfolio calculator fails → `Failed` badge; section SLA reflects it.
10. **Performance card (per calculator)** — performance-card endpoint requested per Portfolio calculator; verify for not-started, in-progress, completed and failed.
11. **Edge:** Regional incomplete while Portfolio flag (17:00) passes → Portfolio `Very late` purely from blocked dependency.

### Acceptance criteria
- [ ] Single-status rows, counter, section SLA and 17:00 flag verified against payload.
- [ ] Dependency note + dependency gating behaviour verified (Regional incomplete vs complete).
- [ ] Not-started lateness projection and est-vs-actual timings verified.
- [ ] Lifecycle, failure and per-calculator performance card verified.

---

# ISSUE 6 — Group portfolio: section integration

**Labels:** `integration-testing` · `section:group-portfolio` · `dependency` · `sla`
**Depends on:** Issues 1, 5

> **Assumption (confirm before starting):** no screenshot was provided for Group portfolio. It is modelled as **structurally identical to Portfolio** — a list of single-status calculators, its own `X/Y Completed` counter, its own SLA flag, and an upstream dependency. Confirm the calculator list, flag time and dependency source with the product owner; adjust scenarios if it differs.

### Objective
Verify the **Group portfolio** section using the same single-status pattern as Portfolio, against its own dashboard-endpoint payload and SLA flag, including its dependency chain (likely on Regional and/or Portfolio).

### Scope
- **In:** single-status calculator rows, counter, section SLA + flag, dependency note + gating, not-started projection, timings, lifecycle, per-calculator performance card.
- **Out:** confirming the business-level calculator list (a precondition, not a test).

### Test scenarios
1. **Confirm structure** — verify payload returns single-status calculators (mirrors Portfolio); flag a defect if shape differs from the contract.
2. **Counter** — `X/Y Completed` matches completed calculators (0/Y, partial, Y/Y).
3. **Section SLA + flag** — section badge derives from calculators + Group-portfolio flag; flag rendered from payload.
4. **Dependency note + gating** — dependency note renders; calculators stay `Not started` until upstream completes, then transition; verify against the confirmed dependency source.
5. **Not-started projection & timings** — projected lateness and est-start/est-end verified; switch to actual on run.
6. **Lifecycle & failure** — `Not started → In progress → Completed/Failed`; counter and SLA update; failure → `Failed`.
7. **Performance card (per calculator)** — performance-card endpoint per calculator across all states.
8. **Edge:** upstream incomplete past the Group-portfolio flag → section `Very late` from blocked dependency.
9. **Regression vs Portfolio** — confirm Group portfolio and Portfolio render independently (no cross-section data bleed; correct section ids).

### Acceptance criteria
- [ ] Group-portfolio structure, calculator list, counter, SLA and flag confirmed and verified against payload.
- [ ] Dependency note + gating verified against the confirmed upstream source.
- [ ] Lifecycle, failure, projection, timings and per-calculator performance card verified.
- [ ] No cross-section data bleed with Portfolio.

---

# ISSUE 7 — Risk governed: Modelled Exposure & Gemini Hedge (ETD / OTC / SFT)

**Labels:** `integration-testing` · `section:risk-governed` · `run-types` · `sla`
**Depends on:** Issue 1

### Objective
Verify the **Risk governed** section: two calculators (**Modelled Exposure**, **Gemini Hedge**), each rendering a **3-run-type matrix** (ETD, OTC, SFT), the `0/2 Completed` counter, the 19:30 flag, sequential timings/dependency between the two calculators, and per-run-type lifecycle/failure.

### Scope
- **In:** 2-calculator section, `X/2` counter, section SLA + 19:30 flag, per-calculator status + est-start/est-end, the ETD/OTC/SFT run-type matrix per calculator, per-run-type icon state, run-type lifecycle/failure, sequential timing between the two calculators, per-run-type performance card.
- **Out:** region matrix; single-status portfolio rows.

### Test data / preconditions
- Payload reproducing capture: section `0/2 Completed`, flag `19:30`; Modelled exposure `Not started`, Est. start 18:02 / Est. end 18:58, run types ETD/OTC/SFT all not started; Gemini hedge `Not started`, Est. start 19:02 / Est. end 19:28, ETD/OTC/SFT all not started.

### Test scenarios
1. **Two-calculator layout** — exactly two calculators render (Modelled Exposure, Gemini Hedge), each with its own status, timings and run-type matrix.
2. **Counter** — `X/2 Completed` (0/2, 1/2, 2/2) matches completed calculators.
3. **Section SLA + flag** — section badge derives from the two calculators + 19:30 flag; flag rendered from payload; verify boundary at 19:30.
4. **Run-type columns** — ETD, OTC, SFT render in expected order per calculator; each run-type binds to its correct column (no cross-mapping between calculators).
5. **Run-type icon states** — success / running-late (amber) / running-very-late (red) / failed / not-started each render for the correct run type.
6. **Per-calculator timings** — Modelled exposure Est. start 18:02 / Est. end 18:58; Gemini hedge Est. start 19:02 / Est. end 19:28; est→actual swap on run.
7. **Sequential dependency** — Gemini hedge est-start (19:02) follows Modelled exposure est-end (18:58); verify Gemini hedge stays `Not started` until Modelled exposure completes (if dependency exists), and that a delay in the first shifts/pressures the second's SLA.
8. **Run-type lifecycle** — drive ETD/OTC/SFT through `Not started → In progress → Completed/Failed` per calculator; calculator status derives from its run types.
9. **Run-type failure derivation** — a single failed run type (e.g. OTC failed) forces the calculator to `Failed` while others may be green (parallels the Regional region-failure rule).
10. **Mixed run-type row** — ETD success / OTC failed / SFT in-progress within one calculator renders correctly.
11. **SLA breach** — calculator/section crosses late then very-late thresholds; badges and icons update; verify against 19:30 flag.
12. **Rerun** — rerun a failed run type / calculator; status, SLA and timings recompute; counter updates.
13. **Per-run-type performance card** — performance-card endpoint requested per `{calculator, run-type}`; verify for success, in-progress and failed run types.
14. **Edge:** Modelled exposure delayed past 18:58 pushing Gemini hedge past 19:30 → section `Very late` from cascaded delay.

### Acceptance criteria
- [ ] Two-calculator layout, `X/2` counter, section SLA and 19:30 flag verified against payload.
- [ ] ETD/OTC/SFT columns map correctly per calculator with correct icon states.
- [ ] Per-calculator timings, est→actual swap and sequential dependency verified.
- [ ] Run-type lifecycle, single-run-type failure derivation, mixed rows, SLA breach and rerun verified.
- [ ] Per-run-type performance card verified.

---

## Appendix — cross-issue edge-case checklist (apply to every section)

- [ ] Happy path (all on time / completed / green)
- [ ] SLA `Late` and `Very late` (single item, multiple items, whole section) + threshold boundary
- [ ] Failure: single item, multiple items, item-failure forces calculator `Failed`
- [ ] Rerun: full and partial; SLA/timings/duration/counter recompute; rerun that still breaches SLA
- [ ] Partial completion counter accuracy
- [ ] Not started + upstream dependency gating + projected lateness
- [ ] In-progress live refresh / polling; est→actual end swap
- [ ] Tooltip / performance-card timing + timezone (CET) correctness
- [ ] Endpoint errors (4xx/5xx/timeout), null/partial/malformed payloads → graceful, isolated to the affected section
- [ ] Business-date switching re-queries and rebuilds all sections
- [ ] No cross-section data bleed (correct section/calculator ids)
