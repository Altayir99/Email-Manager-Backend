package com.emailmanager.backend.notifications;

import com.emailmanager.backend.accounts.entity.EmailAccount;
import com.emailmanager.backend.accounts.repository.EmailAccountRepository;
import com.emailmanager.backend.accounts.service.ImapConnectionService;
import com.sun.mail.imap.IMAPFolder;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.concurrent.*;

/**
 * Polls each email account's INBOX for new emails and sends FCM push notifications.
 *
 * Architecture (scales to 10 accounts):
 *
 *  Previously: sequential loop — 10 accounts × ~1.5s each = ~15s per cycle.
 *  Now: parallel thread pool — ceil(10 / MAX_CONCURRENT) batches × ~2s = ~4s per cycle,
 *  followed by fixedDelay rest. Effective notification latency ≈ 4s + 15s + FCM ≈ 20s worst case.
 *
 *  Concurrency model:
 *   - pollExecutor: fixed thread pool of MAX_CONCURRENT threads → true parallel IMAP I/O
 *   - pollSemaphore: secondary guard so the IMAP connection pool isn't overwhelmed
 *     even if called from multiple scheduler threads in future
 *   - ReentrantLock inside ImapConnectionService: per-account serialisation (unchanged)
 *
 *  fixedDelay = 15s: waits for ALL accounts to finish before starting the next cycle.
 *  With 5 parallel threads and 10 accounts, one cycle takes ~4s → rest 15s → ~19s total.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmailPollingScheduler {

    private final EmailAccountRepository accountRepository;
    private final ImapConnectionService imapConnectionService;
    private final PushNotificationService pushService;

    // Last poll timestamp per account, shared across threads safely
    private final ConcurrentHashMap<String, LocalDateTime> lastChecked = new ConcurrentHashMap<>();

    // ── Concurrency config ────────────────────────────────────────────────────

    // How many accounts to poll truly in parallel.
    // 5 is safe for Gmail (~15 connection limit per account, and we have multiple accounts).
    private static final int MAX_CONCURRENT = 5;

    // Thread pool — dedicated to IMAP polling so it doesn't interfere with
    // request-handling threads. Named threads make logs easier to read.
    private final ExecutorService pollExecutor = Executors.newFixedThreadPool(
            MAX_CONCURRENT,
            r -> {
                Thread t = new Thread(r, "email-poll-" + System.nanoTime() % 100);
                t.setDaemon(true);
                return t;
            });

    // Semaphore guards against concurrent scheduler invocations (edge case)
    private final Semaphore pollSemaphore = new Semaphore(MAX_CONCURRENT, true);

    // ── Scheduler ─────────────────────────────────────────────────────────────

    /**
     * Polls all accounts in parallel. fixedDelay means the next cycle doesn't start
     * until this one fully completes, so there's never an overlap.
     *
     * With MAX_CONCURRENT=5:
     *   5 accounts  → 1 parallel batch  → ~2s poll + 15s rest = 17s cycle
     *   10 accounts → 2 parallel batches → ~4s poll + 15s rest = 19s cycle
     */
    @Scheduled(fixedDelay = 15_000)
    public void pollAllAccounts() {
        List<EmailAccount> accounts = accountRepository.findAllWithUser();
        if (accounts.isEmpty()) return;

        log.debug("[Poll] Starting cycle for {} accounts", accounts.size());
        long start = System.currentTimeMillis();

        // Submit all accounts to the thread pool simultaneously
        List<CompletableFuture<Void>> futures = accounts.stream()
                .map(account -> CompletableFuture.runAsync(
                        () -> safelyPollAccount(account),
                        pollExecutor))
                .toList();

        // Wait for the entire cycle to finish before fixedDelay countdown starts
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        log.debug("[Poll] Cycle done in {}ms", System.currentTimeMillis() - start);
    }

    // ── Per-account poll ──────────────────────────────────────────────────────

    /** Wrapper that never throws — all errors are logged and swallowed per account. */
    private void safelyPollAccount(EmailAccount account) {
        try {
            pollAccount(account);
        } catch (Exception e) {
            log.warn("[Poll] Error polling {}: {}", account.getEmailAddress(), e.getMessage());
        }
    }

    private void pollAccount(EmailAccount account) throws MessagingException {
        // Semaphore tryAcquire guards against concurrent scheduler edge cases.
        // Under normal operation (fixedDelay) this never blocks — just a safety net.
        if (!pollSemaphore.tryAcquire()) {
            log.debug("[Poll] Skipping {} — semaphore full", account.getEmailAddress());
            return;
        }
        try {
            String fcmToken = account.getUser() != null ? account.getUser().getFcmToken() : null;
            if (fcmToken == null || fcmToken.isBlank()) return;

            String accountKey = account.getId().toString();
            LocalDateTime since = lastChecked.getOrDefault(accountKey,
                    LocalDateTime.now().minusMinutes(3));
            lastChecked.put(accountKey, LocalDateTime.now());

            Store store = imapConnectionService.acquireStore(account);
            try {
                IMAPFolder inbox = (IMAPFolder) store.getFolder("INBOX");
                if (!inbox.isOpen()) inbox.open(Folder.READ_ONLY);
                try {
                    int total  = inbox.getMessageCount();
                    int unread = inbox.getUnreadMessageCount();
                    if (unread == 0) return;

                    int start = Math.max(1, total - 4);
                    Message[] messages = inbox.getMessages(start, total);

                    for (Message msg : messages) {
                        if (msg.isSet(Flags.Flag.SEEN)) continue;
                        Date received = msg.getReceivedDate();
                        if (received == null) continue;
                        LocalDateTime receivedAt = received.toInstant()
                                .atZone(java.time.ZoneId.systemDefault())
                                .toLocalDateTime();
                        if (!receivedAt.isAfter(since)) continue;

                        String senderName = "", senderEmail = "";
                        if (msg.getFrom() != null && msg.getFrom().length > 0) {
                            Address from = msg.getFrom()[0];
                            if (from instanceof InternetAddress ia) {
                                senderEmail = ia.getAddress()  != null ? ia.getAddress()  : "";
                                senderName  = ia.getPersonal() != null ? ia.getPersonal() : senderEmail;
                            }
                        }
                        String subject = msg.getSubject() != null ? msg.getSubject() : "(no subject)";
                        log.info("[Poll] New email for {}: from={} subject={}",
                                account.getEmailAddress(), senderName, subject);

                        pushService.sendNewEmailNotification(
                                fcmToken, senderName, subject, "", account.getId().toString());
                    }
                } finally {
                    if (inbox.isOpen()) inbox.close(false);
                }
            } finally {
                imapConnectionService.releaseStore(account.getId());
            }

            account.setLastSyncedAt(LocalDateTime.now());
            accountRepository.save(account);

        } finally {
            pollSemaphore.release();
        }
    }
}
