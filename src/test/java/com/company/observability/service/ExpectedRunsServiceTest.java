package com.company.observability.service;

import com.company.observability.config.CalculatorProperties;
import com.company.observability.config.SlaProperties;
import com.company.observability.domain.CalculatorProfile;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.dto.response.CalculatorBatchRunsResponse.CalculatorEntry;
import com.company.observability.dto.response.CalculatorBatchRunsResponse.RunEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpectedRunsServiceTest {

    private static final List<String> ALL_REGIONS =
            List.of("WMAP", "WMDE", "ASIA", "WMUS", "AUNZ", "WMCH", "ZURI", "LDNL", "AMER", "EURO");
    private static final List<String> ALL_TYPES = List.of("ETD", "OTC", "SFT");

    private static final LocalDate DATE = LocalDate.of(2026, 3, 6);
    private static final Frequency FREQ = Frequency.DAILY;
    private static final String RUN_NUMBER = "2";

    // Zero-sample profile — not enough history; service falls back to template / bare placeholder.
    private static final CalculatorProfile NO_HISTORY =
            new CalculatorProfile("capital", "DAILY", "2", null, 0, 0, 0, 0);

    @Mock private CalculatorProfileService profileService;

    private final SlaProperties slaProps = new SlaProperties();
    private CalculatorProperties props;
    private ExpectedRunsService service;

    @BeforeEach
    void setUp() {
        props = new CalculatorProperties();
        props.setRegions(Map.of("capital", ALL_REGIONS));
        props.setRunTypes(Map.of("modelled-exposure", ALL_TYPES));
        // Default: no dimension-specific history — falls back to template/bare
        lenient().when(profileService.getProfile(anyString(), any(Frequency.class), anyString(), anyString()))
                .thenReturn(NO_HISTORY);
        service = new ExpectedRunsService(props, profileService, slaProps);
    }

    private Map<String, CalculatorEntry> pad(Map<String, CalculatorEntry> in) {
        return service.padToExpected(in, DATE, FREQ, RUN_NUMBER);
    }

    // ── Region-dimensioned padding ─────────────────────────────────────────────

    @Test
    void padsRegionsToFullSetWhenPartialRunsPresent() {
        List<RunEntry> sevenRuns = List.of(
                realRegionEntry("WMAP"), realRegionEntry("WMDE"), realRegionEntry("ASIA"),
                realRegionEntry("WMUS"), realRegionEntry("AUNZ"), realRegionEntry("WMCH"), realRegionEntry("ZURI")
        );
        CalculatorEntry existing = new CalculatorEntry("capital", "calc-id", sevenRuns);

        CalculatorEntry padded = pad(Map.of("capital", existing)).get("capital");

        assertThat(padded.runs()).hasSize(10);
        assertThat(padded.runs()).extracting(RunEntry::region).containsExactlyElementsOf(ALL_REGIONS);
    }

    @Test
    void syntheticPlaceholdersAreNotStartedWithCorrectRegion() {
        CalculatorEntry existing = new CalculatorEntry("capital", "calc-id",
                List.of(realRegionEntry("WMAP"), realRegionEntry("WMDE")));

        List<RunEntry> runs = pad(Map.of("capital", existing)).get("capital").runs();

        assertThat(runs.get(0).region()).isEqualTo("WMAP");
        assertThat(runs.get(0).status()).isEqualTo("SUCCESS");
        assertThat(runs.get(1).region()).isEqualTo("WMDE");
        assertThat(runs.get(1).status()).isEqualTo("SUCCESS");
        for (int i = 2; i < 10; i++) {
            assertThat(runs.get(i).status()).isEqualTo("NOT_STARTED");
            assertThat(runs.get(i).isRerun()).isFalse();
            assertThat(ALL_REGIONS).contains(runs.get(i).region());
        }
    }

    @Test
    void declaredOrderPreservedAcrossRealAndSyntheticEntries() {
        CalculatorEntry existing = new CalculatorEntry("capital", "calc-id",
                List.of(realRegionEntry("AMER"), realRegionEntry("EURO")));

        List<RunEntry> runs = pad(Map.of("capital", existing)).get("capital").runs();

        assertThat(runs).extracting(RunEntry::region).containsExactlyElementsOf(ALL_REGIONS);
        assertThat(runs.get(8).status()).isEqualTo("SUCCESS");   // AMER at index 8
        assertThat(runs.get(9).status()).isEqualTo("SUCCESS");   // EURO at index 9
    }

    @Test
    void allTenRegionsPresentReturnsInDeclaredOrder() {
        List<RunEntry> allRuns = ALL_REGIONS.stream().map(this::realRegionEntry).toList();
        CalculatorEntry existing = new CalculatorEntry("capital", "calc-id", allRuns);

        List<RunEntry> runs = pad(Map.of("capital", existing)).get("capital").runs();

        assertThat(runs).hasSize(10);
        assertThat(runs).extracting(RunEntry::region).containsExactlyElementsOf(ALL_REGIONS);
    }

    // ── Run-type-dimensioned padding ───────────────────────────────────────────

    @Test
    void padsRunTypesToFullSet_settingRunTypeNotRegion() {
        CalculatorEntry existing = new CalculatorEntry("modelled-exposure", "calc-id",
                List.of(realRunTypeEntry("ETD")));

        List<RunEntry> runs = pad(Map.of("modelled-exposure", existing)).get("modelled-exposure").runs();

        assertThat(runs).extracting(RunEntry::runType).containsExactlyElementsOf(ALL_TYPES);
        assertThat(runs).allSatisfy(r -> assertThat(r.region()).isNull());
        assertThat(runs.get(1).status()).isEqualTo("NOT_STARTED");
        assertThat(runs.get(2).status()).isEqualTo("NOT_STARTED");
    }

    // ── Case A: zero-run template reuse ───────────────────────────────────────

    @Test
    void zeroRuns_reusesUpstreamSyntheticAsTemplate_preservingProjectedSla() {
        Instant projectedSla = Instant.parse("2026-02-23T22:00:00Z");
        Instant estEnd = Instant.parse("2026-02-23T20:00:00Z");
        RunEntry template = RunEntry.builder()
                .status("NOT_STARTED")
                .slaStatus("ON_TIME")
                .estimatedEndTime(estEnd)
                .sla(projectedSla)
                .isRerun(false)
                .build();
        CalculatorEntry existing = new CalculatorEntry("capital", "calc-id", List.of(template));

        List<RunEntry> runs = pad(Map.of("capital", existing)).get("capital").runs();

        assertThat(runs).hasSize(10);
        assertThat(runs).extracting(RunEntry::region).containsExactlyElementsOf(ALL_REGIONS);
        // Every placeholder carries the projected SLA from the template
        assertThat(runs).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo("NOT_STARTED");
            assertThat(r.sla()).isEqualTo(projectedSla);
        });
    }

    @Test
    void zeroRunsWithNoTemplate_emitsBarePlaceholders() {
        CalculatorEntry existing = new CalculatorEntry("capital", null, List.of());

        List<RunEntry> runs = pad(Map.of("capital", existing)).get("capital").runs();

        assertThat(runs).hasSize(10);
        assertThat(runs).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo("NOT_STARTED");
            assertThat(r.slaStatus()).isEqualTo("ON_TIME");
            assertThat(r.sla()).isNull();
        });
    }

    // ── Case B: partial batch — per-dimension estimates + calculator-level deadline ──

    @Test
    void partialBatch_placeholdersGradeAgainstSiblingFrozenSla_beforeDeadline_onTime() {
        Instant futureDeadline = Instant.now().plusSeconds(3600);
        List<RunEntry> sevenRuns = List.of(
                realRegionEntryWithSla("WMAP", futureDeadline),
                realRegionEntry("WMDE"), realRegionEntry("ASIA"),
                realRegionEntry("WMUS"), realRegionEntry("AUNZ"),
                realRegionEntry("WMCH"), realRegionEntry("ZURI")
        );
        CalculatorEntry existing = new CalculatorEntry("capital", "calc-id", sevenRuns);

        List<RunEntry> runs = pad(Map.of("capital", existing)).get("capital").runs();

        // Missing regions LDNL, AMER, EURO must all be ON_TIME (deadline in future)
        List<RunEntry> placeholders = runs.stream()
                .filter(r -> r.status().equals("NOT_STARTED")).toList();
        assertThat(placeholders).hasSize(3);
        assertThat(placeholders).allSatisfy(r -> {
            assertThat(r.slaStatus()).isEqualTo("ON_TIME");
            assertThat(r.slaBreached()).isNull();
            assertThat(r.sla()).isEqualTo(futureDeadline);
        });
    }

    @Test
    void partialBatch_placeholdersGradeAgainstSiblingFrozenSla_pastDeadline_late() {
        Instant pastDeadline = Instant.now().minusSeconds(300); // within band gap
        List<RunEntry> sevenRuns = List.of(
                realRegionEntryWithSla("WMAP", pastDeadline),
                realRegionEntry("WMDE"), realRegionEntry("ASIA"),
                realRegionEntry("WMUS"), realRegionEntry("AUNZ"),
                realRegionEntry("WMCH"), realRegionEntry("ZURI")
        );
        CalculatorEntry existing = new CalculatorEntry("capital", "calc-id", sevenRuns);

        List<RunEntry> runs = pad(Map.of("capital", existing)).get("capital").runs();

        List<RunEntry> placeholders = runs.stream()
                .filter(r -> r.status().equals("NOT_STARTED")).toList();
        assertThat(placeholders).hasSize(3);
        assertThat(placeholders).allSatisfy(r -> {
            assertThat(r.slaStatus()).isEqualTo("LATE");
            assertThat(r.slaBreached()).isTrue();
            assertThat(r.sla()).isEqualTo(pastDeadline);
        });
    }

    @Test
    void partialBatch_placeholdersGradeAgainstSiblingFrozenSla_veryPastDeadline_veryLate() {
        Instant veryPastDeadline = Instant.now().minusSeconds(2000); // beyond band gap
        List<RunEntry> sevenRuns = List.of(
                realRegionEntryWithSla("WMAP", veryPastDeadline),
                realRegionEntry("WMDE"), realRegionEntry("ASIA"),
                realRegionEntry("WMUS"), realRegionEntry("AUNZ"),
                realRegionEntry("WMCH"), realRegionEntry("ZURI")
        );
        CalculatorEntry existing = new CalculatorEntry("capital", "calc-id", sevenRuns);

        List<RunEntry> runs = pad(Map.of("capital", existing)).get("capital").runs();

        List<RunEntry> placeholders = runs.stream()
                .filter(r -> r.status().equals("NOT_STARTED")).toList();
        assertThat(placeholders).allSatisfy(r -> {
            assertThat(r.slaStatus()).isEqualTo("VERY_LATE");
            assertThat(r.slaBreached()).isTrue();
        });
    }

    @Test
    void partialBatch_noSiblingDeadline_usesTemplateSla() {
        Instant templateSla = Instant.now().plusSeconds(7200);
        Instant estEnd = Instant.now().plusSeconds(3600);
        RunEntry template = RunEntry.builder()
                .status("NOT_STARTED").slaStatus("ON_TIME").sla(templateSla)
                .estimatedEndTime(estEnd).isRerun(false).build();
        // Mix: one real run (no sla), one template (no region/runType), 8 missing
        CalculatorEntry existing = new CalculatorEntry("capital", "calc-id",
                List.of(realRegionEntry("WMAP"), template));

        List<RunEntry> runs = pad(Map.of("capital", existing)).get("capital").runs();

        List<RunEntry> placeholders = runs.stream()
                .filter(r -> r.status().equals("NOT_STARTED")).toList();
        assertThat(placeholders).hasSize(9);
        assertThat(placeholders).allSatisfy(r -> assertThat(r.sla()).isEqualTo(templateSla));
    }

    @Test
    void partialBatch_perDimensionEstimatesFromProfile() {
        // WMAP has a dimension profile with known avg start (480 min UTC = 08:00)
        CalculatorProfile wmapProfile = new CalculatorProfile(
                "capital", "DAILY", "2", "WMAP", 3_600_000L, 480, 540, 8);
        when(profileService.getProfile("capital", FREQ, "2", "WMAP")).thenReturn(wmapProfile);

        CalculatorEntry existing = new CalculatorEntry("capital", "calc-id",
                List.of(realRegionEntry("WMDE")));

        List<RunEntry> runs = pad(Map.of("capital", existing)).get("capital").runs();

        RunEntry wmapPlaceholder = runs.stream()
                .filter(r -> "WMAP".equals(r.region())).findFirst().orElseThrow();
        assertThat(wmapPlaceholder.status()).isEqualTo("NOT_STARTED");
        assertThat(wmapPlaceholder.expectedDurationMs()).isEqualTo(3_600_000L);
        assertThat(wmapPlaceholder.estimatedStartTime()).isNotNull();
        assertThat(wmapPlaceholder.estimatedEndTime()).isNotNull();
    }

    @Test
    void partialBatch_noDeadlineNoProfile_usesEstEndFallback() {
        Instant estEnd = Instant.now().minusSeconds(500);  // past
        RunEntry template = RunEntry.builder()
                .status("NOT_STARTED").slaStatus("ON_TIME")
                .estimatedEndTime(estEnd).sla(null).isRerun(false).build();
        // No real runs, just the template (no sibling sla → fall back to estEnd)
        CalculatorEntry existing = new CalculatorEntry("capital", "calc-id", List.of(template));

        List<RunEntry> runs = pad(Map.of("capital", existing)).get("capital").runs();

        // estEnd is in the past → at least LATE
        assertThat(runs).allSatisfy(r ->
                assertThat(List.of("LATE", "VERY_LATE")).contains(r.slaStatus()));
    }

    // ── WP9: declared set is a floor, never a whitelist ────────────────────────

    @Test
    void realRunWithNullRegion_notSwallowedAsTemplate_staysInResponse() {
        // A real RUNNING run that arrived untagged (no region) must not be consumed as the
        // synthetic template and dropped — all regions showing NOT_STARTED while the
        // calculator is actually running would hide the truth.
        RunEntry untaggedRunning = RunEntry.builder()
                .runId("run-live").status("RUNNING").slaStatus("ON_TIME").isRerun(false).build();
        CalculatorEntry existing = new CalculatorEntry("capital", "calc-id", List.of(untaggedRunning));

        List<RunEntry> runs = pad(Map.of("capital", existing)).get("capital").runs();

        // 10 placeholders + the untagged real run appended
        assertThat(runs).hasSize(11);
        assertThat(runs).filteredOn(r -> "RUNNING".equals(r.status())).hasSize(1);
        assertThat(runs.stream().filter(r -> "RUNNING".equals(r.status())).findFirst().orElseThrow()
                .runId()).isEqualTo("run-live");
    }

    @Test
    void undeclaredRegionValue_appendedNotDropped() {
        // New region added upstream before config catch-up — must stay visible.
        CalculatorEntry existing = new CalculatorEntry("capital", "calc-id",
                List.of(realRegionEntry("WMAP"), realRegionEntry("NEWREGION")));

        List<RunEntry> runs = pad(Map.of("capital", existing)).get("capital").runs();

        assertThat(runs).hasSize(11); // 10 declared (1 real + 9 placeholders) + 1 undeclared appended
        assertThat(runs).extracting(RunEntry::region).contains("NEWREGION");
    }

    @Test
    void caseMismatchedRegion_appendedNotDropped() {
        // "wmap" ≠ "WMAP" — config drift must not hide a real (possibly FAILED) run.
        List<RunEntry> nineDeclaredPlusMismatch = new java.util.ArrayList<>(
                ALL_REGIONS.subList(1, 10).stream().map(this::realRegionEntry).toList());
        RunEntry failed = RunEntry.builder()
                .runId("run-failed").region("wmap").status("FAILED").slaStatus("ON_TIME").isRerun(false).build();
        nineDeclaredPlusMismatch.add(failed);
        CalculatorEntry existing = new CalculatorEntry("capital", "calc-id", nineDeclaredPlusMismatch);

        List<RunEntry> runs = pad(Map.of("capital", existing)).get("capital").runs();

        // 9 real declared + 1 WMAP placeholder + the mismatched FAILED run appended
        assertThat(runs).hasSize(11);
        assertThat(runs).filteredOn(r -> "FAILED".equals(r.status())).hasSize(1);
    }

    @Test
    void fastPath_allDeclaredCovered_extraUndeclaredStillAppended() {
        List<RunEntry> all = new java.util.ArrayList<>(
                ALL_REGIONS.stream().map(this::realRegionEntry).toList());
        all.add(realRegionEntry("EXTRA"));
        CalculatorEntry existing = new CalculatorEntry("capital", "calc-id", all);

        List<RunEntry> runs = pad(Map.of("capital", existing)).get("capital").runs();

        assertThat(runs).hasSize(11);
        assertThat(runs.subList(0, 10)).extracting(RunEntry::region).containsExactlyElementsOf(ALL_REGIONS);
        assertThat(runs.get(10).region()).isEqualTo("EXTRA");
    }

    @Test
    void multipleRunNumbersPerDimension_allKept() {
        // Post-WP10 the no-filter view yields one entry per run number per dimension —
        // padding must not collapse them first-wins.
        RunEntry run1 = RunEntry.builder().runId("r1").region("WMAP").status("SUCCESS")
                .slaStatus("ON_TIME").isRerun(false).build();
        RunEntry run2 = RunEntry.builder().runId("r2").region("WMAP").status("RUNNING")
                .slaStatus("ON_TIME").isRerun(false).build();
        CalculatorEntry existing = new CalculatorEntry("capital", "calc-id", List.of(run1, run2));

        List<RunEntry> runs = pad(Map.of("capital", existing)).get("capital").runs();

        assertThat(runs).filteredOn(r -> "WMAP".equals(r.region())).hasSize(2);
        // 2 WMAP entries + 9 placeholders
        assertThat(runs).hasSize(11);
    }

    // ── Unconfigured alias / no-config pass-through ───────────────────────────

    @Test
    void unconfiguredAliasPassesThroughUnchanged() {
        CalculatorEntry existing = new CalculatorEntry("portfolio", "pid",
                List.of(realRegionEntry("some-value")));

        CalculatorEntry result = pad(Map.of("portfolio", existing)).get("portfolio");

        assertThat(result.runs()).hasSize(1);
        assertThat(result.runs().get(0).region()).isEqualTo("some-value");
    }

    @Test
    void noDimensionConfig_returnsInputUnchanged() {
        ExpectedRunsService bare = new ExpectedRunsService(
                new CalculatorProperties(), profileService, slaProps);
        Map<String, CalculatorEntry> input = Map.of(
                "capital", new CalculatorEntry("capital", "id", List.of(realRegionEntry("WMAP"))));

        assertThat(bare.padToExpected(input, DATE, FREQ, RUN_NUMBER)).isSameAs(input);
    }

    // ── evaluateSlaStatus branch coverage ──────────────────────────────────────

    @Test
    void evaluateSlaStatus_futureDeadline_returnsOnTime() {
        var eval = ExpectedRunsService.evaluateSlaStatus(Instant.now().plusSeconds(3600), slaProps.bandGapMs());
        assertThat(eval.slaStatus()).isEqualTo("ON_TIME");
        assertThat(eval.slaBreached()).isFalse();
    }

    @Test
    void evaluateSlaStatus_pastDeadlineWithinBandGap_returnsLate() {
        var eval = ExpectedRunsService.evaluateSlaStatus(Instant.now().minusSeconds(300), slaProps.bandGapMs());
        assertThat(eval.slaStatus()).isEqualTo("LATE");
        assertThat(eval.slaBreached()).isTrue();
    }

    @Test
    void evaluateSlaStatus_pastDeadlineBeyondBandGap_returnsVeryLate() {
        var eval = ExpectedRunsService.evaluateSlaStatus(Instant.now().minusSeconds(2000), slaProps.bandGapMs());
        assertThat(eval.slaStatus()).isEqualTo("VERY_LATE");
        assertThat(eval.slaBreached()).isTrue();
    }

    @Test
    void evaluateSlaStatus_nullDeadline_returnsOnTime() {
        var eval = ExpectedRunsService.evaluateSlaStatus(null, slaProps.bandGapMs());
        assertThat(eval.slaStatus()).isEqualTo("ON_TIME");
        assertThat(eval.slaBreached()).isFalse();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private RunEntry realRegionEntry(String region) {
        return RunEntry.builder().region(region).status("SUCCESS").slaStatus("ON_TIME").isRerun(false).build();
    }

    private RunEntry realRegionEntryWithSla(String region, Instant sla) {
        return RunEntry.builder().region(region).status("SUCCESS").slaStatus("ON_TIME")
                .sla(sla).isRerun(false).build();
    }

    private RunEntry realRunTypeEntry(String runType) {
        return RunEntry.builder().runType(runType).status("SUCCESS").slaStatus("ON_TIME").isRerun(false).build();
    }
}
