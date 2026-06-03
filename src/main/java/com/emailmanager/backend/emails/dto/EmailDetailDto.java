package com.emailmanager.backend.emails.dto;

import java.time.LocalDateTime;
import java.util.List;

public record EmailDetailDto(
        long uid,
        String subject,
        String fromAddress,
        String fromName,
        List<String> toAddresses,
        List<String> ccAddresses,
        String bodyHtml,
        String bodyText,
        LocalDateTime receivedAt,
        boolean read,
        List<String> attachmentNames,
        String folder
) {}
