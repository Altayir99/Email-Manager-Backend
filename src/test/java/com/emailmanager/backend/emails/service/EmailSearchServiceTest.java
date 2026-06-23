package com.emailmanager.backend.emails.service;

import com.emailmanager.backend.accounts.entity.EmailAccount;
import com.emailmanager.backend.accounts.service.ImapConnectionService;
import com.emailmanager.backend.cache.entity.CachedEmail;
import com.emailmanager.backend.cache.repository.CachedEmailRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmailSearchServiceTest {

    private CachedEmailRepository cachedEmailRepo;
    private ImapConnectionService imapConnectionService;
    private EmailSearchService searchService;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        cachedEmailRepo = mock(CachedEmailRepository.class);
        imapConnectionService = mock(ImapConnectionService.class);
        searchService = new EmailSearchService(cachedEmailRepo, imapConnectionService);
        accountId = UUID.randomUUID();
    }

    @Nested
    @DisplayName("Cache Search")
    class CacheSearch {

        @Test
        @DisplayName("searchCache returns paginated results from repository")
        void searchCacheReturnsPaginatedResults() {
            CachedEmail email = new CachedEmail();
            email.setAccountId(accountId);
            email.setFolder("INBOX");
            email.setUid(100L);
            email.setSubject("Test Subject");
            email.setFromAddress("sender@example.com");
            email.setFromName("Sender");
            email.setSnippet("This is a test email");
            email.setSeen(false);
            email.setReceivedAt(LocalDateTime.now());

            Page<CachedEmail> page = new PageImpl<>(List.of(email));
            when(cachedEmailRepo.searchByAccountIdAndFolder(
                    eq(accountId), eq("INBOX"), eq("test"), any(PageRequest.class)))
                    .thenReturn(page);

            Page<CachedEmail> results = searchService.searchCache(accountId, "INBOX", "test", 30);

            assertNotNull(results);
            assertEquals(1, results.getTotalElements());
            assertEquals("Test Subject", results.getContent().get(0).getSubject());
            verify(cachedEmailRepo).searchByAccountIdAndFolder(
                    eq(accountId), eq("INBOX"), eq("test"), any(PageRequest.class));
        }

        @Test
        @DisplayName("searchCache returns empty page when no matches")
        void searchCacheReturnsEmptyWhenNoMatches() {
            Page<CachedEmail> emptyPage = Page.empty();
            when(cachedEmailRepo.searchByAccountIdAndFolder(any(), any(), any(), any()))
                    .thenReturn(emptyPage);

            Page<CachedEmail> results = searchService.searchCache(accountId, "INBOX", "xyz", 30);

            assertNotNull(results);
            assertEquals(0, results.getTotalElements());
        }
    }

    @Nested
    @DisplayName("IMAP Search Fallback")
    class ImapSearchFallback {

        @Test
        @DisplayName("searchImap falls back to cache when IMAP connection fails")
        void searchImapFallsBackToCacheOnFailure() {
            EmailAccount account = new EmailAccount();
            account.setId(accountId);
            account.setEmailAddress("test@example.com");

            // IMAP connection throws
            when(imapConnectionService.acquireStore(any()))
                    .thenThrow(new RuntimeException("Connection refused"));

            // Cache fallback data
            CachedEmail email = new CachedEmail();
            email.setUid(200L);
            email.setSubject("Fallback Result");
            email.setFromAddress("fallback@example.com");
            email.setFromName("Fallback");
            email.setSnippet("fallback snippet");
            email.setSeen(true);
            email.setReceivedAt(LocalDateTime.now());

            Page<CachedEmail> fallbackPage = new PageImpl<>(List.of(email));
            when(cachedEmailRepo.searchByAccountIdAndFolder(
                    eq(accountId), eq("INBOX"), eq("test"), any(PageRequest.class)))
                    .thenReturn(fallbackPage);

            List<Map<String, Object>> results = searchService.searchImap(account, "INBOX", "test", 30);

            assertNotNull(results);
            assertEquals(1, results.size());
            assertEquals("Fallback Result", results.get(0).get("subject"));
            assertEquals(200L, results.get(0).get("uid"));
        }
    }

    @Nested
    @DisplayName("PendingSendResponse DTO")
    class PendingSendResponseTest {

        @Test
        @DisplayName("queued() creates response with correct status")
        void queuedHasCorrectStatus() {
            var response = com.emailmanager.backend.emails.dto.PendingSendResponse.queued(
                    UUID.randomUUID(), java.time.Instant.now().plusSeconds(10));
            assertEquals("queued", response.status());
            assertNotNull(response.sendId());
            assertNotNull(response.expiresAt());
        }

        @Test
        @DisplayName("cancelled() creates response with null expiresAt")
        void cancelledHasNullExpiry() {
            var response = com.emailmanager.backend.emails.dto.PendingSendResponse.cancelled(UUID.randomUUID());
            assertEquals("cancelled", response.status());
            assertNull(response.expiresAt());
        }

        @Test
        @DisplayName("sent() creates response with null expiresAt")
        void sentHasNullExpiry() {
            var response = com.emailmanager.backend.emails.dto.PendingSendResponse.sent(UUID.randomUUID());
            assertEquals("sent", response.status());
            assertNull(response.expiresAt());
        }
    }
}
