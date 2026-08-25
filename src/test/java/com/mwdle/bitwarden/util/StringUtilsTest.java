package com.mwdle.bitwarden.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("StringUtils.stripToNull")
class StringUtilsTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "   ", "\t", "\n", " \t \n ", "\r", "\r\n", " \r \n "})
    @DisplayName("returns null for null, empty, or whitespace-only input")
    void returnsNullForBlankInput(String input) {
        assertNull(StringUtils.stripToNull(input));
    }

    @ParameterizedTest
    @CsvSource({
        "'value', 'value'",
        "'  value  ', 'value'",
        "'\tvalue\n', 'value'",
        "'\r\nvalue\n ', 'value'",
        "'\u3000value\u3000', 'value'",
        "'\u2003value\u2003', 'value'",
        "'a b', 'a b'"
    })
    @DisplayName("strips surrounding whitespace and preserves inner content")
    void stripsSurroundingWhitespace(String input, String expected) {
        assertEquals(expected, StringUtils.stripToNull(input));
    }
}
