package com.mwdle.bitwarden.util;

import edu.umd.cs.findbugs.annotations.CheckForNull;

/**
 * Utility for string manipulation and sanitization.
 */
public final class StringUtils {

    private StringUtils() {
        throw new UnsupportedOperationException();
    }

    /**
     * Converts empty or whitespace-only strings to null and strips whitespace.
     *
     * @param value the string to normalize
     * @return the stripped string, or null if it is blank or null
     */
    @CheckForNull
    public static String stripToNull(@CheckForNull String value) {
        return (value == null || value.isBlank()) ? null : value.strip();
    }
}
