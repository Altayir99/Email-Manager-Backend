package com.emailmanager.backend.emails.dto;

import java.util.List;

/**
 * Phase 2.4: wraps the email list with pagination metadata so the client
 * no longer has to guess hasMore from page size, and doesn't break on
 * inbox sizes that are exact multiples of pageSize.
 */
public record EmailPageDto(
        List<EmailSummaryDto> emails,
        int page,
        int pageSize,
        int totalMessages,
        boolean hasMore
) {}
