package com.company.observability.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonbConverterTest {

    private final JsonbConverter converter = new JsonbConverter(new ObjectMapper());

    @Test
    void roundTrip_preservesMap() throws SQLException {
        Map<String, Object> input = Map.of("region", "WMAP", "runNumber", 2);

        PGobject pg = converter.toJsonb(input);
        assertThat(pg.getType()).isEqualTo("jsonb");

        Map<String, Object> output = converter.fromJsonb(pg);
        assertThat(output).containsEntry("region", "WMAP").containsEntry("runNumber", 2);
    }

    @Test
    void toJsonb_null_producesTypedNull() throws SQLException {
        PGobject pg = converter.toJsonb(null);
        assertThat(pg.getType()).isEqualTo("jsonb");
        assertThat(pg.getValue()).isNull();
    }

    @Test
    void fromJsonb_null_returnsNull() {
        assertThat(converter.fromJsonb(null)).isNull();
    }

    @Test
    void toJsonb_unserializableValue_throwsIllegalArgumentException() {
        // Default ObjectMapper fails on an empty bean (no serializable properties).
        Map<String, Object> input = Map.of("bad", new Object());

        assertThatThrownBy(() -> converter.toJsonb(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to serialize JSONB");
    }

    @Test
    void fromJsonb_malformedJson_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> converter.fromJsonb("{not valid json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to deserialize JSONB");
    }

    @Test
    void fromJsonb_unsupportedSourceType_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> converter.fromJsonb(42))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported JSONB source type");
    }
}
