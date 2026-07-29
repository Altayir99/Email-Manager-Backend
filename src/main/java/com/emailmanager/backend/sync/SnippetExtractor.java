package com.emailmanager.backend.sync;

import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMultipart;

/**
 * Extracts a short, readable preview snippet from an email body.
 *
 * <p>Shared by the sync path ({@link SyncService}, live MIME message) and the
 * detail lazy-load path ({@code EmailController}, already-fetched body columns),
 * so both produce identical, clean previews with a single source of truth.
 *
 * <p>Crucially strips the *contents* of &lt;style&gt;/&lt;script&gt;/&lt;head&gt;
 * blocks and comments before removing tags — otherwise CSS/JS rules
 * (e.g. {@code a{outline:none;color:#fff}}) leak into the snippet.
 *
 * <p>Stateless utility — all methods are static and pure (no IMAP/DB access),
 * which keeps it trivially unit-testable (see {@code SnippetExtractionTest}).
 */
public final class SnippetExtractor {

    private static final int SNIPPET_MAX = 200;

    private SnippetExtractor() {
        // utility class — no instances
    }

    /**
     * Builds a snippet from a live MIME message (sync path).
     * Never throws — returns "" on any parsing error.
     */
    public static String fromMessage(Message msg) {
        try {
            return truncate(extractSnippetText(msg.getContent(), msg.getContentType()));
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * Builds a snippet from an already-loaded body — used by the self-healing
     * detail path so old cache rows get a clean preview when opened, without
     * any extra IMAP roundtrip (cache-first preserved).
     * Prefers {@code bodyText}; falls back to tag-stripped {@code bodyHtml}.
     */
    public static String fromBody(String bodyText, String bodyHtml) {
        if (bodyText != null && !bodyText.isBlank()) return truncate(bodyText);
        if (bodyHtml != null && !bodyHtml.isBlank()) return truncate(stripTags(bodyHtml));
        return "";
    }

    /**
     * Recursively pulls readable preview text from a MIME body.
     * Prefers text/plain; falls back to tag-stripped text/html; descends into
     * nested multiparts (multipart/alternative inside multipart/related, etc.).
     * <p>Package-private for unit testing.
     */
    static String extractSnippetText(Object content, String contentType) throws Exception {
        if (content instanceof String s) {
            String ct = contentType == null ? "" : contentType.toLowerCase();
            return ct.contains("html") ? stripTags(s) : s.trim();
        }
        if (content instanceof MimeMultipart mp) {
            String htmlFallback = null;
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart part = mp.getBodyPart(i);
                String ct = part.getContentType() != null
                        ? part.getContentType().toLowerCase() : "";
                if (ct.contains("text/plain")) {
                    String t = part.getContent().toString().trim();
                    if (!t.isBlank()) return t;
                } else if (ct.contains("text/html")) {
                    if (htmlFallback == null) {
                        htmlFallback = stripTags(part.getContent().toString());
                    }
                } else if (ct.contains("multipart")) {
                    String nested = extractSnippetText(part.getContent(), ct);
                    if (nested != null && !nested.isBlank()) return nested;
                }
            }
            if (htmlFallback != null) return htmlFallback;
        }
        return "";
    }

    /** Collapses whitespace and clamps to {@value #SNIPPET_MAX} chars with an ellipsis. */
    static String truncate(String text) {
        if (text == null) return "";
        text = text.replaceAll("\\s+", " ").trim();
        return text.length() > SNIPPET_MAX ? text.substring(0, SNIPPET_MAX) + "…" : text;
    }

    /**
     * Strips HTML down to readable text. Removes the *contents* of
     * &lt;style&gt;/&lt;script&gt;/&lt;head&gt; blocks and comments first, then
     * remaining tags, then decodes common entities.
     * (?is) = case-insensitive + dotall so blocks spanning newlines are matched.
     */
    static String stripTags(String html) {
        if (html == null) return "";
        return html
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<head[^>]*>.*?</head>", " ")
                .replaceAll("(?is)<!--.*?-->", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&#39;", "'")
                .replaceAll("&quot;", "\"")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
