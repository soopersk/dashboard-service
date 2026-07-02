package com.company.observability.dto.response;

import java.time.LocalDate;

public record RecomputeResponse(
        LocalDate from,
        LocalDate to,
        int rowsRecomputed,
        long profilesWarmed
) {}
