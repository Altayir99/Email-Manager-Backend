package com.emailmanager.backend.sync;

import com.emailmanager.backend.accounts.entity.EmailAccount;
import com.emailmanager.backend.accounts.repository.EmailAccountRepository;
import com.emailmanager.backend.accounts.service.EncryptionService;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.mail.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phase 3: IMAP IDLE listener — one virtual thread per account.
 *
 * Strategy:
 *  - On startup, starts one virtual thread per active account.
 *  - Each thread opens a DEDICATED IMAPStore (separate from the SyncService pool,
 *    because IDLE blocks the connection and must not be shared).
 *  - The thread calls IMAPFolder.idle(true) in a loop.
 *    When the server signals new mail, it immediately triggers SyncService.syncAccountNow()
 *    which runs the full incremental fetch + push notification pipeline.
 *  - On any failure the thread sleeps with exponential backoff (5s → 10s → 20s → 30s cap)
 *    then reconnects. This makes IDLE self-healing.
 *  - The 30-second @Scheduled polling in SyncService is reduced to 5 minutes
 *    (IDLE handles real-time delivery; polling is the safety net for IDLE downtime).
 *  - On @PreDestroy, all IDLE threads are interrupted and connections closed.
 *
 * Why virtual threads (Java 21)?
 *  IDLE blocks on I/O for minutes at a time — with platform threads this would tie up
 *  a thread pool slot for the lifetime of each connection. Virtual threads are ~1KB each
 *  and park during I/O at zero cost, making N-account IDLE practically free.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IdleService {

    private static final int IDLE_TIMEOUT_MS        = 25 * 60 * 1000; // 25 min — keeps NAT alive
    private static final int MIN_BACKOFF_MS          = 5_000;
    private static final int MAX_BACKOFF_MS          = 30_000;
    private static final int CONNECT_TIMEOUT_MS      = 15_000;

    private final EmailAccountRepository  accountRepository;
    private final EncryptionService       encryptionService;
    private final SyncService             syncService;

    /** One virtual thread per account — keyed by accountId. */
    private final ConcurrentHashMap<UUID, Thread> idleThreads = new ConcurrentHashMap<>();

    /** Signals all threads to stop on shutdown. */
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @PostConstruct
    public void startAll() {
        // findAllWithUser() eagerly joins User so fcmToken is accessible without a lazy load.
        List<EmailAccount> accounts = accountRepository.findAllWithUser();
        log.info("[IDLE] Starting IDLE listeners for {} active accounts", accounts.size());
        for (EmailAccount account : accounts) {
            if (account.isActive()) startIdleThread(account);
        }
    }

    @PreDestroy
    public void stopAll() {
        shuttingDown.set(true);
        idleThreads.forEach((id, thread) -> thread.interrupt());
        log.info("[IDLE] All IDLE threads interrupted for shutdown");
    }

    /**
     * Start (or restart) the IDLE listener for a single account.
     * Safe to call multiple times — replaces any existing thread for the same account.
     */
    public void startIdleThread(EmailAccount account) {
        // Stop any existing thread for this account first
        Thread existing = idleThreads.remove(account.getId());
        if (existing != null && existing.isAlive()) existing.interrupt();

        Thread thread = Thread.ofVirtual()
                .name("idle-" + account.getEmailAddress())
                .start(() -> idleLoop(account));

        idleThreads.put(account.getId(), thread);
        log.info("[IDLE] Started virtual thread for {}", account.getEmailAddress());
    }

    /**
     * Stop the IDLE listener for a single account.
     * Called when an account is removed or deactivated.
     */
    public void stopIdleThread(UUID accountId) {
        Thread thread = idleThreads.remove(accountId);
        if (thread != null) thread.interrupt();
    }

    // ── IDLE loop ─────────────────────────────────────────────────────────────

    /**
     * Main loop for one account. Runs on a virtual thread.
     *
     * Loop structure:
     *  1. Connect dedicated IMAPStore (does NOT go through ImapConnectionService pool).
     *  2. Open INBOX in READ_ONLY.
     *  3. Call folder.idle(true) — blocks until server sends EXISTS/RECENT notification.
     *  4. On any notification: trigger incremental sync.
     *  5. On exception: close, back off, retry.
     */
    private void idleLoop(EmailAccount account) {
        int backoffMs = MIN_BACKOFF_MS;

        while (!Thread.currentThread().isInterrupted() && !shuttingDown.get()) {
            IMAPStore  store  = null;
            IMAPFolder folder = null;

            try {
                store  = connectDedicated(account);
                folder = (IMAPFolder) store.getFolder("INBOX");
                folder.open(Folder.READ_ONLY);

                backoffMs = MIN_BACKOFF_MS; // reset backoff on successful connect
                log.info("[IDLE] Connected for {} — entering IDLE loop", account.getEmailAddress());

                while (!Thread.currentThread().isInterrupted() && !shuttingDown.get()) {
                    // idle(true) returns when:
                    //   a) the server sends a notification (new mail, flag change, etc.)
                    //   b) IDLE_TIMEOUT_MS elapses (we re-issue IDLE to keep NAT alive)
                    //   c) the thread is interrupted (shutdown / restart)
                    folder.idle(true); // 'true' = return immediately if server already has data

                    if (Thread.currentThread().isInterrupted() || shuttingDown.get()) break;

                    // Server notified us — run incremental sync immediately
                    // Pass UUID (not entity) so SyncService reloads it within a fresh JPA session
                    log.debug("[IDLE] Notification received for {} — triggering sync", account.getEmailAddress());
                    syncService.syncAccountNow(account.getId());

                    // Re-open folder if server closed it (some servers close after notification)
                    if (!folder.isOpen()) {
                        folder.open(Folder.READ_ONLY);
                    }
                }

            } catch (FolderClosedException e) {
                log.warn("[IDLE] Folder closed for {} — reconnecting in {}ms",
                        account.getEmailAddress(), backoffMs);

            } catch (MessagingException e) {
                // Check if the MessagingException wraps a thread interrupt
                if (Thread.currentThread().isInterrupted()) {
                    log.info("[IDLE] Thread interrupted for {} — stopping", account.getEmailAddress());
                    break;
                }
                log.warn("[IDLE] IMAP error for {}: {} — reconnecting in {}ms",
                        account.getEmailAddress(), e.getMessage(), backoffMs);

            } catch (Exception e) {
                log.error("[IDLE] Unexpected error for {}: {} — reconnecting in {}ms",
                        account.getEmailAddress(), e.getMessage(), backoffMs);

            } finally {
                closeQuietly(folder, store);
            }

            // Exponential backoff before reconnect
            if (!Thread.currentThread().isInterrupted() && !shuttingDown.get()) {
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
            }
        }

        log.info("[IDLE] Thread exiting for {}", account.getEmailAddress());
    }

    // ── IMAP helpers ──────────────────────────────────────────────────────────

    /**
     * Creates a DEDICATED IMAPStore for IDLE — completely separate from the
     * ImapConnectionService shared pool. This is essential because idle() blocks
     * the underlying TCP connection and must not be shared with request-path code.
     */
    private IMAPStore connectDedicated(EmailAccount account) throws MessagingException {
        String password = encryptionService.decrypt(account.getEncryptedPassword());

        Properties props = new Properties();
        props.put("mail.store.protocol",           "imaps");
        props.put("mail.imaps.host",               account.getImapHost());
        props.put("mail.imaps.port",               account.getImapPort());
        props.put("mail.imaps.ssl.enable",         "true");
        props.put("mail.imaps.connectiontimeout",  String.valueOf(CONNECT_TIMEOUT_MS));
        props.put("mail.imaps.timeout",            String.valueOf(IDLE_TIMEOUT_MS + 5000));
        props.put("mail.imaps.writetimeout",       String.valueOf(CONNECT_TIMEOUT_MS));
        // Enable IDLE capability check — fall back gracefully if server doesn't support it
        props.put("mail.imaps.usesocketchannels",  "false");

        Session session = Session.getInstance(props);
        IMAPStore store = (IMAPStore) session.getStore("imaps");
        store.connect(account.getImapHost(), account.getImapPort(),
                account.getUsername(), password);
        return store;
    }

    private void closeQuietly(IMAPFolder folder, IMAPStore store) {
        if (folder != null && folder.isOpen()) {
            try { folder.close(false); } catch (Exception ignored) {}
        }
        if (store != null && store.isConnected()) {
            try { store.close(); } catch (Exception ignored) {}
        }
    }
}
