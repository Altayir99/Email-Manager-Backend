package com.emailmanager.backend.sync;

import com.emailmanager.backend.accounts.entity.EmailAccount;
import com.emailmanager.backend.accounts.repository.EmailAccountRepository;
import com.emailmanager.backend.accounts.service.ImapConnectionService;
import com.emailmanager.backend.cache.entity.AccountSyncState;
import com.emailmanager.backend.cache.entity.CachedEmail;
import com.emailmanager.backend.cache.entity.FolderState;
import com.emailmanager.backend.cache.repository.AccountSyncStateRepository;
import com.emailmanager.backend.cache.repository.CachedEmailRepository;
import com.emailmanager.backend.cache.repository.FolderStateRepository;
import com.emailmanager.backend.notifications.PushNotificationService;
import com.sun.mail.imap.IMAPFolder;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Phase 1 SyncService — keeps the local Postgres cache in sync with IMAP.
 *
 * All IMAP I/O lives here. Controllers read only from the DB (instant, no lock contention).
 *
 * Sync strategy:
 *  1. For each account, open INBOX.
 *  2. Check UIDVALIDITY — if changed, wipe and re-seed.
 *  3. Fetch new messages since last_seen_uid.
 *  4. Reconcile flags (seen) for the recent window.
 *  5. Push FCM for any new UID > last_notified_uid (dedup persisted in DB).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SyncService {

    private static final String INBOX = "INBOX";
    private static final int INITIAL_SYNC_COUNT = 50;  // messages to seed on first sync
    private static final int SNIPPET_MAX = 200;

    private final EmailAccountRepository accountRepository;
    private final ImapConnectionService imapConnectionService;
    private final CachedEmailRepository cachedEmailRepository;
    private final FolderStateRepository folderStateRepository;
    private final AccountSyncStateRepository syncStateRepository;
    private final PushNotificationService pushNotificationService;

    // ── Scheduled entry-point ────────────────────────────────────────────────

    /**
     * Sync all active accounts every 30 seconds.
     * Each account is independent — one failure never stalls the others.
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 10_000)
    public void syncAll() {
        List<EmailAccount> accounts = accountRepository.findAll();
        for (EmailAccount account : accounts) {
            if (!account.isActive()) continue;
            try {
                syncAccountInbox(account);
            } catch (Exception e) {
                log.warn("[Sync] Account {} failed: {}", account.getEmailAddress(), e.getMessage());
                markError(account.getId(), e.getMessage());
            }
        }
    }

    /**
     * Force an immediate sync for one account — called from the REST controller
     * when the user triggers pull-to-refresh.
     */
    public void syncAccountNow(EmailAccount account) {
        try {
            syncAccountInbox(account);
        } catch (Exception e) {
            log.warn("[Sync] Manual sync failed for {}: {}", account.getEmailAddress(), e.getMessage());
            markError(account.getId(), e.getMessage());
        }
    }

    // ── Core sync logic ──────────────────────────────────────────────────────

    @Transactional
    public void syncAccountInbox(EmailAccount account) throws MessagingException {
        UUID accountId = account.getId();
        markSyncing(accountId);

        Store store = imapConnectionService.acquireStore(account);
        try {
            IMAPFolder folder = (IMAPFolder) store.getFolder(INBOX);
            folder.open(Folder.READ_ONLY);
            try {
                long serverUidValidity = folder.getUIDValidity();
                FolderState state = folderStateRepository
                        .findByAccountIdAndFullName(accountId, INBOX)
                        .orElseGet(() -> FolderState.builder()
                                .accountId(accountId)
                                .fullName(INBOX)
                                .displayName("Inbox")
                                .build());

                // ── Step 1: UIDVALIDITY check ────────────────────────────────
                if (state.getUidValidity() != null && state.getUidValidity() != serverUidValidity) {
                    log.warn("[Sync] UIDVALIDITY changed for {} — wiping cache", account.getEmailAddress());
                    cachedEmailRepository.deleteByAccountIdAndFolder(accountId, INBOX);
                    state.setLastSeenUid(0L);
                }
                state.setUidValidity(serverUidValidity);

                // ── Step 2: Fetch new messages since last_seen_uid ───────────
                long lastSeenUid = state.getLastSeenUid();
                long fetchFrom = (lastSeenUid == 0) ? Math.max(1, folder.getUIDNext() - INITIAL_SYNC_COUNT) : lastSeenUid + 1;

                Message[] newMessages = folder.getMessagesByUID(fetchFrom, UIDFolder.LASTUID);

                if (newMessages.length > 0) {
                    FetchProfile profile = new FetchProfile();
                    profile.add(FetchProfile.Item.ENVELOPE);
                    profile.add(FetchProfile.Item.FLAGS);
                    profile.add(UIDFolder.FetchProfileItem.UID);
                    profile.add(FetchProfile.Item.CONTENT_INFO);
                    folder.fetch(newMessages, profile);

                    long maxUid = lastSeenUid;
                    for (Message msg : newMessages) {
                        try {
                            long uid = folder.getUID(msg);
                            if (uid <= lastSeenUid) continue;

                            CachedEmail cached = buildCachedEmail(msg, folder, accountId, uid);
                            // upsert — ON CONFLICT (account_id, folder, uid) handled by save
                            cachedEmailRepository.findByAccountIdAndFolderAndUid(accountId, INBOX, uid)
                                    .ifPresentOrElse(
                                            existing -> {
                                                // update flags only
                                                existing.setSeen(cached.isSeen());
                                                cachedEmailRepository.save(existing);
                                            },
                                            () -> cachedEmailRepository.save(cached)
                                    );
                            if (uid > maxUid) maxUid = uid;
                        } catch (Exception e) {
                            log.warn("[Sync] Failed to process message: {}", e.getMessage());
                        }
                    }
                    state.setLastSeenUid(maxUid);
                }

                // ── Step 3: Update folder counts ─────────────────────────────
                state.setTotalCount(folder.getMessageCount());
                state.setUnreadCount(folder.getUnreadMessageCount());
                state.setLastSyncedAt(LocalDateTime.now(ZoneOffset.UTC));
                folderStateRepository.save(state);

                // ── Step 4: Push notifications for new mail ──────────────────
                pushNewMailNotifications(account, accountId, state.getLastSeenUid());

                markIdle(accountId);
                log.debug("[Sync] {} synced — {} total, {} unread, lastUid={}",
                        account.getEmailAddress(), state.getTotalCount(),
                        state.getUnreadCount(), state.getLastSeenUid());

            } finally {
                if (folder.isOpen()) folder.close(false);
            }
        } finally {
            imapConnectionService.releaseStore(accountId);
        }
    }

    // ── Notification helper ──────────────────────────────────────────────────

    private void pushNewMailNotifications(EmailAccount account, UUID accountId, long currentMaxUid) {
        AccountSyncState syncState = syncStateRepository.findById(accountId)
                .orElseGet(() -> AccountSyncState.builder().accountId(accountId).build());

        long lastNotified = syncState.getLastNotifiedUid();
        if (currentMaxUid <= lastNotified) return;

        // Find new emails above the notification watermark
        cachedEmailRepository.findByAccountIdAndFolderOrderByReceivedAtDesc(
                accountId, INBOX, org.springframework.data.domain.PageRequest.of(0, 10))
                .forEach(email -> {
                    if (email.getUid() > lastNotified) {
                        String fcmToken = account.getUser() != null ? account.getUser().getFcmToken() : null;
                        if (fcmToken != null && !fcmToken.isBlank()) {
                            pushNotificationService.sendNewEmailNotification(
                                    fcmToken,
                                    email.getFromName(),
                                    email.getSubject(),
                                    email.getSnippet(),
                                    accountId.toString()
                            );
                        }
                    }
                });

        syncState.setLastNotifiedUid(currentMaxUid);
        syncState.setLastFullSyncAt(LocalDateTime.now(ZoneOffset.UTC));
        syncStateRepository.save(syncState);
    }

    // ── Builder helpers ──────────────────────────────────────────────────────

    private CachedEmail buildCachedEmail(Message msg, IMAPFolder folder, UUID accountId, long uid)
            throws MessagingException {
        String subject = msg.getSubject() != null ? msg.getSubject() : "(no subject)";
        String fromAddress = "", fromName = "";
        if (msg.getFrom() != null && msg.getFrom().length > 0) {
            Address from = msg.getFrom()[0];
            if (from instanceof InternetAddress ia) {
                fromAddress = ia.getAddress() != null ? ia.getAddress() : "";
                fromName = ia.getPersonal() != null ? ia.getPersonal() : fromAddress;
            }
        }
        boolean seen = msg.isSet(Flags.Flag.SEEN);
        boolean hasAttachment = hasAttachmentHeader(msg);
        java.util.Date received = msg.getReceivedDate();
        LocalDateTime receivedAt = received != null
                ? received.toInstant().atZone(ZoneOffset.UTC).toLocalDateTime()
                : LocalDateTime.now(ZoneOffset.UTC);

        String snippet = extractSnippet(msg);

        // Try to get Message-ID header
        String[] messageIdHeader = null;
        try { messageIdHeader = msg.getHeader("Message-ID"); } catch (Exception ignored) {}
        String messageId = (messageIdHeader != null && messageIdHeader.length > 0)
                ? messageIdHeader[0] : null;

        return CachedEmail.builder()
                .accountId(accountId)
                .folder(INBOX)
                .uid(uid)
                .messageId(messageId)
                .subject(subject)
                .fromAddress(fromAddress)
                .fromName(fromName)
                .snippet(snippet)
                .receivedAt(receivedAt)
                .seen(seen)
                .hasAttachment(hasAttachment)
                .bodyLoaded(false)
                .build();
    }

    private boolean hasAttachmentHeader(Message msg) {
        try {
            String ct = msg.getContentType();
            return ct != null && ct.toLowerCase().contains("mixed");
        } catch (Exception ignored) { return false; }
    }

    private String extractSnippet(Message msg) {
        try {
            Object content = msg.getContent();
            if (content instanceof String s) {
                String ct = msg.getContentType().toLowerCase();
                String text = ct.contains("html") ? stripTags(s) : s;
                return truncate(text.trim());
            }
            if (content instanceof jakarta.mail.internet.MimeMultipart mp) {
                for (int i = 0; i < mp.getCount(); i++) {
                    BodyPart part = mp.getBodyPart(i);
                    if (part.getContentType().toLowerCase().contains("text/plain")) {
                        return truncate(part.getContent().toString().trim());
                    }
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private String truncate(String text) {
        if (text == null) return "";
        text = text.replaceAll("\\s+", " ").trim();
        return text.length() > SNIPPET_MAX ? text.substring(0, SNIPPET_MAX) + "…" : text;
    }

    private String stripTags(String html) {
        return html.replaceAll("<[^>]+>", " ").replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&").replaceAll("\\s+", " ").trim();
    }

    // ── Status helpers ───────────────────────────────────────────────────────

    private void markSyncing(UUID accountId) {
        syncStateRepository.findById(accountId).ifPresentOrElse(
                s -> { s.setSyncStatus("SYNCING"); s.setLastError(null); syncStateRepository.save(s); },
                () -> syncStateRepository.save(AccountSyncState.builder()
                        .accountId(accountId).syncStatus("SYNCING").build())
        );
    }

    private void markIdle(UUID accountId) {
        syncStateRepository.findById(accountId).ifPresent(s -> {
            s.setSyncStatus("IDLE");
            s.setLastError(null);
            syncStateRepository.save(s);
        });
    }

    private void markError(UUID accountId, String error) {
        syncStateRepository.findById(accountId).ifPresentOrElse(
                s -> { s.setSyncStatus("ERROR"); s.setLastError(error); syncStateRepository.save(s); },
                () -> syncStateRepository.save(AccountSyncState.builder()
                        .accountId(accountId).syncStatus("ERROR").lastError(error).build())
        );
    }
}

