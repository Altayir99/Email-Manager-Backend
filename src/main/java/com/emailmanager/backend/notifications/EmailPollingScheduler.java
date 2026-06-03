package com.emailmanager.backend.notifications;

import com.emailmanager.backend.accounts.entity.EmailAccount;
import com.emailmanager.backend.accounts.repository.EmailAccountRepository;
import com.emailmanager.backend.accounts.service.ImapConnectionService;
import com.emailmanager.backend.user.UserRepository;
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
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmailPollingScheduler {

    private final EmailAccountRepository accountRepository;
    private final ImapConnectionService imapConnectionService;
    private final PushNotificationService pushService;
    private final UserRepository userRepository;

    // Track last check time per account
    private final ConcurrentHashMap<String, LocalDateTime> lastChecked = new ConcurrentHashMap<>();

    @Scheduled(fixedDelay = 60_000) // every 60 seconds
    public void pollAllAccounts() {
        List<EmailAccount> accounts = accountRepository.findAll();
        for (EmailAccount account : accounts) {
            try {
                pollAccount(account);
            } catch (Exception e) {
                log.warn("[Poll] Error polling {}: {}", account.getEmailAddress(), e.getMessage());
            }
        }
    }

    private void pollAccount(EmailAccount account) throws MessagingException {
        String accountKey = account.getId().toString();
        LocalDateTime since = lastChecked.getOrDefault(accountKey,
                LocalDateTime.now().minusMinutes(2));
        lastChecked.put(accountKey, LocalDateTime.now());

        Store store = imapConnectionService.getStore(account);
        IMAPFolder inbox = (IMAPFolder) store.getFolder("INBOX");
        if (!inbox.isOpen()) inbox.open(Folder.READ_ONLY);

        int total = inbox.getMessageCount();
        int unread = inbox.getUnreadMessageCount();
        if (unread == 0) {
            inbox.close(false);
            return;
        }

        // Get recent messages (last 5 max)
        int start = Math.max(1, total - 4);
        Message[] messages = inbox.getMessages(start, total);

        for (Message msg : messages) {
            if (msg.isSet(Flags.Flag.SEEN)) continue;

            Date received = msg.getReceivedDate();
            if (received == null) continue;

            LocalDateTime receivedAt = received.toInstant()
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDateTime();

            if (receivedAt.isAfter(since)) {
                String senderName = "";
                String senderEmail = "";
                if (msg.getFrom() != null && msg.getFrom().length > 0) {
                    Address from = msg.getFrom()[0];
                    if (from instanceof InternetAddress ia) {
                        senderEmail = ia.getAddress() != null ? ia.getAddress() : "";
                        senderName = ia.getPersonal() != null ? ia.getPersonal() : senderEmail;
                    }
                }

                String subject = msg.getSubject() != null ? msg.getSubject() : "(no subject)";

                // TODO: Replace with actual FCM token from device registration
                // For now, log it. Token storage comes in next step.
                log.info("[Poll] New email for {}: from={} subject={}",
                        account.getEmailAddress(), senderName, subject);

                pushService.sendNewEmailNotification(
                        null, // FCM token — stored per user, wired in next step
                        senderName,
                        subject,
                        "",
                        account.getId().toString()
                );
            }
        }
        inbox.close(false);
    }
}
