package com.emailmanager.backend.cache;

import com.emailmanager.backend.accounts.entity.EmailAccount;
import com.emailmanager.backend.accounts.repository.EmailAccountRepository;
import com.emailmanager.backend.cache.entity.CachedEmail;
import com.emailmanager.backend.cache.entity.FolderState;
import com.emailmanager.backend.cache.repository.CachedEmailRepository;
import com.emailmanager.backend.cache.repository.FolderStateRepository;
import com.emailmanager.backend.user.User;
import com.emailmanager.backend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for cache repositories — run against H2 in-memory.
 * Validates the JPQL queries, pagination, and bulk operations.
 */
@DataJpaTest
@ActiveProfiles("test")
class CacheRepositoryIntegrationTest {

    @Autowired CachedEmailRepository emailRepo;
    @Autowired FolderStateRepository  folderRepo;
    @Autowired EmailAccountRepository accountRepo;
    @Autowired UserRepository         userRepo;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        // Create a real User + EmailAccount so FK constraints are satisfied
        User user = new User();
        user.setUsername("testuser_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPassword("hashed");
        userRepo.save(user);

        EmailAccount account = new EmailAccount();
        account.setUser(user);
        account.setEmailAddress("test@example.com");
        account.setDisplayName("Test");
        account.setColor("#6366F1");
        account.setImapHost("imap.gmail.com");
        account.setImapPort(993);
        account.setSmtpHost("smtp.gmail.com");
        account.setSmtpPort(587);
        account.setUsername("test@example.com");
        account.setEncryptedPassword("encrypted");
        account.setActive(true);
        accountRepo.save(account);

        accountId = account.getId();
    }

    private CachedEmail email(long uid, boolean seen, LocalDateTime receivedAt) {
        return CachedEmail.builder()
                .accountId(accountId)
                .folder("INBOX")
                .uid(uid)
                .subject("Subject " + uid)
                .fromAddress("sender@example.com")
                .fromName("Sender")
                .snippet("snippet " + uid)
                .receivedAt(receivedAt)
                .seen(seen)
                .hasAttachment(false)
                .bodyLoaded(false)
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Paginated list query
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("findByAccountIdAndFolderOrderByReceivedAtDesc")
    class PaginatedList {

        @Test
        @DisplayName("returns emails sorted newest-first")
        void returnsNewestFirst() {
            emailRepo.save(email(1L, false, LocalDateTime.now().minusHours(2)));
            emailRepo.save(email(2L, false, LocalDateTime.now().minusHours(1)));
            emailRepo.save(email(3L, false, LocalDateTime.now()));

            Page<CachedEmail> page = emailRepo
                    .findByAccountIdAndFolderOrderByReceivedAtDesc(accountId, "INBOX", PageRequest.of(0, 10));

            assertThat(page.getContent()).extracting(CachedEmail::getUid)
                    .containsExactly(3L, 2L, 1L);
        }

        @Test
        @DisplayName("paginates correctly")
        void paginatesCorrectly() {
            for (int i = 1; i <= 5; i++) {
                emailRepo.save(email(i, false, LocalDateTime.now().minusMinutes(6 - i)));
            }

            Page<CachedEmail> page0 = emailRepo
                    .findByAccountIdAndFolderOrderByReceivedAtDesc(accountId, "INBOX", PageRequest.of(0, 2));
            Page<CachedEmail> page1 = emailRepo
                    .findByAccountIdAndFolderOrderByReceivedAtDesc(accountId, "INBOX", PageRequest.of(1, 2));

            assertThat(page0.getContent()).hasSize(2);
            assertThat(page0.hasNext()).isTrue();
            assertThat(page1.getContent()).hasSize(2);
        }

        @Test
        @DisplayName("does not return emails from other accounts")
        void isolatesByAccount() {
            emailRepo.save(email(1L, false, LocalDateTime.now()));
            UUID otherId = UUID.randomUUID();

            Page<CachedEmail> page = emailRepo
                    .findByAccountIdAndFolderOrderByReceivedAtDesc(otherId, "INBOX", PageRequest.of(0, 10));

            assertThat(page.getContent()).isEmpty();
        }

        @Test
        @DisplayName("does not return emails from other folders")
        void isolatesByFolder() {
            emailRepo.save(email(1L, false, LocalDateTime.now()));

            Page<CachedEmail> page = emailRepo
                    .findByAccountIdAndFolderOrderByReceivedAtDesc(accountId, "[Gmail]/Sent Mail", PageRequest.of(0, 10));

            assertThat(page.getContent()).isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // updateSeen bulk operations
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Flag updates")
    class FlagUpdates {

        @Test
        @DisplayName("updateSeen marks a single email as read")
        void updateSeen_marksRead() {
            emailRepo.save(email(10L, false, LocalDateTime.now()));

            int updated = emailRepo.updateSeen(accountId, "INBOX", 10L, true);
            assertThat(updated).isEqualTo(1);

            CachedEmail result = emailRepo.findByAccountIdAndFolderAndUid(accountId, "INBOX", 10L).orElseThrow();
            assertThat(result.isSeen()).isTrue();
        }

        @Test
        @DisplayName("markSeenBulk marks multiple emails read at once")
        void markSeenBulk() {
            emailRepo.save(email(1L, false, LocalDateTime.now()));
            emailRepo.save(email(2L, false, LocalDateTime.now()));
            emailRepo.save(email(3L, false, LocalDateTime.now()));

            int updated = emailRepo.markSeenBulk(accountId, "INBOX", List.of(1L, 2L));
            assertThat(updated).isEqualTo(2);

            assertThat(emailRepo.findByAccountIdAndFolderAndUid(accountId, "INBOX", 1L).orElseThrow().isSeen()).isTrue();
            assertThat(emailRepo.findByAccountIdAndFolderAndUid(accountId, "INBOX", 2L).orElseThrow().isSeen()).isTrue();
            assertThat(emailRepo.findByAccountIdAndFolderAndUid(accountId, "INBOX", 3L).orElseThrow().isSeen()).isFalse();
        }

        @Test
        @DisplayName("markUnseenBulk marks multiple emails unread at once")
        void markUnseenBulk() {
            emailRepo.save(email(1L, true, LocalDateTime.now()));
            emailRepo.save(email(2L, true, LocalDateTime.now()));

            emailRepo.markUnseenBulk(accountId, "INBOX", List.of(1L, 2L));

            assertThat(emailRepo.findByAccountIdAndFolderAndUid(accountId, "INBOX", 1L).orElseThrow().isSeen()).isFalse();
            assertThat(emailRepo.findByAccountIdAndFolderAndUid(accountId, "INBOX", 2L).orElseThrow().isSeen()).isFalse();
        }

        @Test
        @DisplayName("countByAccountIdAndFolderAndSeenFalse counts only unread")
        void countsUnreadOnly() {
            emailRepo.save(email(1L, false, LocalDateTime.now()));
            emailRepo.save(email(2L, false, LocalDateTime.now()));
            emailRepo.save(email(3L, true,  LocalDateTime.now())); // read

            int count = emailRepo.countByAccountIdAndFolderAndSeenFalse(accountId, "INBOX");
            assertThat(count).isEqualTo(2);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Deletion detection queries
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Deletion detection")
    class DeletionDetection {

        @Test
        @DisplayName("deleteByAccountIdAndFolderAndUidIn removes only specified UIDs")
        void bulkDelete_removesOnlySpecifiedUids() {
            emailRepo.save(email(1L, false, LocalDateTime.now()));
            emailRepo.save(email(2L, false, LocalDateTime.now()));
            emailRepo.save(email(3L, false, LocalDateTime.now()));

            int deleted = emailRepo.deleteByAccountIdAndFolderAndUidIn(accountId, "INBOX", List.of(1L, 3L));
            assertThat(deleted).isEqualTo(2);

            assertThat(emailRepo.findByAccountIdAndFolderAndUid(accountId, "INBOX", 2L)).isPresent();
            assertThat(emailRepo.findByAccountIdAndFolderAndUid(accountId, "INBOX", 1L)).isEmpty();
            assertThat(emailRepo.findByAccountIdAndFolderAndUid(accountId, "INBOX", 3L)).isEmpty();
        }

        @Test
        @DisplayName("findUidsByAccountIdAndFolder returns UIDs sorted desc with limit")
        void findUids_returnsSortedDescWithLimit() {
            for (int uid = 1; uid <= 10; uid++) {
                emailRepo.save(email(uid, false, LocalDateTime.now().minusMinutes(11 - uid)));
            }

            List<Long> uids = emailRepo.findUidsByAccountIdAndFolder(
                    accountId, "INBOX", PageRequest.of(0, 3));

            assertThat(uids).containsExactly(10L, 9L, 8L);
        }

        @Test
        @DisplayName("deleteByAccountIdAndFolder wipes entire folder")
        void deleteFolderWipe() {
            emailRepo.save(email(1L, false, LocalDateTime.now()));
            emailRepo.save(email(2L, false, LocalDateTime.now()));

            emailRepo.deleteByAccountIdAndFolder(accountId, "INBOX");

            Page<CachedEmail> result = emailRepo
                    .findByAccountIdAndFolderOrderByReceivedAtDesc(accountId, "INBOX", PageRequest.of(0, 10));
            assertThat(result.getContent()).isEmpty();
        }

        @Test
        @DisplayName("findMaxUidByAccountIdAndFolder returns highest UID")
        void findMaxUid() {
            emailRepo.save(email(5L, false, LocalDateTime.now()));
            emailRepo.save(email(10L, false, LocalDateTime.now()));
            emailRepo.save(email(3L, false, LocalDateTime.now()));

            Optional<Long> max = emailRepo.findMaxUidByAccountIdAndFolder(accountId, "INBOX");
            assertThat(max).contains(10L);
        }

        @Test
        @DisplayName("findMaxUidByAccountIdAndFolder returns empty when folder is empty")
        void findMaxUid_empty() {
            Optional<Long> max = emailRepo.findMaxUidByAccountIdAndFolder(accountId, "INBOX");
            assertThat(max).isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // FolderState repository
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("FolderState")
    class FolderStateTests {

        @Test
        @DisplayName("saves and retrieves FolderState by accountId + fullName")
        void savesAndRetrieves() {
            FolderState state = FolderState.builder()
                    .accountId(accountId)
                    .fullName("INBOX")
                    .displayName("Inbox")
                    .uidValidity(12345L)
                    .lastSeenUid(50L)
                    .unreadCount(3)
                    .totalCount(20)
                    .build();
            folderRepo.save(state);

            Optional<FolderState> found = folderRepo.findByAccountIdAndFullName(accountId, "INBOX");
            assertThat(found).isPresent();
            assertThat(found.get().getLastSeenUid()).isEqualTo(50L);
            assertThat(found.get().getUidValidity()).isEqualTo(12345L);
        }

        @Test
        @DisplayName("findByAccountIdOrderByFullNameAsc returns all folders for the account")
        void returnsAllFoldersSorted() {
            folderRepo.save(FolderState.builder().accountId(accountId)
                    .fullName("INBOX").displayName("Inbox").build());
            folderRepo.save(FolderState.builder().accountId(accountId)
                    .fullName("[Gmail]/Sent Mail").displayName("Sent Mail").build());
            folderRepo.save(FolderState.builder().accountId(accountId)
                    .fullName("[Gmail]/Drafts").displayName("Drafts").build());

            List<FolderState> folders = folderRepo.findByAccountIdOrderByFullNameAsc(accountId);
            assertThat(folders).hasSize(3);
            assertThat(folders).extracting(FolderState::getFullName)
                    .containsExactlyInAnyOrder("INBOX", "[Gmail]/Sent Mail", "[Gmail]/Drafts");
        }
    }
}
