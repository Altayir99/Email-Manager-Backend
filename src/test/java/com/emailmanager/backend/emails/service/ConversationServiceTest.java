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

    /** Full builder incl. threading headers. */
    private CachedEmail email(String folder, long uid, String messageId, String inReplyTo,
                              String references, String subject, String fromAddress,
                              LocalDateTime receivedAt) {
        return CachedEmail.builder()
                .accountId(accountId)
                .folder(folder)
                .uid(uid)
                .messageId(messageId)
                .inReplyTo(inReplyTo)
                .references(references)
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

    private void refCandidates(CachedEmail... list) {
        when(cachedEmailRepository.findThreadByReferences(eq(accountId), any(), any(), any(), any()))
                .thenReturn(List.of(list));
    }

    private void subjectCandidates(CachedEmail... list) {
        when(cachedEmailRepository.findThreadCandidates(eq(accountId), any(), any(), any()))
                .thenReturn(List.of(list));
    }

    // ── Pure helpers ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("normalizeSubject / parseIds")
    class Helpers {
        @Test
        @DisplayName("normalizeSubject strips prefixes (nested, case-insensitive)")
        void normalize() {
            assertThat(ConversationService.normalizeSubject("Re: Fwd: AW: Info")).isEqualTo("info");
            assertThat(ConversationService.normalizeSubject(null)).isEmpty();
        }

        @Test
        @DisplayName("parseIds splits whitespace-separated Message-IDs")
        void parse() {
            assertThat(ConversationService.parseIds("<a@x>  <b@x>\t<c@x>"))
                    .containsExactly("<a@x>", "<b@x>", "<c@x>");
            assertThat(ConversationService.parseIds(null)).isEmpty();
        }
    }

    // ── Reference-chain threading (primary) ──────────────────────────────────

    @Nested
    @DisplayName("Reference-chain threading")
    class ReferenceThreading {

        @Test
        @DisplayName("merges the reply chain across INBOX + Sent, ascending, with outgoing flags")
        void mergesChain() {
            var t0 = LocalDateTime.of(2026, 7, 20, 9, 0);
            var root  = email("INBOX", 10, "<m1>", null, null,
                    "Korrigierte Arbeitszeitnachweise", "demir@x.com", t0);
            var reply = email(SENT, 5, "<m2>", "<m1>", "<m1>",
                    "Re: Korrigierte Arbeitszeitnachweise", "owner@example.com", t0.plusHours(1));
            var inbound2 = email("INBOX", 11, "<m3>", "<m2>", "<m1> <m2>",
                    "Re: Korrigierte Arbeitszeitnachweise", "demir@x.com", t0.plusHours(2));

            anchor(root);
            refCandidates(inbound2, reply, root); // deliberately unordered

            List<ConversationMessageDto> result = service.getConversation(account, "INBOX", 10);

            assertThat(result).extracting(ConversationMessageDto::uid)
                    .containsExactly(10L, 5L, 11L);
            assertThat(result).extracting(ConversationMessageDto::outgoing)
                    .containsExactly(false, true, false);
        }

        @Test
        @DisplayName("does NOT pull in a same-subject message from a different chain")
        void ignoresUnrelatedSameSubject() {
            var t0 = LocalDateTime.of(2026, 7, 20, 9, 0);
            var root = email("INBOX", 10, "<m1>", null, null, "Info", "a@x.com", t0);
            // Same subject "Info" but no link into the chain (own message-id, no refs).
            var unrelated = email("INBOX", 12, "<other>", null, null, "Info", "stranger@x.com", t0.plusHours(1));

            anchor(root);
            refCandidates(root, unrelated);

            var result = service.getConversation(account, "INBOX", 10);
            assertThat(result).extracting(ConversationMessageDto::uid).containsExactly(10L);
        }

        @Test
        @DisplayName("dedupes by message_id, preferring INBOX/Sent over All-Mail")
        void dedupes() {
            var t0 = LocalDateTime.of(2026, 7, 20, 9, 0);
            var rootInbox   = email("INBOX", 10, "<m1>", null, null, "Angebot", "demir@x.com", t0);
            var rootMirror  = email(ALL_MAIL, 800, "<m1>", null, null, "Angebot", "demir@x.com", t0);
            var replySent   = email(SENT, 5, "<m2>", "<m1>", "<m1>", "Re: Angebot", "owner@example.com", t0.plusHours(1));
            var replyMirror = email(ALL_MAIL, 801, "<m2>", "<m1>", "<m1>", "Re: Angebot", "owner@example.com", t0.plusHours(1));

            anchor(rootInbox);
            refCandidates(rootMirror, rootInbox, replyMirror, replySent);

            var result = service.getConversation(account, "INBOX", 10);

            assertThat(result).hasSize(2);
            assertThat(result).extracting(ConversationMessageDto::folder)
                    .containsExactly("INBOX", SENT);
        }
    }

    // ── Subject fallback (old rows without headers) ──────────────────────────

    @Nested
    @DisplayName("Subject fallback")
    class SubjectFallback {

        @Test
        @DisplayName("groups by normalized subject when the anchor has no header data")
        void fallsBackToSubject() {
            var t0 = LocalDateTime.of(2026, 7, 20, 9, 0);
            // No messageId / references → reference threading returns null → subject.
            var a = email("INBOX", 10, null, null, null, "Rechnung Juni", "demir@x.com", t0);
            var b = email(SENT, 5, null, null, null, "Re: Rechnung Juni", "owner@example.com", t0.plusHours(1));
            var unrelated = email("INBOX", 12, null, null, null, "Rechnung Juni 2024", "x@y.com", t0.plusHours(2));

            anchor(a);
            subjectCandidates(a, b, unrelated);

            var result = service.getConversation(account, "INBOX", 10);
            assertThat(result).extracting(ConversationMessageDto::uid).containsExactly(10L, 5L);
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class Edge {
        @Test
        @DisplayName("returns empty when the anchor is not in cache")
        void emptyWhenAnchorMissing() {
            when(cachedEmailRepository.findByAccountIdAndFolderAndUid(eq(accountId), any(), anyLong()))
                    .thenReturn(Optional.empty());
            assertThat(service.getConversation(account, "INBOX", 404)).isEmpty();
        }
    }
}
