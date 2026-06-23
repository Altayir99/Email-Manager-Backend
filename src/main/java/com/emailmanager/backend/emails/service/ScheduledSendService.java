package com.emailmanager.backend.emails.service;

import com.emailmanager.backend.accounts.entity.EmailAccount;
import com.emailmanager.backend.emails.dto.PendingSendResponse;
import com.emailmanager.backend.emails.dto.SendEmailRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Undo-send: queues emails for delayed delivery (default 10 seconds).
 *
 * <p>When a user sends an email, it is not dispatched immediately.
 * Instead, a {@link ScheduledFuture} is created. The client can
 * cancel the pending send within the delay window by calling
 * {@link #cancelSend(UUID)}.
 *
 * <p>After the delay expires, the email is delivered via
 * {@link EmailSendService#sendEmail} and the entry is cleaned up.
 */
@Service
@Slf4j
public class ScheduledSendService {

    private static final int SEND_DELAY_SECONDS = 10;

    private final EmailSendService sendService;
    private final ScheduledExecutorService scheduler;

    /**
     * In-flight pending sends — keyed by sendId.
     * Stores both the future (for cancellation) and the request data
     * (in case the client needs to re-inspect).
     */
    private final ConcurrentHashMap<UUID, PendingEntry> pendingMap = new ConcurrentHashMap<>();

    public ScheduledSendService(EmailSendService sendService) {
        this.sendService = sendService;
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "undo-send");
            t.setDaemon(true);
            return t;
        });
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Queues an email for delayed send.
     *
     * @return PendingSendResponse with the sendId and expiry timestamp
     */
    public PendingSendResponse queueSend(EmailAccount account, SendEmailRequest request, MultipartFile attachment) {
        UUID sendId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(SEND_DELAY_SECONDS);

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            try {
                sendService.sendEmail(account, request, attachment);
                log.info("[UndoSend] Delivered send {} for {}", sendId, account.getEmailAddress());
            } catch (Exception e) {
                log.error("[UndoSend] Failed to deliver send {} for {}: {}",
                        sendId, account.getEmailAddress(), e.getMessage());
            } finally {
                pendingMap.remove(sendId);
            }
        }, SEND_DELAY_SECONDS, TimeUnit.SECONDS);

        pendingMap.put(sendId, new PendingEntry(future, account, request));
        log.info("[UndoSend] Queued send {} for {} — expires at {}",
                sendId, account.getEmailAddress(), expiresAt);

        return PendingSendResponse.queued(sendId, expiresAt);
    }

    /**
     * Cancels a pending send if it has not been dispatched yet.
     *
     * @return PendingSendResponse indicating whether cancellation succeeded
     */
    public PendingSendResponse cancelSend(UUID sendId) {
        PendingEntry entry = pendingMap.remove(sendId);
        if (entry == null) {
            // Already sent or never existed
            return PendingSendResponse.sent(sendId);
        }

        boolean cancelled = entry.future().cancel(false);
        if (cancelled) {
            log.info("[UndoSend] Cancelled send {}", sendId);
            return PendingSendResponse.cancelled(sendId);
        }

        // Could not cancel — already in progress
        return PendingSendResponse.sent(sendId);
    }

    /**
     * Checks whether a sendId is still pending (can be undone).
     */
    public boolean isPending(UUID sendId) {
        return pendingMap.containsKey(sendId);
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private record PendingEntry(
            ScheduledFuture<?> future,
            EmailAccount account,
            SendEmailRequest request
    ) {}
}
