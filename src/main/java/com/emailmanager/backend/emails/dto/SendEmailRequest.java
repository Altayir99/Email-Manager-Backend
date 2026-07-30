package com.emailmanager.backend.emails.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * Used for multipart/form-data sends (supports optional PDF attachment).
 * Text fields arrive as @RequestParam, the file as @RequestPart.
 * This record is a thin holder — the controller reads params individually.
 *
 * <p>{@code inReplyTo} carries the Message-ID being replied to (nullable) so the
 * sent mail links into the conversation thread (In-Reply-To/References headers
 * + cache write-through). A 6-arg convenience constructor keeps non-reply
 * callers unchanged.
 */
public record SendEmailRequest(
        @NotBlank @Email String to,
        List<String> cc,
        List<String> bcc,
        @NotBlank String subject,
        String bodyHtml,
        String bodyText,
        String inReplyTo
) {
    public SendEmailRequest(String to, List<String> cc, List<String> bcc,
                            String subject, String bodyHtml, String bodyText) {
        this(to, cc, bcc, subject, bodyHtml, bodyText, null);
    }
}
