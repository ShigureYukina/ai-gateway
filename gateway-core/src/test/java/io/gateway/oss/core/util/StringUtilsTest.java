package io.gateway.oss.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StringUtilsTest {

    @Test
    void blankToNullReturnsNullForNull() {
        assertNull(StringUtils.blankToNull(null));
    }

    @Test
    void blankToNullReturnsNullForEmptyString() {
        assertNull(StringUtils.blankToNull(""));
    }

    @Test
    void blankToNullReturnsNullForBlankString() {
        assertNull(StringUtils.blankToNull("  "));
    }

    @Test
    void blankToNullReturnsOriginalTextWhenNotBlank() {
        assertEquals("hello", StringUtils.blankToNull("hello"));
    }

    @Test
    void blankToNullTrimsWhitespaceAroundValue() {
        assertEquals("hello", StringUtils.blankToNull("  hello  "));
    }
}
