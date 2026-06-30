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
    private static final int INITIAL_SYNC_COUNT  = 500;
    private static final int SNIPPET_MAX          = 200;
    private static final int FLAG_RECONCILE_WINDOW = 200;
    private static final int FLAG_RECONCILE_INTERVAL_MINUTES = 5;

    /** Folders synced in background for Gmail accounts (skip virtual All Mail / Starred / Important). */
    private static final List<String> GMAIL_FOLDERS = List.of(
            "INBOX", "[Gmail]/Sent Mail", "[Gmail]/Drafts", "[Gmail]/Spam", "[Gmail]/Trash"
    );

    /** Folders synced in background for standard IMAP accounts. */
    private static final List<String> IMAP_FOLDERS = List.of(
            "INBOX", "Sent", "Drafts", "Junk", "Trash"
    );

    private final EmailAccountRepository      accountRepository;
    private final ImapConnectionService       imapConnectionService;
    private final CachedEmailRepository       cachedEmailRepository;
    private final FolderStateRepository       folderStateRepository;
    private final AccountSyncStateRepository  syncStateRepository;
    private final PushNotificationService     pushNotificationService;

    // ── Scheduled entry-point ────────────────────────────────────────────────

    /**
     * Background poll every 5 minutes — syncs ALL standard folders for every account.
     * IdleService covers INBOX in real-time; this handles Sent/Drafts/Spam/Trash
     * and acts as a safety net for any IDLE gaps.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 30_000)
    public void syncAll() {
        List<EmailAccount> accounts = accountRepository.findAllWithUser();
        for (EmailAccount account : accounts) {
            if (!account.isActive()) continue;
            List<String> folders = account.getImapHost().contains("gmail")
                    ? GMAIL_FOLDERS : IMAP_FOLDERS;
            for (String folder : folders) {
                try {
                    syncAccountFolder(account, folder);
                } catch (Exception e) {
                    log.warn("[Sync] {}/{} failed: {}", account.getEmailAddress(), folder, e.getMessage());
                }
            }
            // Mark account-level error state only after all folders attempted
        }
    }

    /**
     * Force an immediate INBOX sync — called from IdleService (virtual thread) and pull-to-refresh.
     * Accepts a UUID so the account is reloaded fresh within a JPA session, avoiding
     * detached-entity / lazy-proxy errors when called from a virtual thread context.
     */
    @Transactional
    public void syncAccountNow(UUID accountId) {
        EmailAccount account = accountRepository.findById(accountId).orElse(null);
        if (account == null || !account.isActive()) return;
        try {
            syncAccountInbox(account);
        } catch (Exception e) {
            log.warn("[Sync] Manual sync failed for {}: {}", account.getEmailAddress(), e.getMessage());
            markError(account.getId(), e.getMessage());
        }
    }

    /** Force an immediate sync for a specific folder — used by pull-to-refresh and on-demand calls. */
    @Transactional
    public void syncAccountNow(UUID accountId, String folderName) {
        EmailAccount account = accountRepository.findById(accountId).orElse(null);
        if (account == null || !account.isActive()) return;
        try {
            syncAccountFolder(account, folderName);
        } catch (Exception e) {
            log.warn("[Sync] Manual sync failed for {}/{}: {}", account.getEmailAddress(), folderName, e.getMessage());
            markError(account.getId(), e.getMessage());
        }
    }

    /** Sync a specific folder — shared by scheduled sync and on-demand requests. */
    public void syncAccountFolder(EmailAccount account, String folderName) throws MessagingException {
        syncAccountInbox(account, folderName);
    }

    // ── Core sync logic ──────────────────────────────────────────────────────

    @Transactional
    public void syncAccountInbox(EmailAccount account) throws MessagingException {
        syncAccountInbox(account, INBOX);
    }

    /**
     * Core sync for any folder. Parameterised so all folders share the same
     * incremental-fetch / flag-reconcile / deletion-detect pipeline.
     * Push notifications are only sent for INBOX.
     */
    @Transactional
    public void syncAccountInbox(EmailAccount account, String folderName) throws MessagingException {
        UUID accountId = account.getId();
        if (folderName.equals(INBOX)) markSyncing(accountId);

        Store store = imapConnectionService.acquireStore(account);
        try {
            IMAPFolder imapFolder = (IMAPFolder) store.getFolder(folderName);
            if (!imapFolder.exists()) {
                log.debug("[Sync] Folder '{}' does not exist on {}", folderName, account.getEmailAddress());
                return;
            }
            imapFolder.open(Folder.READ_ONLY);
            try {
                long serverUidValidity = imapFolder.getUIDValidity();
                String displayName = folderName.contains("/")
                        ? folderName.substring(folderName.lastIndexOf('/') + 1) : folderName;
                FolderState state = folderStateRepository
                        .findByAccountIdAndFullName(accountId, folderName)
                        .orElseGet(() -> FolderState.builder()
                                .accountId(accountId)
                                .fullName(folderName)
                                .displayName(displayName)
                                .build());

                // ── Step 1: UIDVALIDITY check ────────────────────────────────
                if (state.getUidValidity() != null && state.getUidValidity() != serverUidValidity) {
                    log.warn("[Sync] UIDVALIDITY changed for {}/{} — wiping cache", account.getEmailAddress(), folderName);
                    cachedEmailRepository.deleteByAccountIdAndFolder(accountId, folderName);
                    state.setLastSeenUid(0L);
                    state.setLastFlagReconcileAt(null);
                }
                state.setUidValidity(serverUidValidity);

                // ── Step 2: Incremental fetch (new messages only) ────────────
                long lastSeenUid = state.getLastSeenUid();
                long fetchFrom = (lastSeenUid == 0)
                        ? Math.max(1, imapFolder.getUIDNext() - INITIAL_SYNC_COUNT)
                        : lastSeenUid + 1;

                Message[] newMessages = imapFolder.getMessagesByUID(fetchFrom, UIDFolder.LASTUID);
                if (newMessages == null) newMessages = new Message[0];

                long maxUid = lastSeenUid;
                if (newMessages.length > 0) {
                    FetchProfile profile = new FetchProfile();
                    profile.add(FetchProfile.Item.ENVELOPE);
                    profile.add(FetchProfile.Item.FLAGS);
                    profile.add(UIDFolder.FetchProfileItem.UID);
                    profile.add(FetchProfile.Item.CONTENT_INFO);
                    imapFolder.fetch(newMessages, profile);

                    for (Message msg : newMessages) {
                        try {
                            long uid = imapFolder.getUID(msg);
                            if (uid <= lastSeenUid) continue;

                            CachedEmail cached = buildCachedEmail(msg, imapFolder, accountId, uid, folderName);
                            cachedEmailRepository.findByAccountIdAndFolderAndUid(accountId, folderName, uid)
                                    .ifPresentOrElse(
                                            existing -> {
                                                existing.setSeen(cached.isSeen());
                                                if (existing.getToAddresses() == null)
                                                    existing.setToAddresses(cached.getToAddresses());
                                                if (existing.getCcAddresses() == null)
                                                    existing.setCcAddresses(cached.getCcAddresses());
                                                cachedEmailRepository.save(existing);
                                            },
                                            () -> cachedEmailRepository.save(cached)
                                    );
                            if (uid > maxUid) maxUid = uid;
                        } catch (Exception e) {
                            log.warn("[Sync] Failed to process message in {}/{}: {}", account.getEmailAddress(), folderName, e.getMessage());
                        }
                    }
                    state.setLastSeenUid(maxUid);
                }

                // ── Step 3: Flag reconciliation (throttled) ──────────────────
                reconcileFlags(imapFolder, state, accountId, folderName);

                // ── Step 4: Deletion detection ───────────────────────────────
                detectDeletions(imapFolder, state, accountId, folderName);

                // ── Step 5: Update folder counts ─────────────────────────────
                state.setTotalCount(imapFolder.getMessageCount());
                state.setUnreadCount(imapFolder.getUnreadMessageCount());
                state.setLastSyncedAt(LocalDateTime.now(ZoneOffset.UTC));
                folderStateRepository.save(state);

                // ── Step 6: Push notifications — INBOX only ──────────────────
                if (folderName.equals(INBOX) && maxUid > lastSeenUid) {
                    pushNewMailNotifications(account, accountId, maxUid);
                }

                if (folderName.equals(INBOX)) markIdle(accountId);
                log.debug("[Sync] {}/{} synced — {} total, {} unread, lastUid={}",
                        account.getEmailAddress(), folderName,
                        state.getTotalCount(), state.getUnreadCount(), state.getLastSeenUid());

            } finally {
                if (imapFolder.isOpen()) imapFolder.close(false);
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
    private void reconcileFlags(IMAPFolder folder, FolderState state, UUID accountId, String folderName) {
        LocalDateTime lastReconcile = state.getLastFlagReconcileAt();
        if (lastReconcile != null &&
                lastReconcile.isAfter(LocalDateTime.now(ZoneOffset.UTC)
                        .minusMinutes(FLAG_RECONCILE_INTERVAL_MINUTES))) {
            return;
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
                    cachedEmailRepository.findByAccountIdAndFolderAndUid(accountId, folderName, uid)
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
            log.debug("[Sync] Flag reconciled {} messages in {}/{}", updatedCount, accountId, folderName);
        } catch (Exception e) {
            log.warn("[Sync] Flag reconciliation failed for {}: {}", folderName, e.getMessage());
        }
    }

    // ── Deletion detection ───────────────────────────────────────────────────

    /**
     * Compare cached UIDs in the recent window against UIDs actually present
     * on the server. Evict any cached rows whose UID is no longer on server.
     * Only checks within the FLAG_RECONCILE_WINDOW to bound IMAP work.
     */
    private void detectDeletions(IMAPFolder folder, FolderState state, UUID accountId, String folderName) {
        try {
            long uidNext = folder.getUIDNext();
            long windowStart = Math.max(1, uidNext - FLAG_RECONCILE_WINDOW);

            Message[] serverMessages = folder.getMessagesByUID(windowStart, UIDFolder.LASTUID);
            if (serverMessages == null) serverMessages = new Message[0];
            FetchProfile uidProfile = new FetchProfile();
            uidProfile.add(UIDFolder.FetchProfileItem.UID);
            folder.fetch(serverMessages, uidProfile);

            Set<Long> serverUids = new HashSet<>();
            for (Message msg : serverMessages) {
                try { serverUids.add(folder.getUID(msg)); } catch (Exception ignored) {}
            }

            List<Long> cachedUids = cachedEmailRepository
                    .findUidsByAccountIdAndFolderInRange(accountId, folderName, windowStart, uidNext);

            int deletedCount = 0;
            for (Long cachedUid : cachedUids) {
                if (!serverUids.contains(cachedUid)) {
                    cachedEmailRepository.deleteByAccountIdAndFolderAndUid(accountId, folderName, cachedUid);
                    deletedCount++;
                }
            }
            if (deletedCount > 0) {
                log.info("[Sync] Evicted {} deleted emails from {}/{}", deletedCount, accountId, folderName);
            }
        } catch (Exception e) {
            log.warn("[Sync] Deletion detection failed for {}: {}", folderName, e.getMessage());
        }
    }

    // ── Notification helper ──────────────────────────────────────────────────

    private void pushNewMailNotifications(EmailAccount account, UUID accountId, long currentMaxUid) {
        AccountSyncState syncState = syncStateRepository.findById(accountId)
                .orElseGet(() -> AccountSyncState.builder().accountId(accountId).build());

        long lastNotified = syncState.getLastNotifiedUid();
        if (currentMaxUid <= lastNotified) return;

        cachedEmailRepository.findNewUnseenEmailsSinceUid(accountId, INBOX, lastNotified, 10)
                .forEach(email -> {
                    String fcmToken = account.getUser() != null ? account.getUser().getFcmToken() : null;
                    if (fcmToken != null && !fcmToken.isBlank()) {
                        pushNotificationService.sendNewEmailNotification(
                                fcmToken,
                                email.getFromName(),
                                email.getSubject(),
                                email.getSnippet(),
                                accountId.toString(),
                                INBOX,
                                email.getUid()
                        );
                    }
                });

        syncState.setLastNotifiedUid(currentMaxUid);
        syncState.setLastFullSyncAt(LocalDateTime.now(ZoneOffset.UTC));
        syncStateRepository.save(syncState);
    }

    // ── Builder helpers ──────────────────────────────────────────────────────

    private CachedEmail buildCachedEmail(Message msg, IMAPFolder folder, UUID accountId, long uid, String folderName)
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
                .folder(folderName)
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
