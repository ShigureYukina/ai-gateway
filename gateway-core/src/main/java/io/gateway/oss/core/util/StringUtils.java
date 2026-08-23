package io.gateway.oss.core.util;

/**
 * Common string utilities.
 */
public final class StringUtils {

    private StringUtils() {
        // utility class
    }

    /**
     * Trims whitespace and returns {@code null} for blank input, so
     * downstream code can treat missing and blank uniformly as {@code null}.
     */
    public static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
