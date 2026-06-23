package com.emailmanager.backend.emails.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response returned when an email is queued for delayed sending.
 * The client shows an "Undo" button until {@code expiresAt}.
 */
public record PendingSendResponse(
        UUID sendId,
        Instant expiresAt,
        String status
) {
    public static PendingSendResponse queued(UUID sendId, Instant expiresAt) {
        return new PendingSendResponse(sendId, expiresAt, "queued");
    }

    public static PendingSendResponse cancelled(UUID sendId) {
        return new PendingSendResponse(sendId, null, "cancelled");
    }

    public static PendingSendResponse sent(UUID sendId) {
        return new PendingSendResponse(sendId, null, "sent");
    }
}
