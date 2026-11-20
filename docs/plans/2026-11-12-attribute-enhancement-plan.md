# Store start + completion event attributes and expose them in the consumer API

Supersedes `read-the-consumer-api-md-file-modular-giraffe.md` (not implemented — no
`exposedAttributes` code exists). Revision 4; changes from revision 3 are listed at the end.

## Context

Airflow turns upstream event-context messages into `POST /runs/start` and
`POST /runs/{runId}/complete`. Today `/complete` accepts no attributes, so everything a completion
event carries is dropped, and nothing in the JSONB columns reaches consumers.

Every message has the same base structure (`event{…, additionalData}` + `context{id, parentIds, data}`);
the content of `context.data` and `event.additionalData` is free-form per event type.

| Sample (`src/test/resources/`) | What it is | What it shows |
|---|---|---|
| `sample_meg_event_start.json` | trigger-event start, capitalcalcmedium AMER | `context.data` = flat calculator inputs. `event.additionalData.overrides` = `{"databricks.num_workers": "4"}` — launch configuration lives **outside** `context.data`, under a key containing a literal dot |
| `sample_meg_event_complete.json` | task-event COMPLETED, **same context id** as the start above — the one real lifecycle pair | `context.data` carries the same parameter set again (the files differ only where long strings were truncated). Completion data is in `event.additionalData`: `taskVersion`, MEGDP `runId`, `jobLink`, `successful` |
| `sample_version_control_event_complete.json` (same content as `sample_COMPLETE_event.json`) | CALC_EVENT FINISH, lnfversioncontrolcalc | `context.data` = outcomes (`consorunid`, `modulesVersion`, `numberOfExecutors`) **plus** a nested `inputAttributes` copy of the producer's start inputs |
| `sample_calc_event_start.json` | CALC_EVENT FINISH, capitalcalcsmall AMER (a completion, despite the name) | `context.data` = outcomes (`taskVersion`, `inputVolumes`, `outputVolumes`, `numberOfExecutors`) with inputs echoed flat at the root |
| `sample_calc_event_complete.json` | CALC_EVENT FINISH, portfoliocalc GLOBAL | same style; a different calculator — not a pair with the file above |
| `sample_START_event.json` | task-event STARTED, older capture | same input keys spelled differently (`LombardSwitch`, `CumulUs`) |

Consequences: `taskVersion` arrives in `context.data` for CALC_EVENT completions but in
`event.additionalData` for task-event completions; key spellings drift; sensitive/noise keys
(`osUser`, `OS_USER`, `jobLink`, `tenantEnvName`) are mixed in. Within one stored column the same
key can appear in several places with **different values** — in the MEG pair `actorSystem` is
`"Airflow"` under `start` and `"spark"` under `completion`; `environment` is `"PROD"` vs
`"PRODAT39764NEU"`. Attribute addressing must therefore be explicit (§3.1).

## Evaluation of the current model

`run_parameters` + `additional_attributes` is a sound base and stays. Gaps:

1. **Completion data has no home.** A flat merge into `additional_attributes` would mix Airflow
   metadata (`odRunId`), the service's guard strays and calculator outcomes in one namespace.
2. **"`run_parameters` holds all start inputs" is too broad.** It holds the calculator input
   payload; launch overrides arrive in event metadata.
3. **Completion rewrites start-owned JSONB.** `doCompleteRun` calls `upsert`, whose ON CONFLICT
   rewrites `run_parameters`/`additional_attributes` from the loaded object, and
   `safeFromJsonb`/`safeToJsonb` degrade failures to null (`CalculatorRunRepository.java:183-205`),
   so a completion can silently wipe start data.

## Design

### 1. Storage and ingestion contract

| Source | Stored at | Sent by Airflow as | Written |
|---|---|---|---|
| Start `context.data` | `run_parameters` | `StartRunRequest.runParameters` | `/start` only; never touched afterwards |
| Start `event.additionalData` | `additional_attributes.start` | `StartRunRequest.additionalAttributes.start` (existing map — no new field) | `/start` |
| Root orchestration fields (`odRunId`, `consRunVersion`, …) + service guard strays | `additional_attributes` root | `StartRunRequest.additionalAttributes` root (as today) | `/start` |
| Completion `context.data` (whole object, incl. `inputAttributes`) | `run_results` (**new** JSONB, nullable) | `CompleteRunRequest.runResults` (new) | `/complete`, once |
| Completion `event.additionalData` | `additional_attributes.completion` | `CompleteRunRequest.additionalAttributes` (new) | `/complete`, once |

- **Verbatim: no key normalisation, filtering, de-duplication or type conversion.** `"4"` stays a string.
- **Echoed inputs stay where they arrived.** `run_results.inputAttributes` remains nested;
  `run_parameters` remains the original start snapshot. The duplication is accepted: removing or
  merging it adds complexity and would hide a difference between requested and reported inputs.
  Start parameters are never backfilled from a completion echo.
- `additional_attributes` is orchestration **and event** metadata — which includes launch
  configuration; metadata does not mean unimportant. `start` and `completion` are reserved keys.
- **null vs `{}`:** a null/absent request map means "not supplied" — `run_results` stays SQL NULL
  and no `completion` key is added. `{}` is an explicitly supplied empty object and is stored as `{}`.
- No promoted columns or indexes (V2 rule stands). No extra tables.
- `V13__run_results.sql`: `ALTER TABLE calculator_runs ADD COLUMN IF NOT EXISTS run_results JSONB;`
  Catalog-only, cascades to all partitions; new partitions inherit it. **`V13` belongs to this
  plan** (decided 2026-11-20); `2026-11-19-cost-design.md` §F.1 (`V13__calculator_run_cost.sql`)
  must move to `V14` — Flyway refuses duplicate versions.

### 2. Completion write

New `CalculatorRunRepository.completeRun(...)` replaces the `upsert` call in `doCompleteRun`:

```sql
UPDATE calculator_runs SET
    end_time = :endTime, duration_ms = :durationMs, status = :status,
    sla_band = :slaBand, sla_breached = :slaBreached, sla_breach_reason = :slaBreachReason,
    run_results = :runResults,                                   -- NULL when not supplied
    additional_attributes = CASE WHEN :completionMeta::jsonb IS NULL THEN additional_attributes
        ELSE COALESCE(additional_attributes, '{}'::jsonb)
             || jsonb_build_object('completion', :completionMeta::jsonb) END,
    updated_at = :updatedAt
WHERE run_id = :runId AND reporting_date = :reportingDate AND status = 'RUNNING'
RETURNING *
```

- `run_parameters` is absent from the statement; `||` adds only the `completion` key, so `start`,
  `odRunId` and every other root field survive. `upsert` is unchanged and now serves `/start` only
  (a `/start` replay returns early via `findById` and never reaches `upsert` —
  `RunIngestionService.java:70-76`).
- Terminal status and attributes commit in one statement. `RETURNING *` must yield exactly 1 row
  (row is held by `findByIdForUpdate`), else `IllegalStateException` → rollback. The **returned row**
  is what `doCompleteRun` returns and publishes in `RunCompletedEvent`/`SlaBreachedEvent`, so events
  carry the merged `additional_attributes` exactly as stored — the merge is not re-implemented in Java.
  Keeps the `redisCache.trackRunningState` call that `upsert` makes today.
- **Strict serialization:** the service calls `JsonbConverter.toJsonb` (throws on failure) for both
  maps before the write — no `safeToJsonb` fallback, so a failure fails the request with nothing committed.
- Size guard on the same serialized value: each map > 64 KB (**UTF-8 bytes**, not `String.length()`)
  → `DomainValidationException` (400). Largest sample ~4 KB. Start-side maps keep today's
  behaviour — flagged follow-up.
- One completion call per run (confirmed): a replay of a terminal run returns early as today and
  its payload is ignored (first write wins). The size guard runs after the replay check, so an
  oversized replay still gets the idempotent 200.

### 3. Consumer API — `include=attributes`

Opt-in on `GET /calculators/batch/runs` and `GET /calculators/{name}/runs`. Unknown token → 400.
Absent → responses byte-identical to today. Nothing is exposed unless configured.

Three groups, one per column — `parameters` ← `run_parameters`, `results` ← `run_results`,
`metadata` ← `additional_attributes`. No further groups.

#### 3.1 Configuration — define each attribute once, select per calculator

Two maps with separate jobs:

- **`attribute-definitions`** — what a public name *means*: per group, **public name → JSON Pointer
  (RFC 6901)** resolved relative to that group's column. Each name is defined once, for all calculators.
- **`exposed-attributes`** — which calculator exposes what: **calculator alias → list of public
  names**. Selecting a name exposes it from every group that defines it (that is how `capital` gets
  `taskVersion` from `results` on CALC_EVENT runs and from `metadata` on task-event runs).

Both live in a dedicated file, `src/main/resources/calculator-attributes.yml`, imported from
`application.yml`:

```yaml
# application.yml
spring:
  config:
    # Consumer attribute mapping — see calculator-attributes.yml. Not optional: a missing file
    # must fail startup rather than silently expose nothing.
    import: classpath:calculator-attributes.yml
```

```yaml
# src/main/resources/calculator-attributes.yml
observability:
  calculator:
    attribute-definitions:
      parameters:
        calcType: /calcType
        runCategory: /run-category
      results:
        taskVersion: /taskVersion                   # CALC_EVENT completions (context.data)
        numberOfExecutors: /numberOfExecutors
        outputVolumes: /outputVolumes
        consoRunId: /consorunid
        modulesVersion: /modulesVersion
        reportedProcess: /inputAttributes/process   # echoed start input, as reported at completion
      metadata:
        taskVersion: /completion/taskVersion        # task-event completions (event.additionalData)
        requestedWorkerCount: /start/overrides/databricks.num_workers
    exposed-attributes:
      capital: [calcType, runCategory, taskVersion, numberOfExecutors, outputVolumes, requestedWorkerCount]
      lnf-version-control: [consoRunId, modulesVersion, reportedProcess]
```

Growth: lines ≈ distinct attributes + calculators, not calculators × attributes. Estimates
(distinct-attribute counts are assumptions):

| Scale | Rev 3 (per-calculator pointer maps) | Rev 4 (definitions + selection) |
|---|---|---|
| 11 calculators × ~8 attributes, ~25 distinct | ~130 lines | ~40 |
| 30 calculators × ~10 attributes, ~50 distinct | ~420 lines | ~85 |

Rejected alternatives:

- **Comma-separated key lists per group.** A flat key cannot reach `/completion/taskVersion`,
  `/start/overrides/databricks.num_workers` or `/inputAttributes/process` (3 of the 10 sample
  entries), and with no rename the public name becomes the upstream spelling (`consorunid`,
  `run-category`), so upstream drift (`LombardSwitch` → `lombardSwitch`) breaks consumers.
- **Flat keys + recursive "first match" search within the group's column.** Returns wrong values
  where a key repeats within one column (`actorSystem` `"Airflow"` under `start` vs `"spark"` under
  `completion`). "First" is decided by JSONB key order, which PostgreSQL does not preserve (in
  practice shorter keys first, so `start` would win over `completion` forever). An echo-only key
  (`region` exists only in `inputAttributes`) would be returned as an outcome, violating §3.3.
  New upstream keys could silently change existing attributes. Making it deterministic needs
  precedence rules hard-coded in Java — a hidden path language.

#### 3.2 Configuration rules

- **Pointers:** standard JSON Pointer only (Jackson `JsonPointer`): a literal dot needs no escaping;
  `~0`/`~1` per the RFC. No wildcards, fallbacks, conversions or expressions. Invalid pointer →
  startup failure. The empty pointer `""` (whole column) → startup failure — exposure is an allow-list.
- **Public names** match `[A-Za-z][A-Za-z0-9]*` (camelCase by convention); violation → startup
  failure. Spring silently *removes* characters other than alphanumerics, `-` and `.` from map keys
  (an `_` cannot be detected after binding), so the binding test (Tests §) pins the shipped names.
- **One meaning per name:** a public name resolves to the same pointer for every calculator. A
  calculator needing a different location gets a different name. No per-calculator overrides.
- **Selection must be defined:** a name in `exposed-attributes` that no group defines → startup
  failure naming the alias and the name.
- **Keys are aliases** (same keys as `regions`/`run-types`/`run-number-aware`); a real
  `calculator_name` resolves through `toAlias`. A key that is not in `aliases` logs one startup WARN
  (`event=calculator.attributes.config outcome=unknown_alias`) — not a failure, because aliases are
  per-profile and an unaliased real name is a legal passthrough. This is what would have caught
  revision 3's never-matching `lnf-version-control` key.
- **File rules:** (1) no `optional:` prefix; (2) these keys are defined **only** in
  `calculator-attributes.yml` — an imported file takes precedence over the file that imports it, and
  maps merge while lists replace across sources, so a second copy elsewhere would be confusing;
  (3) classpath, versioned with code, not a Kubernetes ConfigMap — pointers are compiled at startup,
  so a ConfigMap edit still needs a restart and would move the consumer contract out of code review;
  (4) `regions`/`run-types`/`run-number-aware` stay in `application.yml` (small, don't grow per attribute).
- A mapping change takes effect on restart; cached entries keep the old projection until TTL,
  event eviction or `nocache=true` (Behaviour notes).

#### 3.3 Response

Response for the MEG pair (start + task-event completion), calculator `capital`:

```json
"attributes": {
  "parameters": { "calcType": "B3F", "runCategory": "CORE" },
  "metadata":   { "taskVersion": "51.0.1", "requestedWorkerCount": "4" } }
```

For a CALC_EVENT completion (`sample_calc_event_start.json`, capitalcalcsmall) `results` carries
`taskVersion`, `numberOfExecutors` (`"4"`) and `outputVolumes` (object, returned whole).

- Missing path or JSON null → name omitted. Values keep their native JSON type; an object is returned whole.
- Keys within a group follow the calculator's selection-list order.
- `parameters` always comes from `run_parameters`. An echoed completion input is exposed only when
  explicitly mapped under `results`, with a name that says so (`reportedProcess`).
- `requestedWorkerCount` is a launch request, not evidence of runtime resources; it is a separate
  field from `results.numberOfExecutors` and the two are never equated or derived from each other.
- **`taskVersion` may appear under `results` or `metadata`** depending on the producer's event type.
  The no-fallback rule leaves that choice to the consumer; `consumer-api.md` says so explicitly.
- Public names are the consumer contract; an upstream rename is a one-line config change (runs
  stored under the old spelling stop resolving — no multi-path fallback).
- Empty group omitted; `attributes` omitted when all are empty and on NOT_STARTED/placeholder entries.
- **Collapsed split groups** (`/batch/runs`): project each split, keep only name/value pairs
  identical across all splits. Per-split values are on `/runs`, which never collapses.
- Existing top-level `odRunId`/`consRunVersion`/`calculatorRunVersion` on `/runs` are unchanged.
  `/executions` is unchanged.

Not introduced: additional tables, further response groups, enrichment endpoints, cache variants,
automatic type conversions, a transformation framework, attribute indexes, client key selection,
per-calculator pointer overrides, key search or multi-path fallback.

### 4. Alias prerequisites

Exposure is keyed by alias, so every exposed calculator needs an alias in every profile.

| Profile | Today | Change |
|---|---|---|
| prod (`application-prod.yml`) | no `lnf-version-control` | add `lnf-version-control: [lnfversioncontrolcalc]` |
| local (`application-local.yml`) | `capital: [capitalcalc]`, `portfolio` only | add `lnf-version-control: [lnfversioncontrolcalc]` |
| dev (`application-dev.yml`) | no `lnf-version-control` | add `lnf-version-control: [lnfversioncontrolcalcdev]` (name confirmed 2026-11-20) |

- Prod name `lnfversioncontrolcalc` is assumed from the PROD sample (`event.key`,
  `inputAttributes.class`), matching how prod `capital` aliases already equal producer `class` values.
- Side effects checked: only `CalculatorNameResolver` and `ExpectedRunsService` read `aliases`; no
  job iterates them (the "MissingRunDetectionJob" in the `application-local.yml` comment does not
  exist in code). The new alias makes `findAliasFor` resolve the name (so `AnalyticsCacheService`
  alias-index invalidation covers it) and lets `/batch/runs` take the alias as a key. It has no
  `regions`/`run-types`, so no padding changes.
- Local `capital` stays `[capitalcalc]`: adding `capitalcalcmedium` would make it multi-alias locally
  and change merge behaviour. Local verification posts the MEG sample as `calculatorName=capitalcalc`.

## Implementation

Files touched:

| File | Change |
|---|---|
| `db/migration/V13__run_results.sql` | new |
| `domain/CalculatorRun.java` | `runResults` field |
| `repository/CalculatorRunRepository.java` | `SELECT_WITH_JSONB` + mapper; `completeRun`; `findAllRunsByDateAndDimension(…, withJsonb)` |
| `dto/request/CompleteRunRequest.java` | `runResults`, `additionalAttributes` |
| `service/RunIngestionService.java` | strict serialize + guard; call `completeRun`; inject `JsonbConverter` |
| `resources/calculator-attributes.yml` | new — the mapping |
| `resources/application.yml` | `spring.config.import` |
| `resources/application-prod.yml`, `application-dev.yml`, `application-local.yml` | `lnf-version-control` alias |
| `config/CalculatorProperties.java` | `attributeDefinitions`, `exposedAttributes` |
| `config/AttributeExposure.java` | new — compiled per-alias selection |
| `service/CalculatorNameResolver.java` | compile + validate at construction; `exposedAttributesOf`, `hasExposedAttributes` |
| `dto/response/RunAttributes.java` | new — projection + split intersection |
| `dto/response/CalculatorBatchRunsResponse.java` | `RunEntry.attributes` |
| `service/CalculatorStateService.java` | `withJsonb`; set attributes in `toRunEntry` / `collapseSplitGroup` |
| `dto/response/RunHistoryResponse.java` | `RunEntry.attributes`, `from(run, attributes)`, `withoutAttributes()` |
| `service/RunQueryService.java` | supply attributes; inject `CalculatorNameResolver` |
| `controller/RunQueryController.java` | `include` param + strip |
| `docs/consumer-api.md`, `CLAUDE.md` | docs |

### Step 0 — Task file
Write this plan to `tasks/todo.md` as checkable items (CLAUDE.md task rule).

### Step 1 — Migration
`src/main/resources/db/migration/V13__run_results.sql`:

```sql
-- Completion event context.data, stored verbatim. NULL = not supplied. Catalog-only on a
-- partitioned parent: cascades to existing partitions, inherited by new ones.
ALTER TABLE calculator_runs ADD COLUMN IF NOT EXISTS run_results JSONB;
```

### Step 2 — `CalculatorRun`
Add next to `runParameters`/`additionalAttributes`:

```java
private Map<String, Object> runResults;   // completion context.data (run_results), null until completed with results
```

### Step 3 — `CalculatorRunRepository`

**3a. Read path.** Add the column to the JSONB select and the mapper (covers `upsert`'s
`RETURNING *`, `findByIdForUpdate`, `findById(String)`, `findRunHistory`):

```java
private static final String SELECT_WITH_JSONB =
        "SELECT " + CORE_COLUMNS + ",\nrun_parameters, additional_attributes, run_results\nFROM calculator_runs\n";
```

```java
if (includeJsonb) {
    String runId = rs.getString("run_id");
    builder.runParameters(safeFromJsonb("run_parameters", runId, rs.getObject("run_parameters")))
           .additionalAttributes(safeFromJsonb("additional_attributes", runId, rs.getObject("additional_attributes")))
           .runResults(safeFromJsonb("run_results", runId, rs.getObject("run_results")));
}
```

Update the `SELECT_WITH_JSONB` Javadoc: it is required wherever JSONB is read (write-back via
`upsert` now happens only at `/start`).

**3b. `completeRun`** (new; `upsert` unchanged):

```java
/**
 * Terminal transition for a RUNNING run. Writes timing/SLA outcome, stores run_results once and
 * merges additional_attributes.completion; never touches run_parameters or any other
 * additional_attributes key. Returns the updated row (published in run events).
 *
 * @param runResults     typed jsonb PGobject; SQL-null value = not supplied
 * @param completionMeta typed jsonb PGobject; SQL-null value = no completion key added
 * @throws IllegalStateException when no RUNNING row matched (caller rolls back)
 */
public CalculatorRun completeRun(CalculatorRun run, PGobject runResults, PGobject completionMeta) {
    Instant now = Instant.now();
    run.setUpdatedAt(now);

    String sql = """
        UPDATE calculator_runs SET
            end_time = :endTime, duration_ms = :durationMs, status = :status,
            sla_band = :slaBand, sla_breached = :slaBreached, sla_breach_reason = :slaBreachReason,
            run_results = :runResults,
            additional_attributes = CASE WHEN :completionMeta::jsonb IS NULL THEN additional_attributes
                ELSE COALESCE(additional_attributes, '{}'::jsonb)
                     || jsonb_build_object('completion', :completionMeta::jsonb) END,
            updated_at = :updatedAt
        WHERE run_id = :runId AND reporting_date = :reportingDate AND status = 'RUNNING'
        RETURNING *
        """;

    MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("runId", run.getRunId())
            .addValue("reportingDate", run.getReportingDate())
            .addValue("endTime", toTimestamp(run.getEndTime()))
            .addValue("durationMs", run.getDurationMs())
            .addValue("status", run.getStatus().name())
            .addValue("slaBand", run.getSlaBand() != null ? run.getSlaBand().name() : null)
            .addValue("slaBreached", run.isSlaBreached())
            .addValue("slaBreachReason", run.getSlaBreachReason())
            .addValue("runResults", runResults)
            .addValue("completionMeta", completionMeta)
            .addValue("updatedAt", Timestamp.from(now));

    Timer.Sample sample = Timer.start(meterRegistry);
    CalculatorRun saved = DataAccessUtils.singleResult(
            jdbcTemplate.query(sql, params, new CalculatorRunRowMapper(true)));
    sample.stop(Timer.builder(DB_QUERY_DURATION).tag("query", "complete_run").register(meterRegistry));

    if (saved == null) {
        throw new IllegalStateException("completeRun matched no RUNNING row: run_id=" + run.getRunId()
                + " reporting_date=" + run.getReportingDate());
    }
    try {
        redisCache.trackRunningState(saved);
    } catch (Exception cacheEx) {
        log.warn("event=cache.write outcome=failure run_id={} error={}", saved.getRunId(), cacheEx.getMessage(), cacheEx);
    }
    log.debug("event=run.complete_run outcome=success run_id={}", saved.getRunId());
    return saved;
}
```

`:completionMeta::jsonb` — `NamedParameterJdbcTemplate` treats `::` as a cast, not a parameter;
the repeated parameter is bound twice. The explicit cast gives `jsonb_build_object` a concrete type.
`DataAccessException` propagates unwrapped, like `upsert`.

**3c. `findAllRunsByDateAndDimension`** — add `boolean withJsonb` as the last parameter:

```java
StringBuilder sql = new StringBuilder((withJsonb ? SELECT_WITH_JSONB : SELECT_BASE) + """
        WHERE reporting_date  = :reportingDate
        ...""");
...
List<CalculatorRun> results = jdbcTemplate.query(sql.toString(), params, new CalculatorRunRowMapper(withJsonb));
```

Add `withJsonb={}` to both debug log lines. One flag per query: in a mixed batch JSONB is loaded
for every row, including unconfigured calculators — accepted to keep a single simple query.

### Step 4 — `CompleteRunRequest`

```java
@Schema(description = "Completion event context.data, stored verbatim in run_results. "
        + "Omit/null = not supplied; {} = supplied empty. Max 64 KB serialized.")
private Map<String, Object> runResults;

@Schema(description = "Completion event.additionalData, stored verbatim at additional_attributes.completion. "
        + "Omit/null = not supplied; {} = supplied empty. Max 64 KB serialized.")
private Map<String, Object> additionalAttributes;
```

### Step 5 — `RunIngestionService.doCompleteRun`

Inject `JsonbConverter` (new constructor dependency). In `doCompleteRun`, after the replay/conflict
early return and the end-time check, **before** mutating `run`:

```java
static final int MAX_COMPLETION_JSON_BYTES = 64 * 1024;

PGobject runResults = toBoundedJsonb("runResults", request.getRunResults());
PGobject completionMeta = toBoundedJsonb("additionalAttributes", request.getAdditionalAttributes());
```

Replace `run = runRepository.upsert(run);` with:

```java
run = runRepository.completeRun(run, runResults, completionMeta);   // returned row carries run_results + merged completion
```

Everything after (deregistration, metrics, event publication, return) keeps using `run`, now the
stored row.

```java
/** Strict: serialization failure propagates (no degraded null). null map → typed SQL-null jsonb. */
private PGobject toBoundedJsonb(String field, Map<String, Object> map) {
    PGobject pg = jsonbConverter.toJsonb(map);
    String json = pg.getValue();
    if (json != null) {
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_COMPLETION_JSON_BYTES) {
            throw new DomainValidationException(field + " exceeds " + MAX_COMPLETION_JSON_BYTES
                    + " bytes serialized (" + bytes + ")");
        }
    }
    return pg;
}
```

`JsonbConverter.toJsonb(null)` already yields a typed jsonb `PGobject` with a null value; `{}`
serializes to `"{}"`. Both exceptions map to 400 via `GlobalExceptionHandler`
(`DomainValidationException`, `IllegalArgumentException`) and roll back the transaction.

### Step 6 — Config files

1. Create `src/main/resources/calculator-attributes.yml` with the §3.1 content plus a header comment:
   ```yaml
   # Consumer attribute contract for ?include=attributes on /calculators/batch/runs and
   # /calculators/{name}/runs. Imported by application.yml (spring.config.import). Env-invariant.
   # Define these keys ONLY here: an imported file overrides the file that imports it.
   #
   # attribute-definitions: public name -> RFC 6901 JSON Pointer, relative to the group's column
   #   parameters -> run_parameters | results -> run_results | metadata -> additional_attributes
   #   One meaning per name for every calculator. Names: [A-Za-z][A-Za-z0-9]*.
   # exposed-attributes: calculator ALIAS (same keys as regions/run-types) -> public names.
   #   A selected name is exposed from every group that defines it.
   ```
2. `application.yml`: add the `spring.config.import` block (§3.1) under `spring:`.
3. Add the `lnf-version-control` alias (§4) to each profile:
   - `application-prod.yml`: `lnf-version-control: [lnfversioncontrolcalc]`
   - `application-dev.yml`: `lnf-version-control: [lnfversioncontrolcalcdev]`
   - `application-local.yml`: `lnf-version-control: [lnfversioncontrolcalc]`

### Step 7 — `CalculatorProperties`

```java
/** Group → public name → JSON Pointer. Defined in calculator-attributes.yml. */
private AttributeDefinitions attributeDefinitions = new AttributeDefinitions();

/** Calculator alias → public names it exposes. Defined in calculator-attributes.yml. */
private Map<String, List<String>> exposedAttributes = new HashMap<>();

@Getter
@Setter
public static class AttributeDefinitions {
    private Map<String, String> parameters = new LinkedHashMap<>();
    private Map<String, String> results = new LinkedHashMap<>();
    private Map<String, String> metadata = new LinkedHashMap<>();
}
```

Javadoc: add both to the `<ul>` and state that they live in `calculator-attributes.yml`, not
`application.yml`.

### Step 8 — `config/AttributeExposure` (new)

```java
/** Compiled attribute selection for one calculator alias: public name → pointer, per response group. */
public record AttributeExposure(Map<String, JsonPointer> parameters,
                                Map<String, JsonPointer> results,
                                Map<String, JsonPointer> metadata) {

    public static final AttributeExposure NONE = new AttributeExposure(Map.of(), Map.of(), Map.of());

    public boolean isEmpty() {
        return parameters.isEmpty() && results.isEmpty() && metadata.isEmpty();
    }
}
```

Internal only — never serialized or cached.

### Step 9 — `CalculatorNameResolver`

Replace `@RequiredArgsConstructor` with an explicit constructor that compiles and validates once.
Existing `new CalculatorNameResolver(props)` call sites (tests) keep compiling; with no attribute
config the compiled map is empty.

```java
@Slf4j
@Service
public class CalculatorNameResolver {

    private static final Pattern PUBLIC_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9]*");
    private static final String DEFS = "observability.calculator.attribute-definitions.";
    private static final String EXPOSED = "observability.calculator.exposed-attributes.";

    private final CalculatorProperties calculatorProperties;
    private final Map<String, AttributeExposure> exposureByAlias;

    public CalculatorNameResolver(CalculatorProperties calculatorProperties) {
        this.calculatorProperties = calculatorProperties;
        this.exposureByAlias = compileExposures(calculatorProperties);
    }

    // ... existing methods unchanged ...

    /** Compiled exposure for an alias or real calculator_name; {@link AttributeExposure#NONE} when unconfigured. */
    public AttributeExposure exposedAttributesOf(String nameOrAlias) {
        if (nameOrAlias == null) return AttributeExposure.NONE;
        return exposureByAlias.getOrDefault(toAlias(nameOrAlias), AttributeExposure.NONE);
    }

    public boolean hasExposedAttributes(String nameOrAlias) {
        return !exposedAttributesOf(nameOrAlias).isEmpty();
    }

    private static Map<String, AttributeExposure> compileExposures(CalculatorProperties props) {
        CalculatorProperties.AttributeDefinitions defs = props.getAttributeDefinitions();
        Map<String, JsonPointer> parameters = compileGroup("parameters", defs.getParameters());
        Map<String, JsonPointer> results    = compileGroup("results", defs.getResults());
        Map<String, JsonPointer> metadata   = compileGroup("metadata", defs.getMetadata());

        Map<String, AttributeExposure> byAlias = new HashMap<>();
        props.getExposedAttributes().forEach((alias, names) -> {
            for (String name : names) {
                if (!parameters.containsKey(name) && !results.containsKey(name) && !metadata.containsKey(name)) {
                    throw new IllegalStateException(EXPOSED + alias + " selects '" + name
                            + "', which no attribute-definitions group defines");
                }
            }
            if (!props.getAliases().containsKey(alias)) {
                log.warn("event=calculator.attributes.config outcome=unknown_alias alias={} "
                        + "reason=not_in_aliases_for_active_profile", alias);
            }
            AttributeExposure exposure = new AttributeExposure(
                    select(parameters, names), select(results, names), select(metadata, names));
            if (!exposure.isEmpty()) {
                byAlias.put(alias, exposure);
            }
        });
        return Map.copyOf(byAlias);
    }

    private static Map<String, JsonPointer> compileGroup(String group, Map<String, String> defs) {
        Map<String, JsonPointer> compiled = new LinkedHashMap<>();
        defs.forEach((name, pointer) -> {
            String where = DEFS + group + "." + name;
            if (!PUBLIC_NAME.matcher(name).matches()) {
                throw new IllegalStateException(where + ": public name must match " + PUBLIC_NAME.pattern());
            }
            if (pointer == null || pointer.isEmpty()) {
                throw new IllegalStateException(where + ": empty pointer would expose the whole column");
            }
            try {
                compiled.put(name, JsonPointer.compile(pointer));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(where + ": invalid JSON Pointer '" + pointer + "'", e);
            }
        });
        return compiled;
    }

    /** Group entries for the selected names, in selection order. */
    private static Map<String, JsonPointer> select(Map<String, JsonPointer> group, List<String> names) {
        Map<String, JsonPointer> selected = new LinkedHashMap<>();
        for (String name : names) {
            JsonPointer pointer = group.get(name);
            if (pointer != null) selected.put(name, pointer);
        }
        return Collections.unmodifiableMap(selected);
    }
}
```

A thrown `IllegalStateException` fails bean creation → startup failure with the property path in
the message. `exposedAttributesOf` is called per run; `toAlias` is a linear scan over ~11 aliases —
negligible.

### Step 10 — `dto/response/RunAttributes` (new)

```java
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record RunAttributes(Map<String, Object> parameters,
                            Map<String, Object> results,
                            Map<String, Object> metadata) {

    /** Projects the run's three JSONB columns; null when the exposure is empty or nothing resolved. */
    public static RunAttributes from(CalculatorRun run, AttributeExposure exposure) {
        if (exposure.isEmpty()) return null;
        Map<String, Object> parameters = project(run.getRunParameters(), exposure.parameters());
        Map<String, Object> results    = project(run.getRunResults(), exposure.results());
        Map<String, Object> metadata   = project(run.getAdditionalAttributes(), exposure.metadata());
        return allEmpty(parameters, results, metadata) ? null : new RunAttributes(parameters, results, metadata);
    }

    /** Name/value pairs identical across every split; null when nothing is shared. */
    public static RunAttributes common(List<RunAttributes> splits) {
        if (splits.isEmpty() || splits.stream().anyMatch(Objects::isNull)) return null;
        Map<String, Object> parameters = intersect(splits, RunAttributes::parameters);
        Map<String, Object> results    = intersect(splits, RunAttributes::results);
        Map<String, Object> metadata   = intersect(splits, RunAttributes::metadata);
        return allEmpty(parameters, results, metadata) ? null : new RunAttributes(parameters, results, metadata);
    }

    /** RFC 6901 walk over Jackson-deserialized JSONB (Map / List / scalar). Missing or JSON null → null. */
    static Object resolve(Object root, JsonPointer pointer) {
        Object node = root;
        for (JsonPointer p = pointer; !p.matches(); p = p.tail()) {
            if (node instanceof Map<?, ?> map) {
                node = map.get(p.getMatchingProperty());
            } else if (node instanceof List<?> list) {
                int i = p.getMatchingIndex();
                node = i >= 0 && i < list.size() ? list.get(i) : null;
            } else {
                return null;   // pointer continues past a scalar
            }
            if (node == null) return null;
        }
        return node;
    }

    private static Map<String, Object> project(Map<String, Object> column, Map<String, JsonPointer> pointers) {
        if (column == null || pointers.isEmpty()) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        pointers.forEach((name, pointer) -> {
            Object value = resolve(column, pointer);
            if (value != null) out.put(name, value);
        });
        return out;
    }

    private static Map<String, Object> intersect(List<RunAttributes> splits,
                                                 Function<RunAttributes, Map<String, Object>> group) {
        Map<String, Object> out = new LinkedHashMap<>(orEmpty(group.apply(splits.get(0))));
        for (RunAttributes split : splits.subList(1, splits.size())) {
            Map<String, Object> other = orEmpty(group.apply(split));
            out.entrySet().removeIf(e -> !Objects.equals(e.getValue(), other.get(e.getKey())));
        }
        return out;
    }

    private static Map<String, Object> orEmpty(Map<String, Object> m) {
        return m != null ? m : Map.of();
    }

    @SafeVarargs
    private static boolean allEmpty(Map<String, Object>... groups) {
        return Arrays.stream(groups).allMatch(g -> g == null || g.isEmpty());
    }
}
```

- No instance `isEmpty()`/`get*()` helpers on this record: Jackson treats `is*`/`get*` methods as
  properties and would emit e.g. `"empty": false`.
- Groups are null after a cache round-trip when they were omitted (`NON_EMPTY`); `orEmpty` covers it.
- `JsonPointer.matches()` is true for the empty (tail-end) pointer; `getMatchingProperty()` is the
  unescaped segment (`databricks.num_workers`, `a/b` from `a~1b`); `getMatchingIndex()` is `-1` for
  a non-numeric segment.

### Step 11 — `/batch/runs` DTO and `CalculatorStateService`

**`CalculatorBatchRunsResponse.RunEntry`** — add `RunAttributes attributes` as the last component.
The class-level `@JsonInclude(NON_NULL)` omits it when null. All construction goes through the
builder (no `new RunEntry(` call sites — verified), so nothing else changes; `mergeEntries`'
`toBuilder()` and `ExpectedRunsService` placeholders carry/leave it as appropriate.

**`CalculatorStateService.getState`**:

```java
boolean withJsonb = missNames.stream().anyMatch(nameResolver::hasExposedAttributes);
Map<String, List<CalculatorRun>> runsByName = runRepository
        .findAllRunsByDateAndDimension(reportingDate, frequency, rn, missNames, withJsonb)
        ...
```

**`toRunEntry`** — add to the builder:

```java
.attributes(RunAttributes.from(run, nameResolver.exposedAttributesOf(run.getCalculatorName())))
```

Standalone reruns pass `latest`, so attributes come from the latest attempt.

**`collapseSplitGroup`** — `rep` carries no JSONB, so `toRunEntry(rep, …)` yields null attributes;
replace the return with:

```java
RunAttributes common = RunAttributes.common(splits.stream()
        .map(s -> RunAttributes.from(s, nameResolver.exposedAttributesOf(s.getCalculatorName())))
        .toList());   // Stream.toList() permits null elements
return toRunEntry(rep, entryName).toBuilder().attributes(common).build();
```

Entries are always cached with attributes — `obs:state:*` key, TTLs and invalidation untouched.

### Step 12 — `/runs` DTO and `RunQueryService`

**`RunHistoryResponse.RunEntry`** — add last component
`@JsonInclude(JsonInclude.Include.NON_NULL) RunAttributes attributes` (other components keep
explicit nulls, as today). Change `from(CalculatorRun run)` → `from(CalculatorRun run, RunAttributes attributes)`
and add:

```java
/** Copy without attributes — used when the request did not ask for include=attributes. */
public RunEntry withoutAttributes() {
    return attributes == null ? this : new RunEntry(runId, calculatorId, frequency, reportingDate, runNumber,
            region, runType, status, startTime, endTime, durationMs, expectedDurationMs, slaTime, slaBand,
            slaBreached, odRunId, consRunVersion, calculatorRunVersion, null);
}
```

**`RunHistoryResponse`** — add `public RunHistoryResponse withoutAttributes()` mapping each entry.
Neither method name is a bean getter, so neither is serialized (same as `anyLive()`).

**`RunQueryService`** — inject `CalculatorNameResolver`:

```java
AttributeExposure exposure = nameResolver.exposedAttributesOf(calculatorName);
return historyCache.getOrLoad(calculatorName, frequency, reportingDate, odRunId, limit, nocache,
        () -> new RunHistoryResponse(calculatorName,
                runRepository.findRunHistory(calculatorName, frequency, reportingDate, odRunId, limit).stream()
                        .map(run -> RunHistoryResponse.RunEntry.from(run, RunAttributes.from(run, exposure)))
                        .toList()));
```

Cache key unchanged; snapshots always carry attributes.

### Step 13 — `RunQueryController`

Both routes get:

```java
@Parameter(description = "Optional sections to include. Supported: attributes")
@RequestParam(required = false) Set<String> include
```

```java
/** Only "attributes" is supported; any other token → 400 via the existing IllegalArgumentException path. */
private static boolean includesAttributes(Set<String> include) {
    if (include == null || include.isEmpty()) return false;
    for (String token : include) {
        if (!"attributes".equals(token)) {
            throw new IllegalArgumentException("Unsupported include value '" + token + "' (supported: attributes)");
        }
    }
    return true;
}
```

Validate at the top of each handler (before any DB/cache work). Strip after the cache read:

- `/batch/runs`, after `padToExpected`:
  ```java
  if (!withAttributes) calculators = stripAttributes(calculators);

  private static Map<String, CalculatorEntry> stripAttributes(Map<String, CalculatorEntry> calculators) {
      Map<String, CalculatorEntry> out = new LinkedHashMap<>();
      calculators.forEach((key, e) -> out.put(key, e.runs() == null ? e : new CalculatorEntry(
              e.calculatorName(), e.calculatorId(),
              e.runs().stream().map(r -> r.attributes() == null ? r : r.toBuilder().attributes(null).build()).toList())));
      return out;
  }
  ```
- `/runs`: `if (!withAttributes) response = response.withoutAttributes();`

Update both `@Operation` descriptions (`include=attributes`, configured per calculator, groups).

### Step 14 — Docs

- `docs/consumer-api.md` §1/§3: `include` parameter on both routes; sample response; `attributes`
  field table (`parameters`/`results`/`metadata`, omission rules, native types, split intersection);
  note that `taskVersion` may be under `results` or `metadata`; a public name means the same thing
  for every calculator.
- `CLAUDE.md`: API rows for both routes (`include=attributes`); Key Conventions — the storage/
  ingestion contract table, reserved `start`/`completion` keys, completion uses the targeted
  `completeRun` UPDATE and `upsert` is start-only; calculator config — mapping lives in
  `calculator-attributes.yml` (imported, not optional, keys defined only there, alias-keyed,
  startup validation). Producer-side mapping for Airflow documented with the contract table.

### Behaviour notes

- Cache staleness after a deploy or mapping change, per the implementation: `obs:state` entries
  live 30 s (any RUNNING) / 60 s (NOT_STARTED or empty) / 5 min (terminal with failure or breach) /
  **1 h** (terminal clean — `CalculatorStateCacheService.TTL_TERMINAL_CLEAN`; `CLAUDE.md` and
  `consumer-api.md` say 4 h and are stale); run-history snapshots 10 s (empty/RUNNING) / 5 min
  (terminal). Run events evict earlier; `nocache=true` rebuilds.
- Rolling deploy: old pods ignore the new request fields and never write the column; a run
  completed on an old pod during rollout has no results. Cache entries written by new pods carry
  `attributes`; old pods read them with Boot's default `ObjectMapper` (unknown properties ignored).
  Entries written by old pods lack `attributes` until TTL/eviction.
- `/batch/runs` cache-miss with `withJsonb` loads three JSONB columns for one partition, roughly
  5–10 KB per run.
- `findRunHistory` already uses `SELECT_WITH_JSONB`, so it now also loads `run_results` for every
  history query, including unconfigured calculators (~3–5 KB per run typical, 64 KB max; ~0.5 MB at
  `limit=100` typical). Accepted; snapshots are cached.

## Tests

Fixtures: the MEG start/completion pair is used **together** as the one real lifecycle. The
version-control and CALC_EVENT completions are each tested separately against an explicitly
synthetic matching start built in the test — no cross-calculator pairing. Fixture maps are read
from the sample JSON files with `ObjectMapper`.

**New**

- **`config/CalculatorAttributesConfigTest`** — `ApplicationContextRunner` +
  `ConfigDataApplicationContextInitializer` (loads `application.yml` and its import) +
  `@EnableConfigurationProperties(CalculatorProperties.class)`:
  - the import is loaded: `exposedAttributes` contains `capital` and `lnf-version-control`;
  - camelCase keys survive binding (`calcType`, `requestedWorkerCount`) and values are verbatim
    (`/start/overrides/databricks.num_workers`);
  - `new CalculatorNameResolver(props)` constructs without exception — every shipped pointer
    compiles and every selected name is defined.
- **`dto/response/RunAttributesTest`**:
  - literal dotted key: `/start/overrides/databricks.num_workers` → `"4"` (still a String);
  - nested pointer `/completion/taskVersion` → `"51.0.1"`; `~1` escape (`/a~1b` → key `a/b`);
  - object returned whole (`/outputVolumes`, numbers stay numeric); array index `/companyCodes/0` → `"K946"`;
  - missing path, JSON null, index out of range, pointer past a scalar (`/calcType/x`) → omitted;
  - `taskVersion` resolves from `results` (CALC_EVENT sample) and from `metadata` (MEG completion);
  - unconfigured names never appear; nothing resolved → `null`; `AttributeExposure.NONE` → `null`;
  - `common`: identical pairs kept, differing dropped, any null split → `null`, single split → itself;
  - serialization: empty groups omitted, no `"empty"` property emitted.

**Extended**

- **`CalculatorNameResolverTest`**: exposure by alias and by real name (`capitalcalcmedium`) is the
  same; unknown and null → `NONE`; a name defined in two groups is selected into both; selection
  order preserved; empty selection list → `hasExposedAttributes` false. Startup failures (constructor
  throws `IllegalStateException` naming the property path): undefined selected name, invalid pointer
  (`calcType` without `/`), empty pointer, public name `run-category`. Unknown alias key → constructs
  and logs the WARN (`OutputCaptureExtension`, as in `SlaMonitoringCacheStartupWarningTest`).
- **`RunIngestionServiceTest`**: `completeRun` receives typed PGobjects (null map → null value;
  `{}` → `"{}"`); > 64 KB (UTF-8 bytes) → `DomainValidationException`, `completeRun` never called;
  replay of a terminal run → `completeRun` never called, payload ignored; events carry the row
  returned by `completeRun`.
- **`CalculatorRunRepositoryJdbcTest`** (Testcontainers):
  - MEG pair: start via `upsert` with `runParameters` = start `context.data`,
    `additionalAttributes` = `{ odRunId, start: additionalData }`; `completeRun` with both maps →
    start `overrides` still present; `odRunId` and other root fields survive; `run_parameters`
    identical before/after; returned row equals a fresh `findByIdForUpdate` read;
  - null vs `{}` matrix: null `runResults` → column NULL; `{}` → `{}`; null completion meta → no
    `completion` key; `{}` → `completion: {}`; merge onto NULL `additional_attributes`;
  - `completeRun` on a non-RUNNING row → `IllegalStateException` (0 rows);
  - version-control (synthetic start): `run_results.inputAttributes` stays nested; numeric
    `modulesVersion: 1` round-trips as a number; differing start/echo values both preserved;
  - `findAllRunsByDateAndDimension(…, true)` maps all three JSONB columns; `(…, false)` maps none.
- **`CalculatorStateServiceTest`**: `withJsonb` true iff any miss name is configured (mixed batch
  → true); standalone rerun uses latest attempt's attributes; collapsed split group shows common
  attributes only; NOT_STARTED has none.
- **`RunQueryServiceTest`**: configured calculator → entries carry attributes; unconfigured → null.
- **`RunQueryControllerTest`**: both routes — no `include` → no `attributes` key in JSON;
  `include=attributes` → present; `include=bogus` and `include=attributes,bogus` → 400.
- **`CalculatorStateCacheServiceTest`**, **`CalculatorRunHistoryCacheServiceTest`**: legacy cached
  JSON without `attributes` still deserializes (field null); a value with attributes round-trips.

**Mechanical updates to existing tests**

- `CalculatorStateServiceTest`: its 27 `findAllRunsByDateAndDimension` references (stubs and
  verifies) gain a 5th matcher (`anyBoolean()`); `setUp` adds
  `lenient().when(nameResolver.exposedAttributesOf(any())).thenReturn(AttributeExposure.NONE)` —
  the resolver is a Mockito mock and would otherwise return null into `RunAttributes.from`.
- `CalculatorRunRepositoryDimensionalTest` (4) and `CalculatorRunRepositoryJdbcTest` (2): pass `false`.
- `RunHistoryResponse.RunEntry` constructor calls append `null`:
  `CalculatorRunHistoryCacheIntegrationTest:116`, `CalculatorRunHistoryCacheServiceTest:311,315,404`,
  `RunQueryControllerTest:642`.
- `RunQueryServiceTest`: construct with a resolver mock stubbed to `NONE`.
- `RunIngestionServiceTest`: add a real `new JsonbConverter(new ObjectMapper())` to construction.
- `AnalyticsCacheServiceTest`, `AnalyticsServiceTest`: unchanged (`new CalculatorNameResolver(props)`
  with no attribute config compiles to empty).

## Verification

1. `docker compose up -d`; `SPRING_PROFILES_ACTIVE=local mvn clean test`.
2. `\d calculator_runs` in psql shows `run_results jsonb` on the parent and a partition.
3. Run locally (`SPRING_PROFILES_ACTIVE=local`). Startup log has no
   `event=calculator.attributes.config` WARN (both keys aliased locally). Negative check: temporarily
   add `bogus` to the `capital` selection → startup fails naming
   `observability.calculator.exposed-attributes.capital`; revert.
4. `POST /runs/start` from `sample_meg_event_start.json` with `calculatorName=capitalcalc` (local
   alias), `runParameters` = `context.data`, `additionalAttributes` = `{"odRunId":"od-1","start": <additionalData>}`.
   `GET /batch/runs?keys=capitalcalc&reporting_date=2026-11-11&include=attributes` →
   `parameters` and `metadata.requestedWorkerCount: "4"`.
5. `POST /runs/{id}/complete` from `sample_meg_event_complete.json` (`runResults` = `context.data`,
   `additionalAttributes` = `event.additionalData`). Next GET adds `metadata.taskVersion: "51.0.1"`.
   In psql: `run_parameters` unchanged; `additional_attributes` has `odRunId`, `start.overrides` and
   `completion`; no `jobLink`/`OS_USER` in the API response.
   Repeat the `/complete` with a different payload → 200, stored values unchanged.
6. Synthetic MONTHLY start for `calculatorName=lnfversioncontrolcalc`, `reportingDate=2026-10-31`,
   then complete with `context.data` of `sample_version_control_event_complete.json` →
   `GET /calculators/lnfversioncontrolcalc/runs?include=attributes` shows `results.consoRunId`,
   `results.reportedProcess`; `run_results->'inputAttributes'` still nested in psql.
7. Two split runs sharing a `correlationId` with different volumes → collapsed `/batch/runs` entry
   omits the volumes; `/calculators/{name}/runs?include=attributes` shows them per split.
8. Without `include`, and on an unconfigured calculator (`portfoliocalc`) → no `attributes`.
   `include=foo` → 400.

## Decisions and assumptions

- **Resolved 2026-11-20 — dev name for LNF version control:** `lnfversioncontrolcalcdev`.
- **Resolved 2026-11-20 — migration number:** this plan takes `V13`; `2026-11-19-cost-design.md`
  §F.1 renumbers to `V14`.
- **Assumption:** Airflow sends `calculatorName` = the producer's `class` value, so the prod name
  is `lnfversioncontrolcalc` (consistent with prod `capital` aliases matching `class` values).

## Changes in revision 4

| Change | Reason |
|---|---|
| Config split into `attribute-definitions` (each name once) + `exposed-attributes` (alias → names) | Rev 3 repeated name → pointer per calculator; size grew with calculators × attributes |
| JSON Pointer kept; comma-separated key lists and recursive key search rejected (§3.1) | Flat keys can't reach nested sources or rename; search returns wrong values where a key repeats within a column (`actorSystem`) |
| Mapping moved to `calculator-attributes.yml` via `spring.config.import` (not optional, keys only there) | Consumer contract vs service tuning — different change cadence and reviewers; keeps `application.yml` focused |
| Startup validation: undefined selected name, empty pointer, invalid public name fail; unknown alias key WARNs | Rev 3 validated pointer syntax only; a typo or unmatched key exposed nothing silently |
| `lnf-version-control` alias added (prod `lnfversioncontrolcalc`, dev `lnfversioncontrolcalcdev`, local) | Rev 3's sample key never matched — no alias existed in any profile |
| Local verification posts the MEG sample as `capitalcalc` | Local `capital` alias is `[capitalcalc]` only; rev 3 steps 3–4 would show nothing |
| `completeRun` uses `RETURNING *`; the returned row is published | Events carry stored state; no Java copy of the JSONB merge |
| Size guard counts UTF-8 bytes; runs after the replay check | `String.length()` undercounts multi-byte text; replays stay idempotent |
| `V13` assigned to this plan; cost design moves to `V14` | Both plans claimed `V13`; decided 2026-11-20 |
| `consumer-api.md` states `taskVersion` may be under `results` or `metadata` | No-fallback rule leaves the choice to the consumer |
| `findRunHistory` cost of the extra JSONB column stated | It shares `SELECT_WITH_JSONB` |
| Full implementation detail per step; test call-site updates listed | Requested for implementation |

## Changes in revision 3

| Change | Reason |
|---|---|
| Start `event.additionalData` → `additional_attributes.start`, documented as producer mapping (no new request field) | `sample_meg_event_start` carries launch `overrides` outside `context.data` |
| Dot paths → JSON Pointer | The override key `databricks.num_workers` contains a literal dot, which dot paths could not address |
| `taskVersion` mapped in both `results` and `metadata` | It arrives in `context.data` for CALC_EVENT and in `additionalData` for task-events |
| Echoed inputs: kept nested in `run_results`, exposed only by explicit `reported*` mapping, never backfilled | `inputAttributes` in the version-control sample; preserves requested-vs-reported differences |
| `requestedWorkerCount` kept as string `"4"`, distinct from `numberOfExecutors` | A requested setting is not proof of runtime resources |
| null = absent, `{}` = supplied empty; tests and SQL aligned | Revision 2 treated empty and null alike in places |
| Removed the "runType changes INTRA → BATCH" claim | Those values came from different runs, not a verified pair |
| Cache TTLs restated from code (state clean-terminal is 1 h, not 4 h; history live is 10 s) | Revision 2 repeated stale documentation |
| `withJsonb` cost stated for mixed batches | One flag loads JSONB for all rows in the query |
| Fixtures: MEG pair together; other completions with synthetic starts | The CALC_EVENT samples belong to different calculators |
