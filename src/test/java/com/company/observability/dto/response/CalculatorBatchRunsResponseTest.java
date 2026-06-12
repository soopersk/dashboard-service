package com.company.observability.dto.response;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CalculatorBatchRunsResponseTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void emptyRunsSerializesWithEmptyArray() throws Exception {
        var entry = new CalculatorBatchRunsResponse.CalculatorEntry("capitalcalc", null, List.of());
        var response = new CalculatorBatchRunsResponse(
                LocalDate.of(2026, 3, 6), "DAILY", "1", Instant.now(), Map.of("capitalcalc", entry));
        String json = mapper.writeValueAsString(response);
        assertThat(json).contains("\"runs\":[]");
        assertThat(json).contains("\"calculatorName\":\"capitalcalc\"");
        assertThat(json).doesNotContain("calculatorId");
    }

    @Test
    void nullFieldsOmittedFromRunEntry() throws Exception {
        var run = CalculatorBatchRunsResponse.RunEntry.builder()
                .region("WMAP")
                .status("SUCCESS")
                .slaStatus("ON_TIME")
                .sla(Instant.parse("2026-03-06T15:00:00Z"))
                .isRerun(false)
                .build();
        String json = mapper.writeValueAsString(run);
        assertThat(json).doesNotContain("runId");
        assertThat(json).doesNotContain("runType");
        assertThat(json).doesNotContain("startTime");
        assertThat(json).contains("\"region\":\"WMAP\"");
        assertThat(json).contains("\"slaStatus\":\"ON_TIME\"");
        assertThat(json).contains("\"isRerun\":false");
    }

    @Test
    void rerunFlagAndRegionSerializedCorrectly() throws Exception {
        var run = CalculatorBatchRunsResponse.RunEntry.builder()
                .region("LDNL")
                .runId("run-ldnl-002")
                .status("FAILED")
                .slaStatus("LATE")
                .startTime(Instant.parse("2026-03-06T13:02:00Z"))
                .endTime(Instant.parse("2026-03-06T14:58:00Z"))
                .sla(Instant.parse("2026-03-06T15:00:00Z"))
                .durationMs(6960000L)
                .slaBreachReason("Run status: FAILED")
                .isRerun(true)
                .build();
        String json = mapper.writeValueAsString(run);
        assertThat(json).contains("\"isRerun\":true");
        assertThat(json).contains("\"region\":\"LDNL\"");
        assertThat(json).contains("\"status\":\"FAILED\"");
    }
}
