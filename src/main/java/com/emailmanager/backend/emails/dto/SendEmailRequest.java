package com.emailmanager.backend.emails.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * Used for multipart/form-data sends (supports optional PDF attachment).
 * Text fields arrive as @RequestParam, the file as @RequestPart.
 * This record is a thin holder — the controller reads params individually.
 */
public record SendEmailRequest(
        @NotBlank @Email String to,
        List<String> cc,
        List<String> bcc,
        @NotBlank String subject,
        String bodyHtml,
        String bodyText
) {}
