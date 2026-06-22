# ADDENDUM — Performance Card integration testing (`/calculator/executions`)

Extends epic **Integration Testing: Observability Service ↔ Capital Calculation Dashboard UI**.

The **Performance Card** is the second UI card and is populated by a separate observability endpoint, **`GET /calculator/executions`** (distinct from the dashboard-grid endpoint). It shows, per calculator: scheduled SLA, mean run time, an SLA-outcome distribution, and the last 30 daily executions plotted as floating start→end bars against SLA reference lines. The card is reachable from **every calculator in all four sections** (Regional region-run, Portfolio / Group portfolio single calculator, Risk governed run-type), so it must be tested both in isolation and as invoked from each context.

### Updates to the parent epic
- Add Issues **8, 9, 10** below to the epic's child-issue table; all depend on **Issue 1 (Foundation)**.
- The "Performance card (per calculator)" bullets in Issues 2–7 are **narrowed to invocation/keying checks only** — full card coverage now lives here.
- Move the performance-card contract stub from Issue 1 into **Issue 8** (Issue 1 keeps only the shared card *shell*: loading/empty/error states).

### Performance Card field model (reference — derived from `/calculator/executions`)

| UI element | Field(s) | Example |
|------------|----------|---------|
| Schedule | `schedule.time`, `schedule.frequency` | `10:00 CET`, `Daily` |
| Mean run time | `meanRunTime`, `window` | `1hr 39mins`, `Last 30 days` |
| SLA distribution | `slaDistribution.veryLatePct / latePct / slaMetPct` | `14 / 16 / 70` |
| SLA reference lines | `slaWindow.slaStartTime`, `slaWindow.slaEndTime` | `10:00 CET`, `12:00 CET` |
| Execution bars (≤30) | `executions[].date / startTime / endTime / duration / slaStatus` | `Tue 27 Jan 2026`, `11:07`, `13:14`, `2hrs 7mins`, `very_late` |

`slaStatus` ∈ `{ sla_met (green), late (amber), very_late (red) }`. Distribution percentages should sum to 100. SLA-status colour is the single source of truth shared by the distribution bar and each execution bar.

---

# ISSUE 8 — Performance card: `/calculator/executions` contract & summary metrics

**Labels:** `integration-testing` · `performance-card` · `contract-test`
**Depends on:** Issue 1

### Objective
Validate the `/calculator/executions` response contract and the card's **summary header** — Schedule, Mean run time, and the SLA-outcome distribution bar — against the endpoint payload.

### Scope
- **In:** request shape (calculator keying), response schema, Schedule block, Mean-run-time block, SLA distribution bar + legend/percentages.
- **Out:** the execution chart (Issue 9); cross-section invocation & errors (Issue 10).

### Test data / preconditions
- Mock `/calculator/executions` returning the captured payload: schedule `10:00 CET` / `Daily`; mean `1hr 39mins` / `Last 30 days`; distribution `14% very late / 16% late / 70% SLA met`; SLA window start `10:00 CET`, end `12:00 CET`.

### Test scenarios
1. **Request contract** — card requests `/calculator/executions` with the correct calculator identifier (and region / run-type qualifier where the card was opened from one); query params (window = last 30) correct.
2. **Response schema** — schema/field-presence validation for `schedule`, `meanRunTime`, `window`, `slaDistribution`, `slaWindow`, `executions[]`; unknown fields ignored; missing required fields surfaced.
3. **Schedule rendering** — `schedule.time` + `frequency` render as `10:00 CET` / `Daily`; verify other frequencies (e.g. Weekly/Intraday) and timezone label.
4. **Mean run time rendering** — `1hr 39mins` + `Last 30 days` window label render correctly; verify formatting for mins-only (`< 1h`), hours+mins, and `≥ 24h` durations.
5. **SLA distribution proportions** — the stacked bar segment widths match `veryLatePct / latePct / slaMetPct`; legend values render `14% / 16% / 70%` with correct colours (red/amber/green) and labels (Very late / Late / SLA met).
6. **Distribution sums to 100** — verify segments total 100%; handle rounding (e.g. 33/33/34) without a visible gap/overflow.
7. **Distribution edge cases** — `100% SLA met` (all green, no red/amber segment), `100% very late`, and a zero-segment category (segment absent, legend shows 0%).
8. **Consistency** — distribution percentages are consistent with the `slaStatus` mix in `executions[]` (e.g. 30 runs → ~count-derived percentages); flag mismatches as defects.
9. **Null / partial summary** — missing `meanRunTime` or `slaDistribution` → graceful placeholder, no crash.

### Acceptance criteria
- [ ] Request keying and response schema validated; schema-drift test fails on breaking change.
- [ ] Schedule, mean-run-time (all duration formats) and distribution bar verified against payload.
- [ ] Distribution proportions, sum-to-100, zero/100% segments and executions-consistency verified.
- [ ] Null/partial summary handled gracefully.

---

# ISSUE 9 — Performance card: 30-run execution chart, SLA reference lines & tooltips

**Labels:** `integration-testing` · `performance-card` · `chart`
**Depends on:** Issue 1

### Objective
Verify the **execution history chart**: up to 30 floating start→end bars, correct SLA colour per bar, the SLA start/end reference lines, time-axis scaling, and per-bar tooltip content — including in-progress, missing, overnight and axis-overflow runs.

### Scope
- **In:** floating bars (start→end mapping), per-bar SLA colour, chronological ordering, bar count, SLA start/end reference lines + markers, time-of-day axis, per-bar hover tooltip.
- **Out:** summary header (Issue 8); cross-section/error handling (Issue 10).

### Test data / preconditions
- Payload of 30 executions spanning `sla_met / late / very_late`, incl. the captured run: `Tue 27 Jan 2026`, start `11:07 CET`, end `13:14 CET`, duration `2hrs 7mins`, `very_late`; SLA window start `10:00`, end `12:00`.

### Test scenarios
1. **Bar start/end mapping** — each bar's top/bottom map to the run's actual start and end times on the axis (e.g. 11:07→13:14); bar length ∝ duration.
2. **Bar SLA colour** — each bar's colour matches its `slaStatus` (green/amber/red); verify a bar ending after the SLA-end line is late/very late and one ending before is `sla_met`.
3. **Late vs very-late threshold** — validate the amber→red boundary relative to the SLA-end line (consistent with the late/very-late threshold tracked elsewhere in the epic).
4. **Bar count & ordering** — exactly the returned runs (≤30) render, in chronological order left→right; no missing/duplicated bars.
5. **SLA reference lines** — SLA start (10:00) and SLA end (12:00) lines render at correct axis positions with their start (circle) / end (diamond) markers and legend labels.
6. **Time axis** — axis ticks (10:00–14:00 CET style) render with correct spacing and timezone; verify axis labels come from data range, not hard-coded.
7. **Tooltip content** — hovering a bar shows the correct date, Start time, End time and Duration for *that* run (verify against the captured 27 Jan run); tooltip follows the hovered bar, not a fixed index.
8. **Tooltip — in-progress run** — a run with no end time (still running) shows start + elapsed/estimated and renders an open-ended bar without breaking the axis.
9. **Missing / no-run day** — a day with no execution renders as a gap (not a zero-length bar, not a shifted axis); confirm against contract.
10. **Failed run in history** — a failed execution renders per contract (e.g. red/marked bar, tooltip showing failure) without distorting other bars.
11. **Rerun day** — a day with a rerun plots the agreed execution (e.g. final/latest run) per contract; verify it is not double-plotted.
12. **Axis overflow / overnight** — a very-late run ending beyond the visible axis top (or crossing midnight) is clipped/scaled correctly with the true value still in the tooltip.
13. **Sparse history** — `< 30` runs renders only the available bars without stretching/padding artefacts; single-run history renders cleanly.
14. **Empty history** — `executions: []` shows an empty-chart state, not a broken axis.

### Acceptance criteria
- [ ] Bar start/end mapping, length, colour and chronological order verified against payload.
- [ ] SLA start/end reference lines, markers and time axis verified.
- [ ] Per-bar tooltip (date/start/end/duration) verified, incl. in-progress runs.
- [ ] Missing-day, failed-run, rerun-day, axis-overflow/overnight, sparse and empty histories handled gracefully.

---

# ISSUE 10 — Performance card: cross-section invocation, keying & edge/error cases

**Labels:** `integration-testing` · `performance-card` · `cross-section` · `error-handling`
**Depends on:** Issues 8, 9 (and Issues 2–7 for the invocation contexts)

### Objective
Verify the card behaves correctly when **opened from every calculator context across all four sections** (correct keying to the right execution history), and that it handles degraded `/calculator/executions` responses without affecting the dashboard grid.

### Scope
- **In:** invocation/keying from each section, correct calculator/region/run-type identifier in the request, isolation from the dashboard endpoint, error/timeout/malformed handling, timezone/DST, refresh.
- **Out:** internal chart/summary rendering already covered in Issues 8–9 (re-used here as assertions).

### Test scenarios
1. **Regional invocation** — opening the card from a Regional region (e.g. `Eligible TLAC` → `LDNL`) calls `/calculator/executions` keyed to `{calculator, region}`; the history shown is that region's, not the whole calculator's.
2. **Portfolio / Group portfolio invocation** — opening from a single-status calculator keys to `{calculator}` (no region/run-type) and returns that calculator's history.
3. **Risk governed invocation** — opening from a run-type (e.g. `Modelled Exposure` → `OTC`) keys to `{calculator, run-type}`; history is that run-type's.
4. **Keying isolation** — switching the open card from one calculator/region/run-type to another re-queries and fully replaces summary + chart (no stale bars, no merged histories).
5. **Endpoint vs dashboard isolation** — `/calculator/executions` failing does **not** break the dashboard grid (driven by the dashboard endpoint), and vice-versa; the card shows its own error state.
6. **Error responses** — 400 / 401-403 / 404 (no history) / 500 / 503 / timeout each produce a clear card-level error or empty state, no crash.
7. **Malformed / null payload** — null timestamps, missing `slaWindow`, partial `executions` → graceful degradation in summary and chart.
8. **Timezone & DST** — all times render in the stated zone (CET); verify behaviour across a DST boundary in the 30-day window (no off-by-one-hour bars or tooltips).
9. **Refresh** — re-opening or refreshing the card re-fetches; an in-progress run updates on the next fetch; closing the card cancels/ignores in-flight requests.
10. **Cross-check with grid** — the card's SLA outcome for "today" is consistent with the same calculator's status/SLA on the dashboard grid (e.g. a calculator shown `Very late` today appears as a very-late bar / contributes to the very-late distribution).
11. **Loading state** — card shows a loading state while `/calculator/executions` is pending (shared shell from Issue 1).

### Acceptance criteria
- [ ] Correct keying verified for Regional `{calc, region}`, Portfolio/Group `{calc}`, and Risk governed `{calc, run-type}`, including switch-and-replace isolation.
- [ ] Card error/empty/malformed handling verified and isolated from the dashboard grid.
- [ ] Timezone/DST, refresh and loading behaviour verified.
- [ ] Card-vs-grid consistency for the current business date verified.

---

## Appendix — Performance-card edge-case checklist

- [ ] Distribution: 100% single category; zero-segment category; rounding sum-to-100
- [ ] Mean run time: mins-only, hours+mins, ≥ 24h formatting
- [ ] Chart: late/very-late threshold at SLA-end line; chronological order; ≤30 count
- [ ] Per-run states: in-progress (open bar), failed, rerun day, missing/no-run day
- [ ] Axis overflow / overnight / cross-midnight runs
- [ ] Sparse (< 30) and empty history
- [ ] Keying from Regional region, Portfolio/Group calculator, Risk-governed run-type
- [ ] Endpoint errors/timeouts/malformed isolated from dashboard grid
- [ ] Timezone (CET) + DST boundary in window
- [ ] Card-vs-grid consistency for current business date
