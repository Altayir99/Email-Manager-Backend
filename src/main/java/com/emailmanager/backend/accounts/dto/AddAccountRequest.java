package com.emailmanager.backend.accounts.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record AddAccountRequest(
        @NotBlank @Email String emailAddress,
        @NotBlank String displayName,
        @NotBlank String appPassword,
        String color,       // optional, hex e.g. "#6366F1"
        String imapHost,    // optional, defaults to imap.gmail.com
        Integer imapPort,   // optional, defaults to 993
        String smtpHost,    // optional, defaults to smtp.gmail.com
        Integer smtpPort    // optional, defaults to 587
) {}
