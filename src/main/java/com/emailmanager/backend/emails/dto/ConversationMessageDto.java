package com.emailmanager.backend.emails.dto;

import java.time.LocalDateTime;

/**
 * One message inside a cross-folder conversation (INBOX + Sent + All-Mail),
 * served cache-first. Body is NOT included — it is lazy-loaded per message via
 * the existing {@code GET /emails/{uid}} detail endpoint when expanded.
 */
public record ConversationMessageDto(
        long uid,
        String folder,
        /** RFC Message-ID — used by the client to set In-Reply-To when replying. */
        String messageId,
        String subject,
        String fromAddress,
        String fromName,
        LocalDateTime receivedAt,
        boolean read,
        boolean hasAttachment,
        /** true when this message was sent by the account owner (Sent folder or matching from-address). */
        boolean outgoing
) {}
