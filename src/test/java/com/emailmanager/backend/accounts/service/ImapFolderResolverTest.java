package com.emailmanager.backend.accounts.service;

import com.emailmanager.backend.accounts.entity.EmailAccount;
import com.emailmanager.backend.accounts.service.ImapFolderResolver.SpecialUse;
import com.sun.mail.imap.IMAPFolder;
import jakarta.mail.Folder;
import jakarta.mail.Store;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ImapFolderResolver} — the locale-independent special-use
 * folder resolver that fixes German-Gmail write actions.
 *
 * <p>Covers: RFC 6154 attribute resolution, English + German name fallbacks,
 * All-Mail resolution, per-account caching, and graceful failure.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImapFolderResolverTest {

    @Mock ImapConnectionService imapConnectionService;
    @Mock Store store;

    ImapFolderResolver resolver;
    EmailAccount account;

    @BeforeEach
    void setUp() {
        resolver = new ImapFolderResolver(imapConnectionService);
        account = new EmailAccount();
        account.setId(UUID.randomUUID());
        account.setEmailAddress("test@example.com");
        when(imapConnectionService.acquireStore(account)).thenReturn(store);
    }

    /** Builds a mock IMAP folder with a full name and optional special-use attributes. */
    private IMAPFolder folder(String fullName, String... attrs) throws Exception {
        IMAPFolder f = mock(IMAPFolder.class);
        lenient().when(f.getFullName()).thenReturn(fullName);
        lenient().when(f.getAttributes()).thenReturn(attrs.length == 0 ? new String[0] : attrs);
        return f;
    }

    /** Wires store.getDefaultFolder().list("*") to return the given folders. */
    private void serverFolders(Folder... folders) throws Exception {
        Folder root = mock(Folder.class);
        when(store.getDefaultFolder()).thenReturn(root);
        when(root.list("*")).thenReturn(folders);
    }

    // ── RFC 6154 attribute resolution ────────────────────────────────────────

    @Nested
    @DisplayName("RFC 6154 attributes")
    class Attributes {

        @Test
        @DisplayName("resolves German Gmail folders via \\Sent \\Trash \\All attributes")
        void resolvesGermanGmailViaAttributes() throws Exception {
            serverFolders(
                    folder("INBOX"),
                    folder("[Gmail]/Gesendet", "\\Sent"),
                    folder("[Gmail]/Entwürfe", "\\Drafts"),
                    folder("[Gmail]/Papierkorb", "\\Trash"),
                    folder("[Gmail]/Spam", "\\Junk"),
                    folder("[Gmail]/Alle Nachrichten", "\\All"));

            assertThat(resolver.resolve(account, SpecialUse.SENT)).contains("[Gmail]/Gesendet");
            assertThat(resolver.resolve(account, SpecialUse.TRASH)).contains("[Gmail]/Papierkorb");
            assertThat(resolver.resolve(account, SpecialUse.ALL_MAIL)).contains("[Gmail]/Alle Nachrichten");
            assertThat(resolver.resolve(account, SpecialUse.DRAFTS)).contains("[Gmail]/Entwürfe");
            assertThat(resolver.resolve(account, SpecialUse.JUNK)).contains("[Gmail]/Spam");
        }

        @Test
        @DisplayName("attributes are case-insensitive")
        void attributesCaseInsensitive() throws Exception {
            serverFolders(folder("Papierkorb", "\\TRASH"));
            assertThat(resolver.resolve(account, SpecialUse.TRASH)).contains("Papierkorb");
        }
    }

    // ── Fallback name candidates ─────────────────────────────────────────────

    @Nested
    @DisplayName("Name fallbacks (no attributes advertised)")
    class Fallbacks {

        @Test
        @DisplayName("falls back to German [Gmail] names when no attributes present")
        void fallsBackToGermanNames() throws Exception {
            serverFolders(
                    folder("INBOX"),
                    folder("[Gmail]/Gesendet"),
                    folder("[Gmail]/Papierkorb"),
                    folder("[Gmail]/Alle Nachrichten"));

            assertThat(resolver.resolve(account, SpecialUse.SENT)).contains("[Gmail]/Gesendet");
            assertThat(resolver.resolve(account, SpecialUse.TRASH)).contains("[Gmail]/Papierkorb");
            assertThat(resolver.resolve(account, SpecialUse.ALL_MAIL)).contains("[Gmail]/Alle Nachrichten");
        }

        @Test
        @DisplayName("falls back to English generic IMAP names")
        void fallsBackToEnglishNames() throws Exception {
            serverFolders(
                    folder("INBOX"),
                    folder("Sent"),
                    folder("Trash"),
                    folder("Drafts"));

            assertThat(resolver.resolve(account, SpecialUse.SENT)).contains("Sent");
            assertThat(resolver.resolve(account, SpecialUse.TRASH)).contains("Trash");
            assertThat(resolver.resolve(account, SpecialUse.DRAFTS)).contains("Drafts");
        }

        @Test
        @DisplayName("returns empty when the account has no such folder")
        void emptyWhenAbsent() throws Exception {
            serverFolders(folder("INBOX"), folder("Sent"));
            assertThat(resolver.resolve(account, SpecialUse.ALL_MAIL)).isEmpty();
        }
    }

    // ── syncFolders list ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("syncFolders")
    class SyncFolders {

        @Test
        @DisplayName("returns INBOX + resolved sent/drafts/junk/trash, WITHOUT all-mail")
        void buildsSyncList() throws Exception {
            serverFolders(
                    folder("INBOX"),
                    folder("[Gmail]/Gesendet", "\\Sent"),
                    folder("[Gmail]/Entwürfe", "\\Drafts"),
                    folder("[Gmail]/Papierkorb", "\\Trash"),
                    folder("[Gmail]/Spam", "\\Junk"),
                    folder("[Gmail]/Alle Nachrichten", "\\All"));

            assertThat(resolver.syncFolders(account))
                    .containsExactly("INBOX", "[Gmail]/Gesendet", "[Gmail]/Entwürfe",
                            "[Gmail]/Spam", "[Gmail]/Papierkorb")
                    .doesNotContain("[Gmail]/Alle Nachrichten");
        }
    }

    // ── Caching + failure ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Caching & resilience")
    class CachingAndFailure {

        @Test
        @DisplayName("caches a successful discovery — IMAP is hit only once per account")
        void cachesSuccess() throws Exception {
            serverFolders(folder("INBOX"), folder("Sent"), folder("Trash"));

            resolver.resolve(account, SpecialUse.SENT);
            resolver.resolve(account, SpecialUse.TRASH);
            resolver.syncFolders(account);

            verify(imapConnectionService, times(1)).acquireStore(account);
        }

        @Test
        @DisplayName("does NOT cache a failed discovery — retried next time, always released")
        void doesNotCacheFailure() throws Exception {
            when(store.getDefaultFolder()).thenThrow(new RuntimeException("connection dropped"));

            // Failure → generic fallbacks (Trash), not cached
            assertThat(resolver.resolve(account, SpecialUse.TRASH)).contains("Trash");
            resolver.resolve(account, SpecialUse.SENT);

            // Two calls → two discovery attempts (not cached) and both released.
            verify(imapConnectionService, times(2)).acquireStore(account);
            verify(imapConnectionService, times(2)).releaseStore(account.getId());
        }

        @Test
        @DisplayName("always releases the store lock (even on success)")
        void releasesOnSuccess() throws Exception {
            serverFolders(folder("INBOX"), folder("Sent"));
            resolver.resolve(account, SpecialUse.SENT);
            verify(imapConnectionService, times(1)).releaseStore(account.getId());
        }
    }
}
