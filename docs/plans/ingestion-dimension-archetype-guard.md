# Generalize the ingestion dimension guard to all three archetypes

## Context

`RunIngestionService`'s dimension guard ([RunIngestionService.java:96-106](../../src/main/java/com/company/observability/service/RunIngestionService.java#L96-L106))
only protects the `NONE` archetype: it nulls `region`/`run_type` and stashes them in
`additional_attributes` **only when** `dimensionOf(calculatorName) == Dimension.NONE`. For
`REGION`/`RUN_TYPE` calculators there is no equivalent check, and the dimension-selection line

```java
String dimension = region != null ? region : runType;
```

gives `region` unconditional precedence regardless of the calculator's configured archetype.

**Observed:** `modelledexposurecalcdev` (archetype `RUN_TYPE`, configured `run-types: [ETD, OTC,
SFT]`) has `calculator_sli_daily` rows with `dimension_value='GLB3'` (a region-shaped code, not a
run-type) and `dimension_value='ALL'` (no run_type at all). Root cause: a request that populated
`region` instead of (or in addition to) `run_type` — nothing stops `region` from winning as the
dimension for a `RUN_TYPE` calculator, and nothing requires `run_type` to be present at all. The
identical gap exists for `REGION` calculators (`capital`) with respect to a stray `run_type` —
not yet observed, but structurally the same risk, currently fully unguarded.

## Design

Generalize the existing `NONE`-only guard so **archetype** decides which field is legitimate, for
all three archetypes uniformly, instead of hardcoding the check to one of them:

```java
Map<String, Object> additionalAttributes = request.getAdditionalAttributes();
Dimension archetype = calculatorNameResolver.dimensionOf(request.getCalculatorName());

// Only the field matching the calculator's configured archetype is a legitimate dimension
// source. Anything else populated is a wrong-field mistake, not a second dimension — collapse
// it but preserve the original value in additional_attributes (no data loss, no schema change,
// matches the pre-existing NONE-only guard's behavior — now applied to all three archetypes).
if (archetype != Dimension.REGION && region != null) {
    additionalAttributes = putStray(additionalAttributes, "region", region);
    region = null;
}
if (archetype != Dimension.RUN_TYPE && runType != null) {
    additionalAttributes = putStray(additionalAttributes, "run_type", runType);
    runType = null;
}
if ((archetype == Dimension.REGION || archetype == Dimension.RUN_TYPE) && region == null && runType == null) {
    meterRegistry.counter(INGESTION_DIMENSION_MISSING,
            "calculator", request.getCalculatorName(), "archetype", archetype.name()).increment();
    log.warn("event=run.start.dimension outcome=missing calculator={} archetype={} runId={}",
            request.getCalculatorName(), archetype, request.getRunId());
}

if (runNumber != null && !calculatorNameResolver.isRunNumberAware(request.getCalculatorName())) {
    additionalAttributes = putStray(additionalAttributes, "run_number", runNumber);
    runNumber = null;
}

String dimension = region != null ? region : runType;
```

**Regression check — `NONE` archetype:** both `if` conditions (`archetype != REGION`,
`archetype != RUN_TYPE`) are true, so both `region` and `run_type` get stashed exactly as today.
Byte-for-byte same behavior; the missing-dimension counter never fires for `NONE` (its `if`
excludes `NONE` explicitly — a `NONE` calculator having neither field is the *correct*, expected
state, not an anomaly).

**New coverage:**
- `RUN_TYPE` calculator (`modelled-exposure`, `gemini-hedge`) receiving `region` → now stashed,
  `dimension` falls through to `run_type` (or `null`/`'ALL'` + a logged/counted anomaly if
  `run_type` is also absent). Closes the observed `GLB3` gap.
- `REGION` calculator (`capital`) receiving `run_type` → now stashed. Symmetric protection for the
  same class of mistake, not yet observed but previously fully unguarded.
- Either archetype missing its required field entirely → still accepted (writes are never
  rejected over a dimension-labeling problem — consistent with the existing guard's philosophy),
  but now observable via `obs.ingestion.dimension_missing{calculator,archetype}` instead of
  silently landing in a stray `'ALL'` bucket that's indistinguishable from legitimate data.

**Add to `ObservabilityConstants`:**
```java
public static final String INGESTION_DIMENSION_MISSING = "obs.ingestion.dimension_missing";
```

## Why not a 400 reject instead

Considered. The existing `NONE` guard deliberately chose "collapse + preserve + keep writing" over
hard rejection — `/runs/start` tracks an already-happening calculation; rejecting the write doesn't
stop the calculation, it just removes this service's visibility into it. That's a larger blind spot
than a mislabeled dimension. Introducing a 400 only for `REGION`/`RUN_TYPE` (while `NONE` stays
lenient) would also be an inconsistent contract for no clear reason.

`frequency`/`reporting_date` validation is intentionally the strict 400 counterexample — those are
structurally invalid inputs the service literally cannot interpret, not a labeling mistake with an
obvious correct fallback. If Airflow's wrong-field mistake needs to be forced to fail loudly instead
of silently corrected, that's a one-line change (`throw DomainValidationException` in the stray/
missing branches) — an easy escalation from this design if the soft guard turns out to mask a
problem instead of surfacing it.

## Out of scope

Validating `region`/`run_type` **values** against the configured lists (`WMAP, WMDE, ... / ETD,
OTC, SFT`) — e.g. flagging a typo'd `"ETC"`. Separate, larger concern (config-drift tolerance for
new business values); not what caused either observed bug. Revisit only if typo'd values start
showing up.

## Testing

- `RunIngestionServiceTest`: `RUN_TYPE` calculator + `region` populated, no `run_type` → run
  persists with `region=null`, `runType=null`, dimension `'ALL'`, `additional_attributes` contains
  `stray_region`, `obs.ingestion.dimension_missing{archetype=RUN_TYPE}` incremented.
- `RUN_TYPE` calculator + both `region` and `run_type` populated → `region` stashed, `run_type`
  kept as the dimension, no missing-dimension counter (a field *is* present).
- `REGION` calculator + `run_type` populated, `region` present → `run_type` stashed, `region` kept.
- `NONE` calculator (existing tests) — assert unchanged.
