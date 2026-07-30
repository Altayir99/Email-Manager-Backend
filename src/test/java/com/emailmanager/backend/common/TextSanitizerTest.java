package com.emailmanager.backend.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextSanitizerTest {

    /** The forbidden NUL byte, built without a source-file escape. */
    private static final String NUL = String.valueOf((char) 0);

    @Test
    @DisplayName("removes a NUL byte")
    void removesNul() {
        assertThat(TextSanitizer.clean("a" + NUL + "b")).isEqualTo("ab");
    }

    @Test
    @DisplayName("removes multiple / leading / trailing NUL bytes")
    void removesMultipleNul() {
        assertThat(TextSanitizer.clean(NUL + "x" + NUL + NUL + "y" + NUL)).isEqualTo("xy");
    }

    @Test
    @DisplayName("leaves normal text, umlauts, emojis and whitespace untouched")
    void keepsNormalText() {
        assertThat(TextSanitizer.clean("Grüße 😀\tTab\nNewline"))
                .isEqualTo("Grüße 😀\tTab\nNewline");
        assertThat(TextSanitizer.clean("plain")).isEqualTo("plain");
        assertThat(TextSanitizer.clean("")).isEqualTo("");
    }

    @Test
    @DisplayName("is null-safe")
    void nullSafe() {
        assertThat(TextSanitizer.clean(null)).isNull();
    }

    @Test
    @DisplayName("returns the same instance when there is no NUL (fast path)")
    void fastPathReturnsSameInstance() {
        String clean = "no nul here";
        assertThat(TextSanitizer.clean(clean)).isSameAs(clean);
    }
}
