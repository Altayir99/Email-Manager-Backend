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
import java.util.stream.Collectors;

/**
 * Phase 2 SyncService — keeps the local Postgres cache in sync with IMAP.
 *
 * Sync strategy per INBOX cycle:
 *  1. UIDVALIDITY check — wipe + reseed on change.
 *  2. Incremental fetch: new messages only (UID > last_seen_uid).
 *  3. Flag reconciliation (throttled to once per 5 min): fetch flags for
 *     the most recent FLAG_RECONCILE_WINDOW UIDs and update seen status.
 *  4. Deletion detection: compare server UIDs in the recent window against
 *     cached UIDs — evict rows that are no longer on the server.
 *  5. FCM push for any new UID > last_notified_uid (dedup persisted in DB).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SyncService {

    private static final String INBOX = "INBOX";
    private static final int INITIAL_SYNC_COUNT  = 50;   // messages to seed on first sync
    private static final int SNIPPET_MAX          = 200;
    private static final int FLAG_RECONCILE_WINDOW = 200;  // how many recent UIDs to reconcile
    private static final int FLAG_RECONCILE_INTERVAL_MINUTES = 5;

    private final EmailAccountRepository      accountRepository;
    private final ImapConnectionService       imapConnectionService;
    private final CachedEmailRepository       cachedEmailRepository;
    private final FolderStateRepository       folderStateRepository;
    private final AccountSyncStateRepository  syncStateRepository;
    private final PushNotificationService     pushNotificationService;

    // ── Scheduled entry-point ────────────────────────────────────────────────

    /**
     * Safety-net poll every 5 minutes.
     * IdleService provides real-time IMAP IDLE delivery; this catches any
     * messages missed during IDLE reconnect windows or server IDLE failures.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 30_000)
    public void syncAll() {
        List<EmailAccount> accounts = accountRepository.findAllWithUser();
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

    /** Force an immediate INBOX sync — called on pull-to-refresh. */
    public void syncAccountNow(EmailAccount account) {
        try {
            syncAccountInbox(account);
        } catch (Exception e) {
            log.warn("[Sync] Manual sync failed for {}: {}", account.getEmailAddress(), e.getMessage());
            markError(account.getId(), e.getMessage());
        }
    }

    /** Force an immediate sync for a specific folder (non-INBOX on-demand). */
    public void syncAccountNow(EmailAccount account, String folderName) {
        try {
            syncAccountInbox(account);   // Phase 2 uses INBOX only for now; Phase 3 will add folder routing
        } catch (Exception e) {
            log.warn("[Sync] Manual sync failed for {}/{}: {}", account.getEmailAddress(), folderName, e.getMessage());
            markError(account.getId(), e.getMessage());
        }
    }

    /**
     * Public entry point for folder-specific sync — used by tests and the future IdleService.
     * Delegates to syncAccountInbox (Phase 3 will add multi-folder routing here).
     */
    public void syncAccountFolder(EmailAccount account, String folderName) throws MessagingException {
        syncAccountInbox(account);
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
                    state.setLastFlagReconcileAt(null);
                }
                state.setUidValidity(serverUidValidity);

                // ── Step 2: Incremental fetch (new messages only) ────────────
                long lastSeenUid = state.getLastSeenUid();
                long fetchFrom = (lastSeenUid == 0)
                        ? Math.max(1, folder.getUIDNext() - INITIAL_SYNC_COUNT)
                        : lastSeenUid + 1;

                Message[] newMessages = folder.getMessagesByUID(fetchFrom, UIDFolder.LASTUID);
                if (newMessages == null) newMessages = new Message[0]; // null-safe: some servers return null for empty range

                long maxUid = lastSeenUid;
                if (newMessages.length > 0) {
                    FetchProfile profile = new FetchProfile();
                    profile.add(FetchProfile.Item.ENVELOPE);
                    profile.add(FetchProfile.Item.FLAGS);
                    profile.add(UIDFolder.FetchProfileItem.UID);
                    profile.add(FetchProfile.Item.CONTENT_INFO);
                    folder.fetch(newMessages, profile);

                    for (Message msg : newMessages) {
                        try {
                            long uid = folder.getUID(msg);
                            if (uid <= lastSeenUid) continue;

                            CachedEmail cached = buildCachedEmail(msg, folder, accountId, uid);
                            cachedEmailRepository.findByAccountIdAndFolderAndUid(accountId, INBOX, uid)
                                    .ifPresentOrElse(
                                            existing -> {
                                                // Upsert: update flags + fill in To/CC if missing
                                                existing.setSeen(cached.isSeen());
                                                if (existing.getToAddresses() == null) {
                                                    existing.setToAddresses(cached.getToAddresses());
                                                }
                                                if (existing.getCcAddresses() == null) {
                                                    existing.setCcAddresses(cached.getCcAddresses());
                                                }
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

                // ── Step 3: Flag reconciliation (throttled to every 5 min) ───
                reconcileFlags(folder, state, accountId);

                // ── Step 4: Deletion detection (in the recent window) ────────
                detectDeletions(folder, state, accountId);

                // ── Step 5: Update folder counts ─────────────────────────────
                state.setTotalCount(folder.getMessageCount());
                state.setUnreadCount(folder.getUnreadMessageCount());
                state.setLastSyncedAt(LocalDateTime.now(ZoneOffset.UTC));
                folderStateRepository.save(state);

                // ── Step 6: Push notifications for new mail ──────────────────
                if (maxUid > lastSeenUid) {
                    pushNewMailNotifications(account, accountId, maxUid);
                }

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

    // ── Flag reconciliation ──────────────────────────────────────────────────

    /**
     * Fetch flags for the most recent FLAG_RECONCILE_WINDOW messages and
     * update the seen flag in cache if it differs from the server.
     * Throttled to once per FLAG_RECONCILE_INTERVAL_MINUTES to avoid
     * hammering IMAP on every 30-second cycle.
     */
    private void reconcileFlags(IMAPFolder folder, FolderState state, UUID accountId) {
        LocalDateTime lastReconcile = state.getLastFlagReconcileAt();
        if (lastReconcile != null &&
                lastReconcile.isAfter(LocalDateTime.now(ZoneOffset.UTC)
                        .minusMinutes(FLAG_RECONCILE_INTERVAL_MINUTES))) {
            return; // throttled — skip this cycle
        }

        try {
            long uidNext = folder.getUIDNext();
            long windowStart = Math.max(1, uidNext - FLAG_RECONCILE_WINDOW);

            Message[] recentMessages = folder.getMessagesByUID(windowStart, UIDFolder.LASTUID);
            if (recentMessages == null || recentMessages.length == 0) return;

            FetchProfile flagProfile = new FetchProfile();
            flagProfile.add(FetchProfile.Item.FLAGS);
            flagProfile.add(UIDFolder.FetchProfileItem.UID);
            folder.fetch(recentMessages, flagProfile);

            int updatedCount = 0;
            for (Message msg : recentMessages) {
                try {
                    long uid = folder.getUID(msg);
                    boolean serverSeen = msg.isSet(Flags.Flag.SEEN);

                    cachedEmailRepository.findByAccountIdAndFolderAndUid(accountId, INBOX, uid)
                            .ifPresent(cached -> {
                                if (cached.isSeen() != serverSeen) {
                                    cached.setSeen(serverSeen);
                                    cachedEmailRepository.save(cached);
                                }
                            });
                    updatedCount++;
                } catch (Exception e) {
                    log.trace("[Sync] Flag reconcile skip: {}", e.getMessage());
                }
            }

            state.setLastFlagReconcileAt(LocalDateTime.now(ZoneOffset.UTC));
            log.debug("[Sync] Flag reconciled {} messages for account {}", updatedCount, accountId);
        } catch (Exception e) {
            log.warn("[Sync] Flag reconciliation failed: {}", e.getMessage());
        }
    }

    // ── Deletion detection ───────────────────────────────────────────────────

    /**
     * Compare cached UIDs in the recent window against UIDs actually present
     * on the server. Evict any cached rows whose UID is no longer on server.
     * Only checks within the FLAG_RECONCILE_WINDOW to bound IMAP work.
     */
    private void detectDeletions(IMAPFolder folder, FolderState state, UUID accountId) {
        try {
            long uidNext = folder.getUIDNext();
            long windowStart = Math.max(1, uidNext - FLAG_RECONCILE_WINDOW);

            // Get all UIDs in the window from the server
            Message[] serverMessages = folder.getMessagesByUID(windowStart, UIDFolder.LASTUID);
            if (serverMessages == null) serverMessages = new Message[0];
            FetchProfile uidProfile = new FetchProfile();
            uidProfile.add(UIDFolder.FetchProfileItem.UID);
            folder.fetch(serverMessages, uidProfile);

            Set<Long> serverUids = new HashSet<>();
            for (Message msg : serverMessages) {
                try { serverUids.add(folder.getUID(msg)); } catch (Exception ignored) {}
            }

            // Load cached UIDs in the same window
            List<Long> cachedUids = cachedEmailRepository
                    .findUidsByAccountIdAndFolderInRange(accountId, INBOX, windowStart, uidNext);

            int deletedCount = 0;
            for (Long cachedUid : cachedUids) {
                if (!serverUids.contains(cachedUid)) {
                    cachedEmailRepository.deleteByAccountIdAndFolderAndUid(accountId, INBOX, cachedUid);
                    deletedCount++;
                }
            }

            if (deletedCount > 0) {
                log.info("[Sync] Evicted {} deleted emails from cache for account {}", deletedCount, accountId);
            }
        } catch (Exception e) {
            log.warn("[Sync] Deletion detection failed: {}", e.getMessage());
        }
    }

    // ── Notification helper ──────────────────────────────────────────────────

    private void pushNewMailNotifications(EmailAccount account, UUID accountId, long currentMaxUid) {
        AccountSyncState syncState = syncStateRepository.findById(accountId)
                .orElseGet(() -> AccountSyncState.builder().accountId(accountId).build());

        long lastNotified = syncState.getLastNotifiedUid();
        if (currentMaxUid <= lastNotified) return;

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

        // From
        String fromAddress = "", fromName = "";
        if (msg.getFrom() != null && msg.getFrom().length > 0) {
            Address from = msg.getFrom()[0];
            if (from instanceof InternetAddress ia) {
                fromAddress = ia.getAddress()  != null ? ia.getAddress()  : "";
                fromName    = ia.getPersonal() != null ? ia.getPersonal() : fromAddress;
            }
        }

        // To recipients
        String toAddresses = extractAddresses(msg.getRecipients(Message.RecipientType.TO));

        // CC recipients
        String ccAddresses = extractAddresses(msg.getRecipients(Message.RecipientType.CC));

        boolean seen          = msg.isSet(Flags.Flag.SEEN);
        boolean hasAttachment = hasAttachmentHeader(msg);
        java.util.Date received = msg.getReceivedDate();
        LocalDateTime receivedAt = received != null
                ? received.toInstant().atZone(ZoneOffset.UTC).toLocalDateTime()
                : LocalDateTime.now(ZoneOffset.UTC);

        String snippet = extractSnippet(msg);

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
                .toAddresses(toAddresses)
                .ccAddresses(ccAddresses)
                .snippet(snippet)
                .receivedAt(receivedAt)
                .seen(seen)
                .hasAttachment(hasAttachment)
                .bodyLoaded(false)
                .build();
    }

    /**
     * Converts an array of Address objects to a semicolon-separated string
     * in the format "Display Name <email@example.com>".
     * Returns null if the array is null or empty.
     */
    private String extractAddresses(Address[] addresses) {
        if (addresses == null || addresses.length == 0) return null;
        return Arrays.stream(addresses)
                .filter(a -> a instanceof InternetAddress)
                .map(a -> {
                    InternetAddress ia = (InternetAddress) a;
                    String personal = ia.getPersonal();
                    String email    = ia.getAddress() != null ? ia.getAddress() : "";
                    return (personal != null && !personal.isBlank())
                            ? personal + " <" + email + ">"
                            : email;
                })
                .collect(Collectors.joining(";"));
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
