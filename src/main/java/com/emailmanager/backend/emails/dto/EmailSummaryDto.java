package com.emailmanager.backend.emails.dto;

import java.time.LocalDateTime;

public record EmailSummaryDto(
        long uid,
        String subject,
        String fromAddress,
        String fromName,
        String snippet,
        LocalDateTime receivedAt,
        boolean read,
        boolean hasAttachment,
        String folder
) {}
