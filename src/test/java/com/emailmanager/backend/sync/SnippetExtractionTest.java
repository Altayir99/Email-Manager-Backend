package com.emailmanager.backend.sync;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the {@link SnippetExtractor} pipeline
 * ({@code extractSnippetText} / {@code stripTags} / {@code truncate} / {@code fromBody}).
 *
 * <p>Background: earlier the preview snippet leaked raw CSS/JS
 * (e.g. {@code a{outline:none;color:#fff}}) because {@code stripTags} only
 * removed tags, not the *contents* of &lt;style&gt;/&lt;script&gt;/&lt;head&gt;.
 * These tests lock in the corrected behaviour.
 *
 * <p>The logic was extracted from {@code SyncService} into the dedicated,
 * stateless {@link SnippetExtractor} so both the sync path and the detail
 * lazy-load path (self-healing snippet backfill) share one implementation.
 */
class SnippetExtractionTest {

    /**
     * Wraps a multipart in a real MimeMessage and calls {@code saveChanges()} so
     * every part gets its proper Content-Type header — exactly the state parts are
     * in when JavaMail parses a message off the wire from IMAP. Freshly built
     * {@code MimeBodyPart}s otherwise report the default {@code text/plain} until
     * headers are saved, which would not reflect production behaviour.
     */
    private static MimeMultipart realized(MimeMultipart mp) throws Exception {
        MimeMessage msg = new MimeMessage(Session.getInstance(new Properties()));
        msg.setContent(mp);
        msg.saveChanges();
        return (MimeMultipart) msg.getContent();
    }

    // ── stripTags: no CSS / JS / entities leak ───────────────────────────────

    @Nested
    @DisplayName("stripTags")
    class StripTags {

        @Test
        @DisplayName("(1) removes <style> block contents — no CSS leaks into snippet")
        void removesStyleBlock() {
            String html = "<div><style>a{outline:none;color:#fff}</style>Sichtbarer Text</div>";

            String result = SnippetExtractor.stripTags(html);

            assertThat(result).isEqualTo("Sichtbarer Text");
            assertThat(result).doesNotContain("outline");
            assertThat(result).doesNotContain("#fff");
            assertThat(result).doesNotContain("{");
        }

        @Test
        @DisplayName("(1b) removes <style> nested inside <head>")
        void removesStyleInsideHead() {
            String html = "<html><head><style>body{margin:0}</style></head>"
                    + "<body>Hallo Welt</body></html>";

            String result = SnippetExtractor.stripTags(html);

            assertThat(result).isEqualTo("Hallo Welt");
            assertThat(result).doesNotContain("margin");
        }

        @Test
        @DisplayName("(2) removes <script> block contents — no JS leaks")
        void removesScriptBlock() {
            String html = "<div><script>var x = 1; alert('hi');</script>Klartext</div>";

            String result = SnippetExtractor.stripTags(html);

            assertThat(result).isEqualTo("Klartext");
            assertThat(result).doesNotContain("alert");
            assertThat(result).doesNotContain("var x");
        }

        @Test
        @DisplayName("(2b) removes HTML comments")
        void removesComments() {
            String html = "<p>Vorne<!-- geheimer Kommentar -->Hinten</p>";

            String result = SnippetExtractor.stripTags(html);

            assertThat(result).doesNotContain("geheimer");
            assertThat(result).contains("Vorne");
            assertThat(result).contains("Hinten");
        }

        @Test
        @DisplayName("(5) decodes HTML entities (&nbsp; &amp; &lt; &#39; &quot;)")
        void decodesEntities() {
            String html = "Tom &amp; Jerry &lt;3 &nbsp;&#39;quote&#39; &quot;q&quot;";

            String result = SnippetExtractor.stripTags(html);

            assertThat(result).isEqualTo("Tom & Jerry <3 'quote' \"q\"");
        }

        @Test
        @DisplayName("(7) null / empty HTML → empty string")
        void nullAndEmpty() {
            assertThat(SnippetExtractor.stripTags(null)).isEqualTo("");
            assertThat(SnippetExtractor.stripTags("")).isEqualTo("");
        }
    }

    // ── extractSnippetText: MIME traversal & preference ──────────────────────

    @Nested
    @DisplayName("extractSnippetText")
    class ExtractSnippetText {

        @Test
        @DisplayName("plain String with text/plain is returned as-is (trimmed)")
        void plainString() throws Exception {
            String result = SnippetExtractor.extractSnippetText(
                    "  Just plain text  ", "text/plain; charset=utf-8");
            assertThat(result).isEqualTo("Just plain text");
        }

        @Test
        @DisplayName("plain String with text/html is tag-stripped")
        void htmlString() throws Exception {
            String result = SnippetExtractor.extractSnippetText(
                    "<p>Hi <b>there</b></p>", "text/html; charset=utf-8");
            assertThat(result).isEqualTo("Hi there");
        }

        @Test
        @DisplayName("(3) multipart/alternative: text/plain wins over text/html")
        void multipartAlternative_plainWins() throws Exception {
            MimeBodyPart plain = new MimeBodyPart();
            plain.setText("Plain wins here", "utf-8"); // → text/plain
            MimeBodyPart html = new MimeBodyPart();
            html.setContent("<p>HTML should lose</p>", "text/html; charset=utf-8");

            MimeMultipart mp = new MimeMultipart("alternative");
            mp.addBodyPart(plain);
            mp.addBodyPart(html);

            String result = SnippetExtractor.extractSnippetText(realized(mp), "multipart/alternative");

            assertThat(result).isEqualTo("Plain wins here");
        }

        @Test
        @DisplayName("(3b) multipart/alternative: plain still wins when html is first")
        void multipartAlternative_plainWinsRegardlessOfOrder() throws Exception {
            MimeBodyPart html = new MimeBodyPart();
            html.setContent("<p>HTML should lose</p>", "text/html; charset=utf-8");
            MimeBodyPart plain = new MimeBodyPart();
            plain.setText("Plain still wins", "utf-8");

            MimeMultipart mp = new MimeMultipart("alternative");
            mp.addBodyPart(html); // html first
            mp.addBodyPart(plain);

            String result = SnippetExtractor.extractSnippetText(realized(mp), "multipart/alternative");

            assertThat(result).isEqualTo("Plain still wins");
        }

        @Test
        @DisplayName("(4) nested multipart/related with only text/html → stripped HTML")
        void nestedMultipartRelated_htmlOnly() throws Exception {
            MimeBodyPart htmlPart = new MimeBodyPart();
            htmlPart.setContent("<div>Nested <b>HTML</b> only</div>",
                    "text/html; charset=utf-8");

            MimeMultipart alternative = new MimeMultipart("alternative");
            alternative.addBodyPart(htmlPart);

            MimeBodyPart wrapper = new MimeBodyPart();
            wrapper.setContent(alternative); // → multipart/alternative part

            MimeMultipart related = new MimeMultipart("related");
            related.addBodyPart(wrapper);

            String result = SnippetExtractor.extractSnippetText(realized(related), "multipart/related");

            assertThat(result).isEqualTo("Nested HTML only");
        }

        @Test
        @DisplayName("(7) null content → empty string")
        void nullContent() throws Exception {
            assertThat(SnippetExtractor.extractSnippetText(null, "text/plain")).isEqualTo("");
        }

        @Test
        @DisplayName("(7b) empty String content → empty string")
        void emptyContent() throws Exception {
            assertThat(SnippetExtractor.extractSnippetText("", "text/plain")).isEqualTo("");
        }
    }

    // ── truncate: whitespace collapse + 200-char ellipsis ────────────────────

    @Nested
    @DisplayName("truncate")
    class Truncate {

        @Test
        @DisplayName("(6) text longer than 200 chars is cut to 200 + ellipsis")
        void truncatesLongText() {
            String longText = "A".repeat(250);

            String result = SnippetExtractor.truncate(longText);

            assertThat(result).hasSize(201);              // 200 chars + '…'
            assertThat(result).endsWith("…");
            assertThat(result.substring(0, 200)).isEqualTo("A".repeat(200));
        }

        @Test
        @DisplayName("text at/under 200 chars is left untouched (no ellipsis)")
        void keepsShortText() {
            String text = "B".repeat(200);
            assertThat(SnippetExtractor.truncate(text)).isEqualTo(text);
            assertThat(SnippetExtractor.truncate("short")).isEqualTo("short");
        }

        @Test
        @DisplayName("collapses runs of whitespace into single spaces")
        void collapsesWhitespace() {
            assertThat(SnippetExtractor.truncate("a\n\n   b\t c")).isEqualTo("a b c");
        }

        @Test
        @DisplayName("(7) null text → empty string")
        void nullText() {
            assertThat(SnippetExtractor.truncate(null)).isEqualTo("");
        }
    }

    // ── fromBody: self-healing snippet backfill (detail lazy-load path) ───────

    @Nested
    @DisplayName("fromBody")
    class FromBody {

        @Test
        @DisplayName("prefers bodyText when present")
        void prefersBodyText() {
            String result = SnippetExtractor.fromBody(
                    "  Klartext gewinnt  ", "<p>HTML fallback</p>");
            assertThat(result).isEqualTo("Klartext gewinnt");
        }

        @Test
        @DisplayName("falls back to tag-stripped bodyHtml when text is null/blank")
        void fallsBackToHtml() {
            assertThat(SnippetExtractor.fromBody(null,
                    "<div><style>a{color:#fff}</style>Nur HTML</div>"))
                    .isEqualTo("Nur HTML");
            assertThat(SnippetExtractor.fromBody("   ",
                    "<p>Auch HTML</p>")).isEqualTo("Auch HTML");
        }

        @Test
        @DisplayName("truncates a long body to 200 chars + ellipsis")
        void truncatesLongBody() {
            String result = SnippetExtractor.fromBody("C".repeat(300), null);
            assertThat(result).hasSize(201);
            assertThat(result).endsWith("…");
        }

        @Test
        @DisplayName("both null/blank → empty string (never blanks a good snippet upstream)")
        void bothEmpty() {
            assertThat(SnippetExtractor.fromBody(null, null)).isEqualTo("");
            assertThat(SnippetExtractor.fromBody("", "   ")).isEqualTo("");
        }
    }
}
