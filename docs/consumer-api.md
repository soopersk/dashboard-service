# Consumer Query API

This guide is the external-team contract for the two **consumer-facing query endpoints** that power dashboards and run-history views:

| Endpoint | Purpose | View |
|----------|---------|------|
| [`GET /api/v1/calculators/batch/runs`](#1-get-apiv1calculatorsbatchruns) | Point-in-time **dimensional run state** for a set of calculators on one reporting date | Dashboard grid |
| [`GET /api/v1/analytics/calculators/{name}/executions`](#2-get-apiv1analyticscalculatorsnameexecutions) | **Raw execution history** for one calculator over a lookback window | Run-history / performance chart |

Both endpoints:

- Require `Authorization: Basic <base64>` and `X-Tenant-Id` headers. All data is tenant-scoped.
- Identify calculators by their **readable `calculator_name`** (unique per tenant), never the internal UUID `calculator_id`.
- Are Redis-cached and safe for high-frequency polling.
- Return all timestamps as **UTC ISO-8601 `Instant`s** (`2026-05-19T13:02:00Z`).

> **Status vs SLA status.** Every run carries two independent fields. `status` is the lifecycle (`RUNNING`, `SUCCESS`, `FAILED`, `TIMEOUT`, `CANCELLED`). `slaStatus` is the timeliness classification (`ON_TIME`, `LATE`, `VERY_LATE`). A `FAILED` run that missed its deadline reports `status: "FAILED"` **and** `slaStatus: "VERY_LATE"` — `slaStatus` is never a lifecycle value.

---

## 1. `GET /api/v1/calculators/batch/runs`

Returns the current run state for each requested calculator on a single reporting date, broken out by dimension (one entry per `region` or per `runType`). This is the dashboard feed: ask for the calculators in one section, render one row per dimensional run.

### Request

```
GET /api/v1/calculators/batch/runs
  ?reporting_date=2026-05-19         (required, ISO YYYY-MM-DD)
  &frequency=DAILY                   (DAILY | MONTHLY, default DAILY)
  &run_number=1                      (optional; omit for all buckets)
  &keys=capitalcalc|portfoliocalc    (required, pipe-separated calculator_name list)
Authorization: Basic <base64>
X-Tenant-Id: <tenant>
```

| Parameter | Type | Default | Constraints |
|-----------|------|---------|-------------|
| `reporting_date` | Date | — (required) | ISO `YYYY-MM-DD`; the partition key |
| `frequency` | String | `DAILY` | `DAILY` or `MONTHLY` (`D`/`M` short codes accepted) |
| `run_number` | String | none | A bucket value (`1`/`2`) to filter; omit or `""` for all buckets |
| `keys` | String | — (required) | Pipe (`|`)-separated `calculator_name` values. No UUIDs, no wildcards. Must contain at least one non-blank name |

### Response `200 OK`

`Cache-Control: max-age=30, private`

```json
{
  "reportingDate": "2026-05-19",
  "frequency": "DAILY",
  "runNumber": "1",
  "generatedAt": "2026-05-19T17:00:00Z",
  "calculators": {
    "capitalcalc": {
      "calculatorName": "capitalcalc",
      "runs": [
        {
          "runId": "run-wmap-001",
          "region": "WMAP",
          "status": "SUCCESS",
          "slaStatus": "ON_TIME",
          "startTime": "2026-05-19T13:02:00Z",
          "endTime": "2026-05-19T14:45:00Z",
          "estimatedStartTime": "2026-05-19T13:00:00Z",
          "estimatedEndTime": "2026-05-19T14:50:00Z",
          "sla": "2026-05-19T15:00:00Z",
          "durationMs": 6180000,
          "expectedDurationMs": 5400000,
          "slaBreached": false,
          "isRerun": false
        },
        {
          "runId": "run-ldnl-002",
          "region": "LDNL",
          "status": "FAILED",
          "slaStatus": "VERY_LATE",
          "startTime": "2026-05-19T13:02:00Z",
          "endTime": "2026-05-19T15:18:00Z",
          "estimatedStartTime": "2026-05-19T13:00:00Z",
          "estimatedEndTime": "2026-05-19T14:50:00Z",
          "sla": "2026-05-19T15:00:00Z",
          "durationMs": 8160000,
          "expectedDurationMs": 5400000,
          "slaBreached": true,
          "slaBreachReason": "Run status: FAILED",
          "isRerun": true
        }
      ]
    },
    "modelledexposurecalc": {
      "calculatorName": "modelledexposurecalc",
      "runs": [
        {
          "runId": "run-otc-001",
          "runType": "OTC",
          "status": "SUCCESS",
          "slaStatus": "ON_TIME",
          "startTime": "2026-05-19T17:03:00Z",
          "endTime": "2026-05-19T17:49:00Z",
          "estimatedStartTime": "2026-05-19T17:02:00Z",
          "estimatedEndTime": "2026-05-19T17:58:00Z",
          "sla": "2026-05-19T18:30:00Z",
          "durationMs": 2760000,
          "expectedDurationMs": 3360000,
          "slaBreached": false,
          "isRerun": false
        },
        {
          "runId": "run-sft-001",
          "runType": "SFT",
          "status": "RUNNING",
          "slaStatus": "ON_TIME",
          "startTime": "2026-05-19T17:05:00Z",
          "estimatedStartTime": "2026-05-19T17:02:00Z",
          "estimatedEndTime": "2026-05-19T17:58:00Z",
          "sla": "2026-05-19T18:30:00Z",
          "expectedDurationMs": 3360000,
          "slaBreached": false,
          "isRerun": false
        }
      ]
    },
    "portfoliocalc": {
      "calculatorName": "portfoliocalc",
      "runs": [
        {
          "slaStatus": "ON_TIME",
          "estimatedStartTime": "2026-05-19T16:02:00Z",
          "estimatedEndTime": "2026-05-19T17:05:00Z",
          "expectedDurationMs": 3780000,
          "slaBreached": false,
          "isRerun": false
        }
      ]
    },
    "newcalc": { "calculatorName": "newcalc", "runs": [] }
  }
}
```

The sample deliberately shows every entry shape a consumer must handle:

| Calculator | What it demonstrates |
|------------|----------------------|
| `capitalcalc` / `WMAP` | **Regional** calculator — a completed, on-time actual run (uses `region`) |
| `capitalcalc` / `LDNL` | An actual run that **failed and breached** — `status: FAILED`, `slaStatus: VERY_LATE`, `isRerun: true` |
| `modelledexposurecalc` / `OTC` | **Typed** calculator — a completed actual run (uses `runType`) |
| `modelledexposurecalc` / `SFT` | A **RUNNING** typed run — `endTime` and `durationMs` are absent until it completes |
| `portfoliocalc` | A **synthetic NOT_STARTED** entry — no run yet, projected from history (no `runId`/`status`) |
| `newcalc` | A **brand-new calculator** with no history — empty `runs` |

### Response contract

**Envelope:**

| Field | Type | Notes |
|-------|------|-------|
| `reportingDate` | Date | Echo of request |
| `frequency` | String | Echo of request (`DAILY`/`MONTHLY`) |
| `runNumber` | String | Echo of request; `null` when not supplied |
| `generatedAt` | Instant | When the response was assembled (UTC) |
| `calculators` | Map | Keyed by `calculator_name`; **every requested key is present** |

**`CalculatorEntry`** (each map value):

| Field | Type | Notes |
|-------|------|-------|
| `calculatorName` | String | The readable name (no `calculatorId` is exposed) |
| `runs` | array of `RunEntry` | See states below |

**`RunEntry`** (`@JsonInclude(NON_NULL)` — null fields are omitted):

| Field | Type | Notes |
|-------|------|-------|
| `runId` | String | Absent on synthetic NOT_STARTED entries |
| `region` | String | Present for regional calculators only |
| `runType` | String | Present for typed calculators only |
| `status` | String | `RUNNING`/`SUCCESS`/`FAILED`/`TIMEOUT`/`CANCELLED`; absent on NOT_STARTED |
| `slaStatus` | String | `ON_TIME` / `LATE` / `VERY_LATE` |
| `startTime` | Instant | Earliest physical start (UTC) |
| `endTime` | Instant | Latest physical end; absent while RUNNING |
| `estimatedStartTime` | Instant | Stored estimate (see precedence below) |
| `estimatedEndTime` | Instant | Stored estimate |
| `sla` | Instant | Duration-derived deadline (not an upstream wall-clock time) |
| `durationMs` | Long | Actual duration; absent while RUNNING |
| `expectedDurationMs` | Long | Configured baseline, immutable after first INSERT |
| `slaBreached` | Boolean | Persisted breach flag |
| `slaBreachReason` | String | Present only when breached |
| `isRerun` | boolean | `true` when this dimension was re-triggered |

### The three `runs` states (important)

How `runs` is populated depends on whether the calculator ran and whether it has history:

1. **Has actual runs** → one `RunEntry` per dimension with `runId` and `status` populated.
2. **No run on the date, but has history/profile** → a **single synthetic NOT_STARTED entry**. It has **no `runId` and no `status`**, `slaStatus: "ON_TIME"`, and `estimatedStartTime` / `estimatedEndTime` / `expectedDurationMs` projected from the rolling profile (or the most recent run's stored estimates).
3. **Brand-new calculator with no history at all** → `runs` is an empty list `[]`.

> **Detect "not started" by the absence of `runId`/`status`, not by an empty list.** An empty list only means "no history to estimate from".

### Field semantics

- `region` and `runType` are **mutually exclusive** per calculator — a calculator uses one or neither, never both.
- `slaStatus` is duration-based and always consistent with `slaBreached`: `ON_TIME` only when the run is neither flagged breached nor past its derived deadline; `LATE` within one band-gap (default 15 min) past `sla`; `VERY_LATE` beyond. A `RUNNING` run already flagged `slaBreached=true` reports `LATE`/`VERY_LATE`, never `ON_TIME`.
- Start-request semantics differ from query-response semantics: request `slaTime` on `POST /runs/start` is a duration-style baseline input, while query `sla`/`slaTime` fields are derived deadline instants.
- `estimatedStartTime` / `estimatedEndTime` / `sla` are frozen at run start. `estimatedEndTime` falls back from an explicit request value to the resolved baseline (`slaTime` duration, `expectedDurationMs`, or profile average).
- `isRerun: true` is set when more than one attempt exists for that `(region, runType)`. Parallel splits sharing a `correlationId` are collapsed into one entry (worst-status-wins) and are **not** marked as reruns.

### Caching

Cached per `(calculatorName, reportingDate, frequency, runNumber)` with state-aware TTL. Partial hits are supported — only names absent from cache hit the database. Invalidation is TTL-only; staleness is bounded to ≤30s while any run is active.

| Run state | TTL |
|-----------|-----|
| Any RUNNING | 30 seconds |
| NOT_STARTED or empty | 60 seconds |
| Terminal with failure or SLA breach | 5 minutes |
| Terminal clean | 4 hours |

### cURL

```bash
curl -u admin:admin -H "X-Tenant-Id: tenant1" \
  "http://localhost:8080/api/v1/calculators/batch/runs?reporting_date=2026-05-19&frequency=DAILY&run_number=1&keys=capitalcalc|portfoliocalc|grportfoliocalc"
```

---

## 2. `GET /api/v1/analytics/calculators/{name}/executions`

Returns every **physical run** of one calculator over a lookback window as an independent row. Unlike `/batch/runs`, there is **no grouping** — split runs sharing a `correlationId` appear as separate rows. Use this for run-history tables and actual-vs-expected performance charts.

### Request

```
GET /api/v1/analytics/calculators/portfoliocalc/executions
  ?days=30            (1-365, default 30)
  &frequency=DAILY    (DAILY | MONTHLY, default DAILY)
  &run_number=1       (optional; omit for all buckets)
Authorization: Basic <base64>
X-Tenant-Id: <tenant>
```

| Parameter | Type | Default | Constraints |
|-----------|------|---------|-------------|
| `name` (path) | String | — (required) | The readable `calculator_name` |
| `days` | int | `30` | Min 1, Max 365 — lookback window |
| `frequency` | String | `DAILY` | `DAILY` or `MONTHLY` |
| `run_number` | String | none | Bucket filter (`1`/`2`); omit or `""` for all buckets |

### Response `200 OK`

`Cache-Control: max-age=60, private`

```json
{
  "calculatorId": "portfoliocalc",
  "calculatorName": "portfoliocalc",
  "frequency": "DAILY",
  "periodDays": 30,
  "meanDurationMs": 285000,
  "totalRuns": 29,
  "runningRuns": 1,
  "slaMetCount": 24,
  "lateCount": 3,
  "veryLateCount": 2,
  "runs": [
    {
      "runId": "run-2026-05-13-001",
      "reportingDate": "2026-05-13",
      "startTime": "2026-05-13T04:02:15Z",
      "endTime": "2026-05-13T04:07:30Z",
      "durationMs": 315000,
      "status": "SUCCESS",
      "slaBreached": false,
      "slaStatus": "ON_TIME",
      "subRunIds": null,
      "estimatedStartTime": "2026-05-13T04:00:00Z",
      "slaTime": "2026-05-13T06:30:00Z",
      "runNumber": "1",
      "expectedDurationMs": 300000
    },
    {
      "runId": "run-2026-05-14-001",
      "reportingDate": "2026-05-14",
      "startTime": "2026-05-14T04:01:00Z",
      "endTime": null,
      "durationMs": null,
      "status": "RUNNING",
      "slaBreached": false,
      "slaStatus": "ON_TIME",
      "subRunIds": null,
      "estimatedStartTime": "2026-05-14T04:00:00Z",
      "slaTime": "2026-05-14T06:30:00Z",
      "runNumber": "1",
      "expectedDurationMs": 300000
    }
  ],
  "estimatedStartTime": "2026-05-13T04:00:00Z",
  "slaTime": "2026-05-13T06:30:00Z"
}
```

### Response contract

**Envelope:**

| Field | Type | Notes |
|-------|------|-------|
| `calculatorId` | String | Carries the readable name (lookup is by name; no UUID leak) |
| `calculatorName` | String | The readable name |
| `frequency` | String | Echo of request |
| `periodDays` | int | Echo of `days` |
| `meanDurationMs` | long | Mean over **completed** runs only (duration > 0); `0` when none |
| `totalRuns` | int | Terminal/evaluated runs only (excludes RUNNING) |
| `runningRuns` | int | Currently-running runs, tracked separately |
| `slaMetCount` | int | Terminal runs with `slaStatus = ON_TIME` |
| `lateCount` | int | Terminal runs with `slaStatus = LATE` |
| `veryLateCount` | int | Terminal runs with `slaStatus = VERY_LATE` |
| `runs` | array of `RunDataPoint` | One entry per physical row, oldest → newest |
| `estimatedStartTime` | Instant | Chart reference line — see note |
| `slaTime` | Instant | Chart reference line — see note |

**`RunDataPoint`:**

| Field | Type | Notes |
|-------|------|-------|
| `runId` | String | Physical run id |
| `reportingDate` | Date | `YYYY-MM-DD` |
| `startTime` | Instant | UTC |
| `endTime` | Instant | `null` while RUNNING |
| `durationMs` | Long | `null` while RUNNING |
| `status` | String | `RUNNING`/`SUCCESS`/`FAILED`/`TIMEOUT`/`CANCELLED` |
| `slaBreached` | Boolean | Persisted breach flag |
| `slaStatus` | String | `ON_TIME` / `LATE` / `VERY_LATE` |
| `subRunIds` | array | Always `null` here (no split-grouping on this endpoint) |
| `estimatedStartTime` | Instant | The run's own stored estimate |
| `slaTime` | Instant | The run's own derived deadline |
| `runNumber` | String | Run bucket (`1`/`2`) |
| `expectedDurationMs` | Long | Configured baseline, immutable; compare to `durationMs` |

> **Null handling differs from `/batch/runs`.** This payload is **not** `@JsonInclude(NON_NULL)`: null fields (e.g. `endTime`, `durationMs`, `subRunIds`) are present in the JSON as explicit `null` rather than omitted.

### Field semantics

- **No grouping:** each row of `calculator_runs` is one `RunDataPoint`. Two physical splits of the same logical run sharing a `correlationId` appear as two entries (contrast with `/batch/runs`, which collapses them). For the grouped/logical view, use `/run-performance`.
- `expectedDurationMs` is the originally-estimated duration (immutable, set at first INSERT). Use per-row `durationMs` vs `expectedDurationMs` for actual-vs-expected comparison. Use envelope `meanDurationMs` for the rolling trend baseline.
- **Envelope `estimatedStartTime` / `slaTime`** are chart reference lines from the cached **profile** — the typical historical-average start and a typical buffered deadline — so they stay stable across the window. When the calculator has fewer than the minimum sample size, they fall back to the most recent run's stored values. Per-row `estimatedStartTime` / `slaTime` are the run's own live values.

### Empty result

When no run matches the window, the endpoint returns `200 OK` (**not** `404`) with `calculatorName: null`, `runs: []`, and all counters `0`.

### cURL

```bash
curl -u admin:admin -H "X-Tenant-Id: tenant1" \
  "http://localhost:8080/api/v1/analytics/calculators/portfoliocalc/executions?days=30&frequency=DAILY"
```

---

## Choosing between the two

| Need | Use |
|------|-----|
| "What is the state of these calculators right now, for date D?" | `/batch/runs` |
| "Show one row per dimension (region/run-type), splits collapsed" | `/batch/runs` |
| "Show every physical run over the last N days" | `/executions` |
| "Compare each run's actual vs expected duration over time" | `/executions` |
| "Grouped/logical run-performance view (splits collapsed)" | `/run-performance` |

---

## Common errors

| HTTP Status | Cause |
|-------------|-------|
| `400 Bad Request` | Missing/invalid `reporting_date`, blank `keys`, `days` out of `1–365`, malformed parameter |
| `401 Unauthorized` | Missing or invalid `Authorization` header |
| `403 Forbidden` | `X-Tenant-Id` does not match the authenticated tenant |
| `500 Internal Server Error` | Unhandled server-side error |

> Neither endpoint returns `404` for "no data" — `/batch/runs` returns every requested key (possibly with empty/synthetic `runs`), and `/executions` returns a `200` empty envelope.
