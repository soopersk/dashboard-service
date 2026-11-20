# GitLab Epic + Issues — Run Attributes (`include=attributes`)

Copy-paste source for GitLab. The epic comes first, then seven issues with descriptions,
dependencies and acceptance criteria. Use the sequencing table at the end to order the work.

Design reference: [Attribute enhancement plan, revision 4](2026-11-12-attribute-enhancement-plan.md).
Section and step numbers below refer to that plan. This file is a local issue source; it does not
create or update GitLab issues.

---

## EPIC

**Title:**
```
Run attributes — store start/completion event payloads and expose configured attributes via include=attributes
```

**Description:**
```markdown
## Summary

Airflow's completion events carry calculator results (task version, executor count, input/output
volumes) that `POST /runs/{runId}/complete` drops today, and nothing stored in the JSONB columns
reaches consumers. This epic stores start and completion payloads verbatim and exposes a
configured, per-calculator allow-list of attributes on `GET /calculators/batch/runs` and
`GET /calculators/{name}/runs` when the client asks for `?include=attributes`.

It also fixes a latent data-loss bug: completion currently rewrites `run_parameters` and
`additional_attributes` from the loaded row, and a serialization failure degrades them to null,
so a completion can silently wipe start data.

## Approach

- Storage (plan §1): start `context.data` → `run_parameters`; start `event.additionalData` →
  `additional_attributes.start`; completion `context.data` → new `run_results` column; completion
  `event.additionalData` → `additional_attributes.completion`. Verbatim, no normalisation.
- Completion write (plan §2): targeted `UPDATE … WHERE status = 'RUNNING' RETURNING *` that adds
  only the `completion` key and never touches `run_parameters`. `upsert` becomes start-only.
- Configuration (plan §3.1–3.2): each public attribute is defined once (name → RFC 6901 JSON
  Pointer, per group); each calculator alias lists the names it exposes. It lives in
  `calculator-attributes.yml`, imported by `application.yml`, and is validated at startup.
- Response (plan §3.3): `attributes { parameters, results, metadata }` per run entry, opt-in.
  Without `include` the responses are byte-identical to today.

## Key decisions

- Public names are the consumer contract; an upstream rename is a one-line config change.
- No fallbacks, key search or per-calculator pointer overrides. `taskVersion` may appear under
  `results` (CALC_EVENT) or `metadata` (task-event); consumers check both.
- Collapsed split groups on `/batch/runs` show only name/value pairs identical across all splits;
  `/runs` shows per-split values.
- `V13` belongs to this epic; the cost design (`2026-11-19-cost-design.md`) uses `V14`.

## Explicitly out of scope

- Size guard on start-side maps (follow-up)
- Additional tables, promoted columns or attribute indexes
- Filtering or searching by attribute value
- Type conversion, transformations, client-side key selection
- Changes to `/executions`

## Definition of done (epic)

- A real capital start/complete pair and an LNF version-control completion show the expected
  attributes in dev
- Responses without `include` are byte-identical to before on both routes
- Completion never changes `run_parameters` or existing `additional_attributes` keys (JDBC-tested)
- All Testcontainers tests for this epic executed, not skipped
- Consumer docs published; dashboard team has signed off on the contract
```

**Labels:** `~epic` `~backend` `~observability` `~api`

---

## ISSUE 1 — Airflow producer mapping

**Title:**
```
Airflow: map start/completion event payloads to the run ingestion contract
```

**Description:**
```markdown
Airflow builds the `/runs/start` and `/runs/{runId}/complete` requests from upstream
event-context messages. Agree and implement this mapping (plan §1):

| Event field | Request field |
|---|---|
| start `context.data` | `StartRunRequest.runParameters` (as today) |
| start `event.additionalData` | `StartRunRequest.additionalAttributes.start` |
| orchestration fields (`odRunId`, `consRunVersion`, …) | `StartRunRequest.additionalAttributes` root (as today) |
| completion `context.data` | `CompleteRunRequest.runResults` (new) |
| completion `event.additionalData` | `CompleteRunRequest.additionalAttributes` (new) |

Rules:
- Send payloads verbatim: no renaming, filtering or type conversion.
- `start` and `completion` are reserved keys in `additionalAttributes`.
- Omit a payload that does not exist; `{}` means "supplied, empty".
- Each completion map is limited to 64 KB serialized (largest sample is ~4 KB).
- One `/complete` per run; a replay is accepted but its payload is ignored.

The start-side mapping works on today's service (it uses the existing map). The two completion
fields need Issue 2; until it is deployed the service ignores them (unknown JSON properties are
ignored), so Airflow can ship first.

### Dependencies

None to agree the contract. Completion fields are stored only after Issue 2 is deployed.
```

**Acceptance Criteria:**
```markdown
- [ ] Mapping and rules agreed in writing with the Airflow team
- [ ] Start requests carry start `event.additionalData` under `additionalAttributes.start`; Airflow uses no other root `start`/`completion` keys
- [ ] Complete requests carry `runResults` and `additionalAttributes` as mapped above, verbatim
- [ ] Confirmed `calculatorName` for LNF version control: `lnfversioncontrolcalc` (prod), `lnfversioncontrolcalcdev` (dev)
- [ ] One sample start + complete request per event style (task-event, CALC_EVENT) attached to this issue
```

**Labels:** `~integration` `~airflow`
**Suggested weight:** 2

---

## ISSUE 2 — Store completion payloads with a targeted completion UPDATE

**Title:**
```
Ingestion: store completion payloads (run_results + additional_attributes.completion) via a targeted completeRun UPDATE
```

**Description:**
```markdown
`/complete` accepts no event data, and its `upsert` rewrites `run_parameters` and
`additional_attributes` from the loaded row, with serialization failures degrading to null. A
completion can therefore silently wipe start data.

Scope (plan §1, §2, steps 1–5):
- Migration `V13__run_results.sql`: `run_results JSONB` (nullable, catalog-only on the partitioned parent).
- `CalculatorRun.runResults`; `SELECT_WITH_JSONB` and the JSONB row mapper read `run_results`.
- `CalculatorRunRepository.completeRun(run, runResults, completionMeta)`: `UPDATE … WHERE status = 'RUNNING' RETURNING *`.
  It sets the terminal fields and `run_results`, merges only the `completion` key, keeps
  `trackRunningState`, and adds timer tag `query=complete_run`. If no row matches it throws
  `IllegalStateException`, which rolls back.
- `CompleteRunRequest.runResults` and `.additionalAttributes` with `@Schema` docs.
- `RunIngestionService.doCompleteRun`: strict `JsonbConverter.toJsonb` (no degraded null); a 64 KB
  limit per map, counted in UTF-8 bytes and checked after the replay early return; calls
  `completeRun`; publishes the returned row in events.
- `upsert` unchanged; it now serves `/start` only.

Coordination: `V13` is reserved for this issue; `2026-11-19-cost-design.md` §F.1 must use `V14`.

### Dependencies

None.
```

**Acceptance Criteria:**
```markdown
- [ ] `V13` applies on an empty DB and a dev snapshot; `run_results` exists on the parent, existing partitions and a newly created partition
- [ ] After completion, `run_parameters` and every existing `additional_attributes` key (`start`, `odRunId`, guard strays) are unchanged; only `completion` is added
- [ ] Null map → `run_results` NULL / no `completion` key; `{}` → stored as `{}`; merge works when `additional_attributes` is NULL
- [ ] A map over 64 KB (UTF-8) → 400; the run stays RUNNING and nothing is written
- [ ] A serialization failure fails the request; no null is written
- [ ] A replay on a terminal run → 200 with stored values unchanged; a different status → 409 (unchanged)
- [ ] `RunCompletedEvent` / `SlaBreachedEvent` carry the row returned by `completeRun`
- [ ] JDBC tests (MEG pair, null/`{}` matrix, 0-row update) executed against Testcontainers, not skipped
```

**Labels:** `~backend` `~database` `~ingestion`
**Suggested weight:** 3

---

## ISSUE 3 — Attribute mapping config and startup validation

**Title:**
```
Config: calculator-attributes.yml (definitions + per-calculator selection) with startup validation
```

**Description:**
```markdown
Adds the attribute mapping and its compiled, validated form. No endpoint behaviour changes.

Scope (plan §3.1, §3.2, §4, steps 6–9):
- New `src/main/resources/calculator-attributes.yml` with the initial mapping (capital,
  lnf-version-control) and the header comment from step 6.
- `application.yml`: `spring.config.import: classpath:calculator-attributes.yml` (no `optional:`).
- `CalculatorProperties`: `attributeDefinitions` (parameters / results / metadata: public name →
  JSON Pointer) and `exposedAttributes` (alias → names).
- New `config/AttributeExposure` record (compiled pointers per group; `NONE`).
- `CalculatorNameResolver`: explicit constructor that compiles once; `exposedAttributesOf(nameOrAlias)`
  and `hasExposedAttributes(nameOrAlias)`, accepting alias or real `calculator_name`.
- Validation: invalid or empty pointer, public name not matching `[A-Za-z][A-Za-z0-9]*`, or a
  selected name that no group defines → startup failure naming the property path. An
  `exposed-attributes` key that is not an alias → one WARN
  (`event=calculator.attributes.config outcome=unknown_alias`).
- `lnf-version-control` alias: prod `lnfversioncontrolcalc`, dev `lnfversioncontrolcalcdev`,
  local `lnfversioncontrolcalc`.

### Dependencies

None (can run in parallel with Issue 2).
```

**Acceptance Criteria:**
```markdown
- [ ] Binding test (`ConfigDataApplicationContextInitializer`) loads the import: camelCase names preserved, pointers verbatim (`/start/overrides/databricks.num_workers`)
- [ ] The app fails to start if the imported file is missing
- [ ] Startup fails with the property path for: invalid pointer, empty pointer, invalid public name, selected-but-undefined name
- [ ] An unknown alias key logs one WARN and the app starts
- [ ] Alias and real name resolve to the same exposure; unconfigured or null → `NONE`; selection order preserved
- [ ] `lnf-version-control` alias present in prod, dev and local; dev and prod start without the unknown-alias WARN
```

**Labels:** `~backend` `~config`
**Suggested weight:** 2

---

## ISSUE 4 — `include=attributes` on run history

**Title:**
```
Run history: include=attributes on GET /calculators/{calculatorName}/runs
```

**Description:**
```markdown
First consumer-visible slice: attribute projection plus the `include` parameter, on the history
route (it already loads JSONB, so it needs no query change).

Scope (plan §3.3, steps 10, 12, 13):
- New `dto/response/RunAttributes` record (`parameters`, `results`, `metadata`;
  `@JsonInclude(NON_EMPTY)`): `from(run, exposure)` and the RFC 6901 `resolve` walk over
  Jackson-deserialized `Map`/`List` values. No instance `is*`/`get*` helpers (Jackson would
  serialize them).
- `RunHistoryResponse.RunEntry`: last component `attributes` (`NON_NULL`), `from(run, attributes)`,
  `withoutAttributes()`; `RunHistoryResponse.withoutAttributes()`.
- `RunQueryService`: inject `CalculatorNameResolver`; resolve the exposure once per request.
- `RunQueryController`: `@RequestParam(required = false) Set<String> include`. Only `attributes`
  is accepted; any other token → 400 via the existing `IllegalArgumentException` path. When
  `include` is absent, strip attributes after the cache read.
- Run-history cache key unchanged; snapshots always carry attributes.
- Update the 5 existing `RunHistoryResponse.RunEntry` constructor calls in tests (plan, Tests §).

### Dependencies

Issue 2 (`runResults` is read), Issue 3 (exposure).
```

**Acceptance Criteria:**
```markdown
- [ ] Without `include`, response JSON is byte-identical to before
- [ ] `include=attributes` on a configured calculator returns the §3.3 groups; an unconfigured calculator returns none
- [ ] `include=bogus` and `include=attributes,bogus` → 400
- [ ] Pointers resolve a literal-dot key, a nested object, an array index and a `~1` escape; a missing path or JSON null is omitted; values keep their JSON type; objects are returned whole
- [ ] Empty groups omitted; `attributes` omitted when nothing resolves
- [ ] A cached snapshot written before deploy (no `attributes`) still deserializes
```

**Labels:** `~backend` `~api`
**Suggested weight:** 3

---

## ISSUE 5 — `include=attributes` on the dashboard feed

**Title:**
```
Dashboard feed: include=attributes on GET /calculators/batch/runs
```

**Description:**
```markdown
Extends attributes to `/batch/runs`, which needs JSONB loading, split collapsing and cached-entry
stripping.

Scope (plan §3.3, steps 3c, 10, 11, 13):
- `CalculatorRunRepository.findAllRunsByDateAndDimension(…, boolean withJsonb)`: chooses
  `SELECT_WITH_JSONB` and the JSONB mapper. `CalculatorStateService` passes `true` when any
  missed calculator has an exposure.
- `CalculatorBatchRunsResponse.RunEntry`: last component `attributes`.
- `CalculatorStateService.toRunEntry` sets attributes (standalone reruns use the latest attempt);
  `collapseSplitGroup` sets `RunAttributes.common(...)`, which keeps only pairs identical across
  all splits.
- NOT_STARTED projections and dimension placeholders carry no attributes.
- `RunQueryController`: `include` on this route, using Issue 4's validation; strip attributes
  after the cache read when absent.
- `obs:state:*` keys, TTLs and invalidation unchanged; entries are always cached with attributes.
- Test updates: 27 `findAllRunsByDateAndDimension` references in `CalculatorStateServiceTest`
  gain a 5th matcher, and `setUp` stubs `exposedAttributesOf` → `AttributeExposure.NONE` (the
  resolver is a mock); repository tests pass `false`.

### Dependencies

Issue 4 (`RunAttributes`, `include` validation).
```

**Acceptance Criteria:**
```markdown
- [ ] Without `include`, response JSON is byte-identical to before
- [ ] `include=attributes` returns per-run attributes for configured calculators; NOT_STARTED and placeholder entries have none
- [ ] A collapsed split group shows only pairs identical across all splits; `/runs` still shows per-split values
- [ ] A rerun bucket shows the latest attempt's attributes
- [ ] JSONB is loaded only when a requested calculator is configured (asserted on the repository call)
- [ ] `include=bogus` → 400
- [ ] A cached entry written before deploy (no `attributes`) still deserializes
```

**Labels:** `~backend` `~api` `~dashboard`
**Suggested weight:** 3

---

## ISSUE 6 — Documentation

**Title:**
```
Docs: consumer API attributes, producer mapping and CLAUDE.md conventions
```

**Description:**
```markdown
Plan step 14. Draft alongside Issues 2–5; finalise when they merge.

### Dependencies

Issue 1 (producer mapping), Issues 2–5 (final behaviour).
```

**Acceptance Criteria:**
```markdown
- [ ] `docs/consumer-api.md`: `include` on both routes, sample response, `attributes` field table and omission rules, split-group behaviour, and that `taskVersion` may be under `results` or `metadata`
- [ ] `docs/consumer-api.md`: Airflow producer mapping table from Issue 1
- [ ] `CLAUDE.md`: API rows for both routes; storage contract table; reserved `start`/`completion` keys; completion uses `completeRun` and `upsert` is start-only
- [ ] `CLAUDE.md`: mapping lives in `calculator-attributes.yml` (imported, not optional, keys defined only there, alias-keyed, validated at startup)
- [ ] Dashboard team sign-off on the contract recorded on the epic
```

**Labels:** `~documentation`
**Suggested weight:** 1

---

## ISSUE 7 — End-to-end verification and rollout

**Title:**
```
Attributes: end-to-end verification (local + dev) and rollout notes
```

**Description:**
```markdown
Run the plan's Verification steps 1–8 locally, then repeat the key checks in dev with real
Airflow payloads. Record the rollout behaviour the plan describes (Behaviour notes):
- Mixed-version rollout: old pods ignore the new request fields, so a run completed on an old pod
  has no results.
- Cache staleness after deploy: up to 1 h for clean terminal `/batch/runs` entries, unless
  evicted by an event or bypassed with `nocache=true`.
- A mapping change takes effect only after a restart.

### Dependencies

Issues 2–5; Issue 1 for the dev checks.
```

**Acceptance Criteria:**
```markdown
- [ ] Full suite run with Docker; this epic's Testcontainers tests executed, none skipped
- [ ] Plan Verification steps 1–8 pass locally; results recorded on this issue
- [ ] Dev: a real capital start/complete pair and an LNF version-control completion show the expected attributes; no unknown-alias WARN at startup
- [ ] `/batch/runs` cache-miss latency with vs. without `include=attributes` measured on dev data and recorded
- [ ] Rollout notes (mixed versions, cache staleness, restart for mapping changes) recorded on the epic
```

**Labels:** `~backend` `~testing` `~rollout`
**Suggested weight:** 2

---

## Suggested sequencing

| Issue | Depends on | Release role |
|---|---|---|
| 1 — Airflow mapping | None | Contract; start-side mapping can ship immediately |
| 2 — Completion storage | None | Fixes the completion data-loss bug on its own; enables completion fields |
| 3 — Mapping config | None | Config and validation only; no behaviour change |
| 4 — `/runs` attributes | 2, 3 | First consumer-visible slice |
| 5 — `/batch/runs` attributes | 4 | Dashboard feed |
| 6 — Documentation | 1, 2–5 | Draft early, finalise last |
| 7 — Verification + rollout | 2–5 (1 for dev) | Release sign-off |

Issues 1, 2 and 3 can start in parallel. Issues 2 and 3 are each deployable on their own; no
attributes are exposed until Issue 4 ships.

Total suggested weight: **16**
