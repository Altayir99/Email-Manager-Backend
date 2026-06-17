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
import com.emailmanager.backend.user.User;
import com.sun.mail.imap.IMAPFolder;
import jakarta.mail.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SyncService — Phase 2 & 3 logic.
 *
 * Strategy: mock all IMAP + DB dependencies; verify only the SyncService
 * decision-making logic (what it saves, what it deletes, what FCM it sends).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SyncServiceTest {

    @Mock EmailAccountRepository accountRepository;
    @Mock ImapConnectionService  imapConnectionService;
    @Mock CachedEmailRepository  cachedEmailRepo;
    @Mock FolderStateRepository  folderStateRepo;
    @Mock AccountSyncStateRepository syncStateRepo;
    @Mock PushNotificationService    pushService;

    @Mock Store     store;
    @Mock IMAPFolder folder;

    @InjectMocks SyncService syncService;

    private EmailAccount account;
    private User user;
    private UUID accountId;

    @BeforeEach
    void setUp() throws MessagingException {
        accountId = UUID.randomUUID();
        user = new User();
        user.setFcmToken("fcm-test-token");

        account = new EmailAccount();
        account.setId(accountId);
        account.setEmailAddress("test@example.com");
        account.setActive(true);
        account.setUser(user);

        // Default: push notification path needs a non-null page (empty = no push fired)
        when(cachedEmailRepo.findByAccountIdAndFolderOrderByReceivedAtDesc(
                eq(accountId), eq("INBOX"), any()))
                .thenReturn(new PageImpl<>(List.of()));

        // Common IMAP stubs
        when(imapConnectionService.acquireStore(account)).thenReturn(store);
        when(store.getFolder("INBOX")).thenReturn(folder);
        doNothing().when(folder).open(Folder.READ_ONLY);
        when(folder.isOpen()).thenReturn(true);
        doNothing().when(folder).close(false);
        when(folder.getUIDValidity()).thenReturn(12345L);
        when(folder.getUIDNext()).thenReturn(100L);
        when(folder.getMessageCount()).thenReturn(10);
        when(folder.getUnreadMessageCount()).thenReturn(2);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private FolderState existingFolderState(long lastSeenUid) {
        return FolderState.builder()
                .accountId(accountId)
                .fullName("INBOX")
                .displayName("Inbox")
                .uidValidity(12345L)
                .lastSeenUid(lastSeenUid)
                .build();
    }

    private CachedEmail cachedEmail(long uid, boolean seen) {
        return CachedEmail.builder()
                .accountId(accountId)
                .folder("INBOX")
                .uid(uid)
                .subject("Subject " + uid)
                .fromName("Sender")
                .fromAddress("sender@example.com")
                .snippet("snippet")
                .receivedAt(LocalDateTime.now())
                .seen(seen)
                .build();
    }

    private Message mockMessage(IMAPFolder f, long uid, boolean seen) throws MessagingException {
        Message msg = mock(Message.class);
        when(f.getUID(msg)).thenReturn(uid);
        when(msg.isSet(Flags.Flag.SEEN)).thenReturn(seen);
        when(msg.getSubject()).thenReturn("Subject " + uid);
        when(msg.getFrom()).thenReturn(null);
        when(msg.getReceivedDate()).thenReturn(new Date());
        when(msg.getRecipients(Message.RecipientType.TO)).thenReturn(null);
        when(msg.getRecipients(Message.RecipientType.CC)).thenReturn(null);
        when(msg.getContentType()).thenReturn("text/plain");
        try { when(msg.getContent()).thenReturn("body text"); } catch (Exception ignored) {}
        try { when(msg.getHeader("Message-ID")).thenReturn(new String[]{"<id-" + uid + ">"}); } catch (Exception ignored) {}
        doNothing().when(f).fetch(any(), any());
        return msg;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1. UIDVALIDITY change → wipe cache and reset last_seen_uid
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("UIDVALIDITY")
    class UidValidity {

        @Test
        @DisplayName("wipes cache when UIDVALIDITY changes")
        void wipesCache_whenUidValidityChanges() throws MessagingException {
            // Server now returns validity 99999, but cache says 12345
            when(folder.getUIDValidity()).thenReturn(99999L);
            when(folder.getMessagesByUID(anyLong(), anyLong())).thenReturn(new Message[0]);

            FolderState stale = existingFolderState(50L);
            stale.setUidValidity(12345L); // old validity
            when(folderStateRepo.findByAccountIdAndFullName(accountId, "INBOX"))
                    .thenReturn(Optional.of(stale));
            when(syncStateRepo.findById(accountId)).thenReturn(Optional.empty());

            syncService.syncAccountFolder(account, "INBOX");

            verify(cachedEmailRepo).deleteByAccountIdAndFolder(accountId, "INBOX");
        }

        @Test
        @DisplayName("does NOT wipe cache when UIDVALIDITY is unchanged")
        void doesNotWipeCache_whenUidValidityUnchanged() throws MessagingException {
            when(folder.getMessagesByUID(anyLong(), anyLong())).thenReturn(new Message[0]);
            when(folderStateRepo.findByAccountIdAndFullName(accountId, "INBOX"))
                    .thenReturn(Optional.of(existingFolderState(50L)));
            when(syncStateRepo.findById(accountId)).thenReturn(Optional.empty());

            syncService.syncAccountFolder(account, "INBOX");

            verify(cachedEmailRepo, never()).deleteByAccountIdAndFolder(any(), any());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. Incremental fetch — only UIDs > last_seen_uid are fetched
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Incremental fetch")
    class IncrementalFetch {

        @Test
        @DisplayName("saves new message when UID is above last_seen_uid")
        void savesNewMessage_whenUidAboveWatermark() throws MessagingException {
            FolderState state = existingFolderState(50L);
            when(folderStateRepo.findByAccountIdAndFullName(accountId, "INBOX"))
                    .thenReturn(Optional.of(state));

            Message newMsg = mockMessage(folder, 51L, false);
            when(folder.getMessagesByUID(51L, UIDFolder.LASTUID))
                    .thenReturn(new Message[]{newMsg});
            when(cachedEmailRepo.findByAccountIdAndFolderAndUid(accountId, "INBOX", 51L))
                    .thenReturn(Optional.empty());
            when(syncStateRepo.findById(accountId)).thenReturn(Optional.empty());

            syncService.syncAccountFolder(account, "INBOX");

            ArgumentCaptor<CachedEmail> captor = ArgumentCaptor.forClass(CachedEmail.class);
            verify(cachedEmailRepo, atLeastOnce()).save(captor.capture());

            List<CachedEmail> saved = captor.getAllValues().stream()
                    .filter(e -> e.getUid() == 51L).toList();
            assertThat(saved).hasSize(1);
            assertThat(saved.get(0).getFolder()).isEqualTo("INBOX");
            assertThat(saved.get(0).getAccountId()).isEqualTo(accountId);
        }

        @Test
        @DisplayName("updates last_seen_uid in FolderState after new messages")
        void updatesLastSeenUid() throws MessagingException {
            FolderState state = existingFolderState(50L);
            when(folderStateRepo.findByAccountIdAndFullName(accountId, "INBOX"))
                    .thenReturn(Optional.of(state));

            Message msg51 = mockMessage(folder, 51L, false);
            Message msg52 = mockMessage(folder, 52L, false);
            when(folder.getMessagesByUID(51L, UIDFolder.LASTUID))
                    .thenReturn(new Message[]{msg51, msg52});
            when(cachedEmailRepo.findByAccountIdAndFolderAndUid(eq(accountId), eq("INBOX"), anyLong()))
                    .thenReturn(Optional.empty());
            when(syncStateRepo.findById(accountId)).thenReturn(Optional.empty());

            syncService.syncAccountFolder(account, "INBOX");

            ArgumentCaptor<FolderState> fsCaptor = ArgumentCaptor.forClass(FolderState.class);
            verify(folderStateRepo).save(fsCaptor.capture());
            assertThat(fsCaptor.getValue().getLastSeenUid()).isEqualTo(52L);
        }

        @Test
        @DisplayName("skips messages with UID <= last_seen_uid (server overlap safety)")
        void skipsAlreadySeenUids() throws MessagingException {
            FolderState state = existingFolderState(50L);
            when(folderStateRepo.findByAccountIdAndFullName(accountId, "INBOX"))
                    .thenReturn(Optional.of(state));

            // Server returns uid=50 (already seen) and uid=51 (new)
            Message oldMsg = mockMessage(folder, 50L, true);
            Message newMsg = mockMessage(folder, 51L, false);
            when(folder.getMessagesByUID(51L, UIDFolder.LASTUID))
                    .thenReturn(new Message[]{oldMsg, newMsg});
            when(cachedEmailRepo.findByAccountIdAndFolderAndUid(accountId, "INBOX", 51L))
                    .thenReturn(Optional.empty());
            when(syncStateRepo.findById(accountId)).thenReturn(Optional.empty());

            syncService.syncAccountFolder(account, "INBOX");

            // Only uid=51 should be saved, NOT uid=50
            verify(cachedEmailRepo, never()).findByAccountIdAndFolderAndUid(accountId, "INBOX", 50L);
        }

        @Test
        @DisplayName("does nothing when no new messages (empty response from server)")
        void doesNothing_whenNoNewMessages() throws MessagingException {
            FolderState state = existingFolderState(50L);
            when(folderStateRepo.findByAccountIdAndFullName(accountId, "INBOX"))
                    .thenReturn(Optional.of(state));
            when(folder.getMessagesByUID(51L, UIDFolder.LASTUID))
                    .thenReturn(new Message[0]);
            when(syncStateRepo.findById(accountId)).thenReturn(Optional.empty());

            syncService.syncAccountFolder(account, "INBOX");

            verify(cachedEmailRepo, never()).save(any(CachedEmail.class));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 3. Upsert behaviour — existing UID in cache → flag update only
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Upsert on existing UID")
    class Upsert {

        @Test
        @DisplayName("updates seen flag when UID already exists in cache")
        void updatesSeen_whenUidAlreadyInCache() throws MessagingException {
            FolderState state = existingFolderState(50L);
            when(folderStateRepo.findByAccountIdAndFullName(accountId, "INBOX"))
                    .thenReturn(Optional.of(state));

            Message msg = mockMessage(folder, 51L, true); // now marked seen on server
            when(folder.getMessagesByUID(51L, UIDFolder.LASTUID))
                    .thenReturn(new Message[]{msg});

            CachedEmail existing = cachedEmail(51L, false); // we have it as unseen
            when(cachedEmailRepo.findByAccountIdAndFolderAndUid(accountId, "INBOX", 51L))
                    .thenReturn(Optional.of(existing));
            when(syncStateRepo.findById(accountId)).thenReturn(Optional.empty());

            syncService.syncAccountFolder(account, "INBOX");

            assertThat(existing.isSeen()).isTrue();
            verify(cachedEmailRepo, atLeastOnce()).save(existing);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 4. FCM push notifications
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("FCM Push Notifications")
    class FcmPush {

        @Test
        @DisplayName("sends push when new UID is above last_notified_uid")
        void sendsPush_whenNewUidAboveWatermark() throws MessagingException {
            FolderState state = existingFolderState(50L);
            when(folderStateRepo.findByAccountIdAndFullName(accountId, "INBOX"))
                    .thenReturn(Optional.of(state));

            Message msg = mockMessage(folder, 51L, false);
            when(folder.getMessagesByUID(51L, UIDFolder.LASTUID))
                    .thenReturn(new Message[]{msg});
            when(cachedEmailRepo.findByAccountIdAndFolderAndUid(accountId, "INBOX", 51L))
                    .thenReturn(Optional.empty());

            AccountSyncState syncState = AccountSyncState.builder()
                    .accountId(accountId).lastNotifiedUid(40L).build();
            when(syncStateRepo.findById(accountId)).thenReturn(Optional.of(syncState));

            CachedEmail newEmail = cachedEmail(51L, false);
            newEmail.setFromName("Alice");
            newEmail.setSubject("Hello");
            newEmail.setSnippet("Hey there");
            org.springframework.data.domain.Page<CachedEmail> page =
                    new org.springframework.data.domain.PageImpl<>(List.of(newEmail));
            when(cachedEmailRepo.findByAccountIdAndFolderOrderByReceivedAtDesc(
                    eq(accountId), eq("INBOX"), any())).thenReturn(page);

            syncService.syncAccountFolder(account, "INBOX");

            verify(pushService).sendNewEmailNotification(
                    eq("fcm-test-token"), eq("Alice"), eq("Hello"), eq("Hey there"), anyString());
        }

        @Test
        @DisplayName("does NOT send push when UID is below last_notified_uid (dedup)")
        void doesNotSendPush_whenUidBelowWatermark() throws MessagingException {
            FolderState state = existingFolderState(50L);
            when(folderStateRepo.findByAccountIdAndFullName(accountId, "INBOX"))
                    .thenReturn(Optional.of(state));
            when(folder.getMessagesByUID(51L, UIDFolder.LASTUID))
                    .thenReturn(new Message[0]);

            // last_notified_uid is already at 50 — same as max, no new mail
            AccountSyncState syncState = AccountSyncState.builder()
                    .accountId(accountId).lastNotifiedUid(50L).build();
            when(syncStateRepo.findById(accountId)).thenReturn(Optional.of(syncState));

            syncService.syncAccountFolder(account, "INBOX");

            verify(pushService, never()).sendNewEmailNotification(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("does NOT send push when account has no FCM token")
        void doesNotSendPush_whenNoFcmToken() throws MessagingException {
            user.setFcmToken(null); // no token registered

            FolderState state = existingFolderState(50L);
            when(folderStateRepo.findByAccountIdAndFullName(accountId, "INBOX"))
                    .thenReturn(Optional.of(state));

            Message msg = mockMessage(folder, 51L, false);
            when(folder.getMessagesByUID(51L, UIDFolder.LASTUID))
                    .thenReturn(new Message[]{msg});
            when(cachedEmailRepo.findByAccountIdAndFolderAndUid(accountId, "INBOX", 51L))
                    .thenReturn(Optional.empty());

            AccountSyncState syncState = AccountSyncState.builder()
                    .accountId(accountId).lastNotifiedUid(40L).build();
            when(syncStateRepo.findById(accountId)).thenReturn(Optional.of(syncState));

            CachedEmail newEmail = cachedEmail(51L, false);
            org.springframework.data.domain.Page<CachedEmail> page =
                    new org.springframework.data.domain.PageImpl<>(List.of(newEmail));
            when(cachedEmailRepo.findByAccountIdAndFolderOrderByReceivedAtDesc(
                    eq(accountId), eq("INBOX"), any())).thenReturn(page);

            syncService.syncAccountFolder(account, "INBOX");

            verify(pushService, never()).sendNewEmailNotification(any(), any(), any(), any(), any());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 5. syncAll() uses findAllWithUser() — the bug fix
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("syncAll")
    class SyncAll {

        @Test
        @DisplayName("calls findAllWithUser() NOT findAll()")
        void usesFindAllWithUser() {
            when(accountRepository.findAllWithUser()).thenReturn(List.of());
            syncService.syncAll();
            verify(accountRepository).findAllWithUser();
            verify(accountRepository, never()).findAll();
        }

        @Test
        @DisplayName("skips inactive accounts")
        void skipsInactiveAccounts() throws MessagingException {
            account.setActive(false);
            when(accountRepository.findAllWithUser()).thenReturn(List.of(account));

            syncService.syncAll();

            verify(imapConnectionService, never()).acquireStore(any());
        }

        @Test
        @DisplayName("continues syncing other accounts when one fails")
        void continuesOnFailure() throws MessagingException {
            EmailAccount goodAccount = new EmailAccount();
            goodAccount.setId(UUID.randomUUID());
            goodAccount.setEmailAddress("good@example.com");
            goodAccount.setActive(true);
            goodAccount.setUser(user);

            account.setActive(true); // bad account — will throw
            when(imapConnectionService.acquireStore(account))
                    .thenThrow(new RuntimeException("connection refused"));

            when(accountRepository.findAllWithUser()).thenReturn(List.of(account, goodAccount));
            when(imapConnectionService.acquireStore(goodAccount)).thenReturn(store);
            when(store.getFolder("INBOX")).thenReturn(folder);
            when(folderStateRepo.findByAccountIdAndFullName(goodAccount.getId(), "INBOX"))
                    .thenReturn(Optional.empty());
            when(folder.getMessagesByUID(anyLong(), anyLong())).thenReturn(new Message[0]);
            when(syncStateRepo.findById(any())).thenReturn(Optional.empty());

            // Should not throw — bad account is caught, good account still runs
            assertThatCode(() -> syncService.syncAll()).doesNotThrowAnyException();
            verify(imapConnectionService).acquireStore(goodAccount);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 6. First sync (last_seen_uid == 0) seeds the initial INITIAL_SYNC_COUNT
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("First sync (cold start)")
    class FirstSync {

        @Test
        @DisplayName("fetches from (uidNext - INITIAL_SYNC_COUNT) on first sync")
        void fetchesFromCorrectStartUid_onFirstSync() throws MessagingException {
            // No cached folder state — fresh account
            when(folderStateRepo.findByAccountIdAndFullName(accountId, "INBOX"))
                    .thenReturn(Optional.empty());
            when(folder.getUIDNext()).thenReturn(150L); // server UID next = 150
            // Expect fetch from 150 - 50 = 100
            when(folder.getMessagesByUID(100L, UIDFolder.LASTUID))
                    .thenReturn(new Message[0]);
            when(syncStateRepo.findById(accountId)).thenReturn(Optional.empty());

            syncService.syncAccountFolder(account, "INBOX");

            verify(folder).getMessagesByUID(100L, UIDFolder.LASTUID);
        }

        @Test
        @DisplayName("creates new FolderState when none exists")
        void createsFolderState_whenNoneExists() throws MessagingException {
            when(folderStateRepo.findByAccountIdAndFullName(accountId, "INBOX"))
                    .thenReturn(Optional.empty());
            when(folder.getMessagesByUID(anyLong(), anyLong())).thenReturn(new Message[0]);
            when(syncStateRepo.findById(accountId)).thenReturn(Optional.empty());

            syncService.syncAccountFolder(account, "INBOX");

            ArgumentCaptor<FolderState> captor = ArgumentCaptor.forClass(FolderState.class);
            verify(folderStateRepo).save(captor.capture());
            assertThat(captor.getValue().getAccountId()).isEqualTo(accountId);
            assertThat(captor.getValue().getFullName()).isEqualTo("INBOX");
            assertThat(captor.getValue().getUidValidity()).isEqualTo(12345L);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 7. IMAP lock is always released (even on exception)
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("IMAP resource cleanup")
    class ImapCleanup {

        @Test
        @DisplayName("releases IMAP store lock even when folder fetch throws")
        void releasesLock_evenOnException() throws MessagingException {
            when(folderStateRepo.findByAccountIdAndFullName(accountId, "INBOX"))
                    .thenReturn(Optional.of(existingFolderState(10L)));
            when(folder.getMessagesByUID(anyLong(), anyLong()))
                    .thenThrow(new MessagingException("IMAP timeout"));

            assertThatThrownBy(() -> syncService.syncAccountFolder(account, "INBOX"))
                    .isInstanceOf(MessagingException.class);

            verify(imapConnectionService).releaseStore(accountId);
        }

        @Test
        @DisplayName("closes folder even when processing throws")
        void closesFolder_evenOnException() throws MessagingException {
            when(folderStateRepo.findByAccountIdAndFullName(accountId, "INBOX"))
                    .thenReturn(Optional.of(existingFolderState(10L)));
            when(folder.getMessagesByUID(anyLong(), anyLong()))
                    .thenThrow(new MessagingException("IMAP timeout"));

            assertThatThrownBy(() -> syncService.syncAccountFolder(account, "INBOX"))
                    .isInstanceOf(MessagingException.class);

            verify(folder).close(false);
        }
    }
}
