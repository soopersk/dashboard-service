package com.company.observability.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.postgresql.util.PGobject;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class JsonbConverter {

    private final ObjectMapper objectMapper;

    public PGobject toJsonb(Map<String, Object> map) {
        try {
            PGobject pg = new PGobject();
            pg.setType("jsonb");
            pg.setValue(map != null ? objectMapper.writeValueAsString(map) : null);
            return pg;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize JSONB: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> fromJsonb(Object dbValue) {
        if (dbValue == null) {
            return null;
        }

        String json;
        if (dbValue instanceof PGobject pg) {
            json = pg.getValue();
        } else if (dbValue instanceof String s) {
            json = s;
        } else {
            throw new IllegalArgumentException("Unsupported JSONB source type: " + dbValue.getClass().getName());
        }

        if (json == null || json.isBlank()) {
            return null;
        }

        try {
            return objectMapper.readValue(
                    json,
                    objectMapper.getTypeFactory()
                            .constructMapType(Map.class, String.class, Object.class)
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize JSONB: " + e.getMessage(), e);
        }
    }
}
