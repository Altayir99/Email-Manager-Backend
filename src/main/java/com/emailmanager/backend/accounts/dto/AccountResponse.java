package com.emailmanager.backend.accounts.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String emailAddress,
        String displayName,
        String color,
        String imapHost,
        Integer imapPort,
        String smtpHost,
        Integer smtpPort,
        boolean active,
        LocalDateTime lastSyncedAt,
        LocalDateTime createdAt
) {}
