package com.emailmanager.backend.emails.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record SendEmailRequest(
        @NotBlank @Email String to,
        List<String> cc,
        List<String> bcc,
        @NotBlank String subject,
        String bodyHtml,
        String bodyText
) {}
