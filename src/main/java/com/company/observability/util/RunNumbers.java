package com.company.observability.util;

/**
 * Shared run_number normalization: blank → null, trim, numeric canonicalization ("01" → "1").
 * Non-numeric values are returned as-is (callers that need a warning/metric handle it themselves).
 */
public final class RunNumbers {

    private RunNumbers() {}

    public static String normalize(String runNumber) {
        if (runNumber == null || runNumber.isBlank()) {
            return null;
        }
        String trimmed = runNumber.trim();
        try {
            return Integer.toString(Integer.parseInt(trimmed));
        } catch (NumberFormatException e) {
            return trimmed;
        }
    }
}
