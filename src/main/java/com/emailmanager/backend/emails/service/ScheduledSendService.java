package com.emailmanager.backend.emails.service;

import com.emailmanager.backend.accounts.entity.EmailAccount;
import com.emailmanager.backend.accounts.repository.EmailAccountRepository;
import com.emailmanager.backend.emails.dto.PendingSendResponse;
import com.emailmanager.backend.emails.dto.SendEmailRequest;
import com.emailmanager.backend.emails.entity.PendingSend;
import com.emailmanager.backend.emails.repository.PendingSendRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Undo-send: queues emails for delayed delivery (default 10 seconds).
 *
 * <p>When a user sends an email, it is not dispatched immediately. Instead a row
 * is persisted in {@code pending_send} (status PENDING) AND an in-memory
 * {@link ScheduledFuture} is created for the fast path. The client can cancel
 * within the delay window via {@link #cancelSend(UUID)}.
 *
 * <p>Persistence makes undo-send crash-safe: a restart inside the delay window
 * no longer loses the mail. On startup {@link #recoverPendingSends()} reschedules
 * still-PENDING rows (or delivers past-due ones immediately). Delivery is
 * idempotent — the row is atomically claimed PENDING → SENT before the SMTP
 * dispatch, so the in-memory schedule and recovery can never double-send.
 */
@Service
@Slf4j
public class ScheduledSendService {

    private static final int SEND_DELAY_SECONDS = 10;

    private final EmailSendService sendService;
    private final PendingSendRepository pendingSendRepository;
    private final EmailAccountRepository accountRepository;
    private final ScheduledExecutorService scheduler;

    /** In-flight pending sends — keyed by sendId (fast cancellation path). */
    private final ConcurrentHashMap<UUID, PendingEntry> pendingMap = new ConcurrentHashMap<>();

    public ScheduledSendService(EmailSendService sendService,
                                PendingSendRepository pendingSendRepository,
                                EmailAccountRepository accountRepository) {
        this.sendService = sendService;
        this.pendingSendRepository = pendingSendRepository;
        this.accountRepository = accountRepository;
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "undo-send");
            t.setDaemon(true);
            return t;
        });
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Queues an email for delayed send. Persists a PENDING row (with eagerly
     * copied attachment bytes) and schedules the fast in-memory delivery.
     *
     * @return PendingSendResponse with the sendId and expiry timestamp
     */
    public PendingSendResponse queueSend(EmailAccount account, SendEmailRequest request, List<MultipartFile> attachments) {
        Instant expiresAt = Instant.now().plusSeconds(SEND_DELAY_SECONDS);

        // CRITICAL: MultipartFile is HTTP-request-scoped. Copy ALL bytes NOW,
        // before the request ends and streams are closed.
        List<BytesBackedMultipartFile> safeAttachments = new ArrayList<>();
        if (attachments != null) {
            for (MultipartFile attachment : attachments) {
                if (attachment == null || attachment.isEmpty()) continue;
                try {
                    String name = attachment.getOriginalFilename() != null
                            ? attachment.getOriginalFilename() : "attachment";
                    String ct = attachment.getContentType() != null
                            ? attachment.getContentType() : "application/octet-stream";
                    safeAttachments.add(new BytesBackedMultipartFile("attachment", name, ct, attachment.getBytes()));
                } catch (IOException e) {
                    log.error("[UndoSend] Failed to read attachment bytes: {}", e.getMessage());
                }
            }
        }

        // Serialize all attachments into the single DB blob column.
        byte[] serialized = serializeAttachments(safeAttachments);
        String firstFilename = safeAttachments.isEmpty() ? null : safeAttachments.get(0).getOriginalFilename();
        String firstContentType = safeAttachments.isEmpty() ? null : safeAttachments.get(0).getContentType();

        // Persist first so a crash before the schedule fires is still recoverable.
        UUID sendId = UUID.randomUUID();
        PendingSend row = PendingSend.builder()
                .id(sendId)
                .accountId(account.getId())
                .toAddress(request.to())
                .ccAddresses(join(request.cc()))
                .bccAddresses(join(request.bcc()))
                .subject(request.subject())
                .bodyText(request.bodyText())
                .bodyHtml(request.bodyHtml())
                .inReplyTo(request.inReplyTo())
                .attachmentBytes(serialized)
                .attachmentFilename(firstFilename)
                .attachmentContentType(firstContentType)
                .sendAt(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(SEND_DELAY_SECONDS))
                .status(PendingSend.PENDING)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
        pendingSendRepository.save(row);

        ScheduledFuture<?> future = scheduler.schedule(
                () -> deliver(sendId, account, request, safeAttachments),
                SEND_DELAY_SECONDS, TimeUnit.SECONDS);

        pendingMap.put(sendId, new PendingEntry(future, account, request));
        log.info("[UndoSend] Queued send {} for {} — {} attachment(s) — expires at {}",
                sendId, account.getEmailAddress(), safeAttachments.size(), expiresAt);

        return PendingSendResponse.queued(sendId, expiresAt);
    }

    /**
     * Cancels a pending send if it has not been dispatched yet.
     *
     * @return PendingSendResponse indicating whether cancellation succeeded
     */
    public PendingSendResponse cancelSend(UUID sendId) {
        // Atomically flip PENDING → CANCELLED in the DB (source of truth).
        int cancelledRows = pendingSendRepository.markCancelledIfPending(sendId);

        PendingEntry entry = pendingMap.remove(sendId);
        if (entry != null) {
            entry.future().cancel(false);
        }

        if (cancelledRows == 1) {
            log.info("[UndoSend] Cancelled send {}", sendId);
            return PendingSendResponse.cancelled(sendId);
        }
        // Row was already SENT/CANCELLED (or unknown) — treat as already sent.
        return PendingSendResponse.sent(sendId);
    }

    /**
     * Checks whether a sendId is still pending in memory (can be undone quickly).
     */
    public boolean isPending(UUID sendId) {
        return pendingMap.containsKey(sendId);
    }

    // ── Startup recovery ─────────────────────────────────────────────────────

    /**
     * On startup, finish any sends that were still PENDING when the process died:
     * reschedule future rows, deliver past-due ones immediately. Runs off the
     * scheduler thread so it never blocks startup. Idempotent via the atomic claim.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverPendingSends() {
        List<PendingSend> pendings;
        try {
            pendings = pendingSendRepository.findByStatus(PendingSend.PENDING);
        } catch (Exception e) {
            log.warn("[UndoSend] Recovery query failed: {}", e.getMessage());
            return;
        }
        if (pendings.isEmpty()) return;
        log.info("[UndoSend] Recovering {} pending send(s) after restart", pendings.size());

        for (PendingSend ps : pendings) {
            long delaySec = Duration.between(LocalDateTime.now(ZoneOffset.UTC), ps.getSendAt()).getSeconds();
            long safeDelay = Math.max(0, delaySec);
            scheduler.schedule(() -> deliverFromDb(ps.getId()), safeDelay, TimeUnit.SECONDS);
        }
    }

    // ── Delivery ─────────────────────────────────────────────────────────────

    /** Fast path: deliver using the in-memory account/request/attachments. */
    private void deliver(UUID sendId, EmailAccount account, SendEmailRequest request, List<BytesBackedMultipartFile> attachments) {
        try {
            if (pendingSendRepository.markSentIfPending(sendId) == 1) {
                sendService.sendEmail(account, request, new ArrayList<>(attachments));
                log.info("[UndoSend] Delivered send {} for {}", sendId, account.getEmailAddress());
            } else {
                log.debug("[UndoSend] Send {} already sent/cancelled — skipping", sendId);
            }
        } catch (Exception e) {
            log.error("[UndoSend] Failed to deliver send {}: {}", sendId, e.getMessage());
        } finally {
            pendingMap.remove(sendId);
        }
    }

    /** Recovery path: reload account/request/attachment from the persisted row. */
    private void deliverFromDb(UUID sendId) {
        PendingSend ps = pendingSendRepository.findById(sendId).orElse(null);
        if (ps == null || !PendingSend.PENDING.equals(ps.getStatus())) return;

        EmailAccount account = accountRepository.findById(ps.getAccountId()).orElse(null);
        if (account == null) {
            log.warn("[UndoSend] Recovery: account {} for send {} not found — dropping",
                    ps.getAccountId(), sendId);
            pendingSendRepository.markSentIfPending(sendId); // consume so we don't retry forever
            return;
        }

        SendEmailRequest request = new SendEmailRequest(
                ps.getToAddress(), split(ps.getCcAddresses()), split(ps.getBccAddresses()),
                ps.getSubject(), ps.getBodyHtml(), ps.getBodyText(), ps.getInReplyTo());

        List<BytesBackedMultipartFile> attachments = deserializeAttachments(ps.getAttachmentBytes());
        deliver(sendId, account, request, attachments);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String join(List<String> parts) {
        return (parts == null || parts.isEmpty()) ? null : String.join(";", parts);
    }

    private static List<String> split(String joined) {
        return (joined == null || joined.isBlank()) ? null : Arrays.asList(joined.split(";"));
    }

    /**
     * Serialize a list of in-memory attachments to a single byte[] for DB storage.
     * Format (DataOutputStream big-endian):
     *   int count
     *   for each:
     *     int nameLen, UTF name bytes
     *     int ctLen,   UTF contentType bytes
     *     int dataLen, data bytes
     */
    private static byte[] serializeAttachments(List<BytesBackedMultipartFile> list) {
        if (list == null || list.isEmpty()) return null;
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(bos)) {
            dos.writeInt(list.size());
            for (BytesBackedMultipartFile f : list) {
                byte[] nameB = f.getOriginalFilename().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] ctB   = f.getContentType().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] data  = f.getBytes();
                dos.writeInt(nameB.length); dos.write(nameB);
                dos.writeInt(ctB.length);   dos.write(ctB);
                dos.writeInt(data.length);  dos.write(data);
            }
            dos.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /** Inverse of {@link #serializeAttachments}. Returns empty list if blob is null/corrupt. */
    private static List<BytesBackedMultipartFile> deserializeAttachments(byte[] blob) {
        List<BytesBackedMultipartFile> result = new ArrayList<>();
        if (blob == null || blob.length == 0) return result;
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(blob))) {
            int count = dis.readInt();
            for (int i = 0; i < count; i++) {
                int nameLen = dis.readInt(); byte[] nameB = dis.readNBytes(nameLen);
                int ctLen   = dis.readInt(); byte[] ctB   = dis.readNBytes(ctLen);
                int dataLen = dis.readInt(); byte[] data  = dis.readNBytes(dataLen);
                result.add(new BytesBackedMultipartFile(
                        "attachment",
                        new String(nameB, java.nio.charset.StandardCharsets.UTF_8),
                        new String(ctB,   java.nio.charset.StandardCharsets.UTF_8),
                        data));
            }
        } catch (IOException e) {
            // Corrupt blob — fall back to legacy single-attachment format handled below
        }
        return result;
    }

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
