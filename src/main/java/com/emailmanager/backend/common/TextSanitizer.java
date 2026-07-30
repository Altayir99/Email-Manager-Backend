package com.emailmanager.backend.common;

/**
 * Strips characters PostgreSQL rejects in {@code text}/{@code varchar} columns.
 *
 * <p>Specifically the NUL byte (0x00): some emails carry a NUL in the body,
 * subject or a header, and any persist/update of such a String fails with
 * {@code SQLState 22021 "invalid byte sequence for encoding UTF8: 0x00"} → HTTP 500.
 * Everything else (normal text, umlauts, emojis, newlines/tabs) is left
 * untouched — only NUL is hard-forbidden by Postgres.
 */
public final class TextSanitizer {

    /** The NUL character (0x00) — the only byte Postgres forbids in text. */
    private static final char NUL = (char) 0;

    private TextSanitizer() {
        // utility class — no instances
    }

    /** Removes NUL bytes from the string. Null-safe; returns the input when clean. */
    public static String clean(String s) {
        if (s == null || s.indexOf(NUL) < 0) return s; // fast path — no allocation
        return s.replace(String.valueOf(NUL), "");
    }
}
