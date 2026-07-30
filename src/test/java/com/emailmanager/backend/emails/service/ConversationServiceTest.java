package com.emailmanager.backend.emails.service;

import com.emailmanager.backend.accounts.entity.EmailAccount;
import com.emailmanager.backend.accounts.service.ImapFolderResolver;
import com.emailmanager.backend.accounts.service.ImapFolderResolver.SpecialUse;
import com.emailmanager.backend.cache.entity.CachedEmail;
import com.emailmanager.backend.cache.repository.CachedEmailRepository;
import com.emailmanager.backend.emails.dto.ConversationMessageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConversationServiceTest {

    private static final String SENT     = "[Gmail]/Gesendet";
    private static final String ALL_MAIL = "[Gmail]/Alle Nachrichten";

    @Mock CachedEmailRepository cachedEmailRepository;
    @Mock ImapFolderResolver folderResolver;

    @InjectMocks ConversationService service;

    EmailAccount account;
    UUID accountId;

    @BeforeEach
    void setUp() {
        accountId = UUID.randomUUID();
        account = new EmailAccount();
        account.setId(accountId);
        account.setEmailAddress("owner@example.com");

        when(folderResolver.resolve(account, SpecialUse.SENT)).thenReturn(Optional.of(SENT));
        when(folderResolver.resolve(account, SpecialUse.ALL_MAIL)).thenReturn(Optional.of(ALL_MAIL));
    }

    private CachedEmail email(String folder, long uid, String messageId, String subject,
                              String fromAddress, LocalDateTime receivedAt) {
        return CachedEmail.builder()
                .accountId(accountId)
                .folder(folder)
                .uid(uid)
                .messageId(messageId)
                .subject(subject)
                .fromAddress(fromAddress)
                .fromName(fromAddress)
                .receivedAt(receivedAt)
                .seen(true)
                .hasAttachment(false)
                .build();
    }

    private void anchor(CachedEmail e) {
        when(cachedEmailRepository.findByAccountIdAndFolderAndUid(accountId, e.getFolder(), e.getUid()))
                .thenReturn(Optional.of(e));
    }

    private void candidates(CachedEmail... list) {
        when(cachedEmailRepository.findThreadCandidates(eq(accountId), any(), any(), any()))
                .thenReturn(List.of(list));
    }

    // ── Subject normalization ────────────────────────────────────────────────

    @Nested
    @DisplayName("normalizeSubject")
    class Normalize {
        @Test
        @DisplayName("strips Re:/Fwd:/Aw:/Wg: (nested, case-insensitive) and lower-cases")
        void stripsPrefixes() {
            assertThat(ConversationService.normalizeSubject("Re: Arbeitszeit")).isEqualTo("arbeitszeit");
            assertThat(ConversationService.normalizeSubject("FWD: Arbeitszeit")).isEqualTo("arbeitszeit");
            assertThat(ConversationService.normalizeSubject("AW: Arbeitszeit")).isEqualTo("arbeitszeit");
            assertThat(ConversationService.normalizeSubject("WG: Arbeitszeit")).isEqualTo("arbeitszeit");
            assertThat(ConversationService.normalizeSubject("Re: Fwd:  Arbeitszeit")).isEqualTo("arbeitszeit");
            assertThat(ConversationService.normalizeSubject("  aw:re: Arbeitszeit  ")).isEqualTo("arbeitszeit");
        }

        @Test
        @DisplayName("null / empty → empty string")
        void nullEmpty() {
            assertThat(ConversationService.normalizeSubject(null)).isEmpty();
            assertThat(ConversationService.normalizeSubject("   ")).isEmpty();
        }
    }

    // ── Merge, dedup, sort, outgoing ─────────────────────────────────────────

    @Nested
    @DisplayName("getConversation")
    class GetConversation {

        @Test
        @DisplayName("merges INBOX + Sent, ascending by time, with correct outgoing flags")
        void mergesAndSorts() {
            var t0 = LocalDateTime.of(2026, 7, 20, 9, 0);
            var inbound1 = email("INBOX", 10, "<a@x>", "Korrigierte Arbeitszeitnachweise", "demir@x.com", t0);
            var reply    = email(SENT, 5, "<b@x>", "Re: Korrigierte Arbeitszeitnachweise", "owner@example.com", t0.plusHours(1));
            var inbound2 = email("INBOX", 11, "<c@x>", "Re: Korrigierte Arbeitszeitnachweise", "demir@x.com", t0.plusHours(2));

            anchor(inbound1);
            // Deliberately out of order to prove sorting.
            candidates(inbound2, inbound1, reply);

            List<ConversationMessageDto> result = service.getConversation(account, "INBOX", 10);

            assertThat(result).extracting(ConversationMessageDto::uid)
                    .containsExactly(10L, 5L, 11L);   // ascending by receivedAt
            assertThat(result).extracting(ConversationMessageDto::outgoing)
                    .containsExactly(false, true, false);
        }

        @Test
        @DisplayName("outgoing is true when fromAddress matches the account (even outside Sent)")
        void outgoingByFromAddress() {
            var t0 = LocalDateTime.of(2026, 7, 20, 9, 0);
            var mine = email(ALL_MAIL, 99, "<mine@x>", "Projekt", "OWNER@example.com", t0);
            anchor(mine);
            candidates(mine);

            var result = service.getConversation(account, ALL_MAIL, 99);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).outgoing()).isTrue();
        }

        @Test
        @DisplayName("dedupes by message_id, preferring INBOX/Sent over All-Mail")
        void dedupesByMessageId() {
            var t0 = LocalDateTime.of(2026, 7, 20, 9, 0);
            var inInbox   = email("INBOX", 10, "<dup@x>", "Angebot", "demir@x.com", t0);
            var inAllMail = email(ALL_MAIL, 800, "<dup@x>", "Angebot", "demir@x.com", t0);
            var replyMine   = email(SENT, 5, "<mine@x>", "Re: Angebot", "owner@example.com", t0.plusHours(1));
            var replyMirror = email(ALL_MAIL, 801, "<mine@x>", "Re: Angebot", "owner@example.com", t0.plusHours(1));

            anchor(inInbox);
            candidates(inAllMail, inInbox, replyMirror, replyMine);

            var result = service.getConversation(account, "INBOX", 10);

            assertThat(result).hasSize(2); // 4 rows → 2 unique message-ids
            // Kept the INBOX copy (uid 10) and the Sent copy (uid 5), not the All-Mail mirrors.
            assertThat(result).extracting(ConversationMessageDto::folder)
                    .containsExactly("INBOX", SENT);
            assertThat(result).extracting(ConversationMessageDto::uid)
                    .containsExactly(10L, 5L);
        }

        @Test
        @DisplayName("excludes candidates whose normalized subject differs (SQL LIKE false positive)")
        void excludesNonMatchingSubject() {
            var t0 = LocalDateTime.of(2026, 7, 20, 9, 0);
            var anchorMsg = email("INBOX", 10, "<a@x>", "Angebot", "demir@x.com", t0);
            var unrelated = email("INBOX", 12, "<z@x>", "Angebot 2024 Nachtrag", "x@y.com", t0.plusHours(1));

            anchor(anchorMsg);
            candidates(anchorMsg, unrelated);

            var result = service.getConversation(account, "INBOX", 10);
            assertThat(result).extracting(ConversationMessageDto::uid).containsExactly(10L);
        }

        @Test
        @DisplayName("returns empty when the anchor is not in cache")
        void emptyWhenAnchorMissing() {
            when(cachedEmailRepository.findByAccountIdAndFolderAndUid(eq(accountId), any(), anyLong()))
                    .thenReturn(Optional.empty());
            assertThat(service.getConversation(account, "INBOX", 404)).isEmpty();
        }
    }
}
