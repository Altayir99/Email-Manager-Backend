package com.emailmanager.backend.emails.service;

import com.emailmanager.backend.accounts.entity.EmailAccount;
import com.emailmanager.backend.emails.dto.PendingSendResponse;
import com.emailmanager.backend.emails.dto.SendEmailRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import org.springframework.core.io.Resource;
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

        // CRITICAL: MultipartFile is HTTP-request-scoped. We must eagerly copy the
        // bytes NOW, before the HTTP request ends and the stream is closed.
        // By the time the 10-second scheduler fires, the original MultipartFile
        // is already garbage — the bytes would be empty/null.
        MultipartFile safeAttachment = null;
        if (attachment != null && !attachment.isEmpty()) {
            try {
                byte[] bytes = attachment.getBytes();
                String originalName = attachment.getOriginalFilename();
                String contentType = attachment.getContentType();
                safeAttachment = new BytesBackedMultipartFile(
                        "attachment",
                        originalName != null ? originalName : "attachment.pdf",
                        contentType != null ? contentType : "application/pdf",
                        bytes
                );
                log.debug("[UndoSend] Eagerly copied attachment '{}' ({} bytes) for send {}",
                        originalName, bytes.length, sendId);
            } catch (IOException e) {
                log.error("[UndoSend] Failed to read attachment bytes for send {}: {}", sendId, e.getMessage());
                // Continue without attachment rather than failing the whole send
            }
        }

        final MultipartFile finalAttachment = safeAttachment;
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            try {
                sendService.sendEmail(account, request, finalAttachment);
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

    /**
     * A production-safe, in-memory MultipartFile backed by a byte[].
     * Implemented as a plain class (not a record) because Java records generate
     * accessors without the 'get' prefix, which breaks the MultipartFile interface contract.
     */
    private static final class BytesBackedMultipartFile implements MultipartFile {
        private final String _name;
        private final String _originalFilename;
        private final String _contentType;
        private final byte[] _bytes;

        BytesBackedMultipartFile(String name, String originalFilename, String contentType, byte[] bytes) {
            this._name             = name;
            this._originalFilename = originalFilename;
            this._contentType      = contentType;
            this._bytes            = bytes != null ? bytes : new byte[0];
        }

        @Override public String  getName()             { return _name; }
        @Override public String  getOriginalFilename() { return _originalFilename; }
        @Override public String  getContentType()      { return _contentType; }
        @Override public boolean isEmpty()             { return _bytes.length == 0; }
        @Override public long    getSize()             { return _bytes.length; }
        @Override public byte[]  getBytes()            { return _bytes; }
        @Override public InputStream getInputStream()  { return new ByteArrayInputStream(_bytes); }
        @Override public void transferTo(File dest) throws IOException {
            throw new UnsupportedOperationException("Not supported for in-memory attachment");
        }
    }
}
