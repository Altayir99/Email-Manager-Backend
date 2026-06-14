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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polls each email account's INBOX every 60 seconds for new emails.
 * Triggers FCM push when unseen emails are detected since last poll.
 *
 * Fix: reads the real FCM token from account.getUser().getFcmToken()
 * instead of passing null. Skips accounts with no registered token.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmailPollingScheduler {

    private final EmailAccountRepository accountRepository;
    private final ImapConnectionService imapConnectionService;
    private final PushNotificationService pushService;

    // Track last check time per account UUID
    private final ConcurrentHashMap<String, LocalDateTime> lastChecked = new ConcurrentHashMap<>();

    // Cap concurrent IMAP polls to avoid hitting Gmail's ~15 connection limit
    private static final int MAX_CONCURRENT_POLLS = 3;
    private final java.util.concurrent.Semaphore pollSemaphore =
            new java.util.concurrent.Semaphore(MAX_CONCURRENT_POLLS, true);

    @Scheduled(fixedDelay = 30_000)
    public void pollAllAccounts() {
        List<EmailAccount> accounts = accountRepository.findAllWithUser();
        for (EmailAccount account : accounts) {
            try {
                pollAccount(account);
            } catch (Exception e) {
                log.warn("[Poll] Error polling {}: {}", account.getEmailAddress(), e.getMessage());
            }
        }
    }

    private void pollAccount(EmailAccount account) throws MessagingException {
        if (!pollSemaphore.tryAcquire()) {
            log.debug("[Poll] Skipping {} — too many concurrent polls", account.getEmailAddress());
            return;
        }
        try {
            String fcmToken = account.getUser() != null ? account.getUser().getFcmToken() : null;
            if (fcmToken == null || fcmToken.isBlank()) return;

            String accountKey = account.getId().toString();
            LocalDateTime since = lastChecked.getOrDefault(accountKey,
                    LocalDateTime.now().minusMinutes(2));
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
                                .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
                        if (!receivedAt.isAfter(since)) continue;

                        String senderName = "", senderEmail = "";
                        if (msg.getFrom() != null && msg.getFrom().length > 0) {
                            Address from = msg.getFrom()[0];
                            if (from instanceof InternetAddress ia) {
                                senderEmail = ia.getAddress() != null ? ia.getAddress() : "";
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
