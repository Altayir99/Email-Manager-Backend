package com.emailmanager.backend.emails.service;

import com.emailmanager.backend.accounts.entity.EmailAccount;
import com.emailmanager.backend.accounts.service.ImapConnectionService;
import com.emailmanager.backend.cache.entity.CachedEmail;
import com.emailmanager.backend.cache.repository.CachedEmailRepository;
import com.sun.mail.imap.IMAPFolder;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.util.*;

/**
 * Server-side email search — supports both local cache and live IMAP search.
 *
 * <p>Cache search: fast, queries Postgres full-text indexes.
 * <p>IMAP search: slower, but finds messages the cache may not have yet.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EmailSearchService {

    private static final int DEFAULT_PAGE_SIZE = 30;
    private static final int SNIPPET_MAX = 200;

    private final CachedEmailRepository cachedEmailRepo;
    private final ImapConnectionService imapConnectionService;

    // ── Cache search (default) ──────────────────────────────────────────────

    /**
     * Fast full-text search against the local Postgres cache.
     * Matches subject, sender address, sender name, and snippet.
     */
    public Page<CachedEmail> searchCache(UUID accountId, String folder, String query, int pageSize) {
        return cachedEmailRepo.searchByAccountIdAndFolder(
                accountId, folder, query, PageRequest.of(0, pageSize));
    }

    // ── IMAP search (live) ──────────────────────────────────────────────────

    /**
     * Live IMAP search — queries the mail server directly.
     * Falls back to cache search on IMAP failure.
     *
     * @return list of email summaries matching the search term
     */
    public List<Map<String, Object>> searchImap(EmailAccount account, String folderName, String query, int limit) {
        Store store = null;
        try {
            store = imapConnectionService.acquireStore(account);
            IMAPFolder folder = (IMAPFolder) store.getFolder(folderName);
            folder.open(Folder.READ_ONLY);

            try {
                // Build a compound search term: subject OR from OR body
                SearchTerm searchTerm = new OrTerm(new SearchTerm[]{
                        new SubjectTerm(query),
                        new FromStringTerm(query),
                        new BodyTerm(query)
                });

                Message[] messages = folder.search(searchTerm);

                // Sort by date descending and limit results
                Arrays.sort(messages, (a, b) -> {
                    try {
                        Date da = a.getReceivedDate();
                        Date db = b.getReceivedDate();
                        if (da == null && db == null) return 0;
                        if (da == null) return 1;
                        if (db == null) return -1;
                        return db.compareTo(da);
                    } catch (MessagingException e) {
                        return 0;
                    }
                });

                int count = Math.min(messages.length, limit);
                List<Map<String, Object>> results = new ArrayList<>(count);

                // Pre-fetch envelopes for performance
                Message[] subset = Arrays.copyOf(messages, count);
                FetchProfile fp = new FetchProfile();
                fp.add(FetchProfile.Item.ENVELOPE);
                fp.add(FetchProfile.Item.FLAGS);
                fp.add(IMAPFolder.FetchProfileItem.SIZE);
                folder.fetch(subset, fp);

                for (int i = 0; i < count; i++) {
                    Message msg = subset[i];
                    try {
                        results.add(messageToMap(msg, folder));
                    } catch (Exception e) {
                        log.debug("[Search] Skipping message due to parse error: {}", e.getMessage());
                    }
                }

                return results;
            } finally {
                if (folder.isOpen()) folder.close(false);
            }
        } catch (Exception e) {
            log.warn("[Search] IMAP search failed for account {}, falling back to cache: {}",
                    account.getEmailAddress(), e.getMessage());
            // Fallback: return cache results as maps
            return cacheResultsAsMaps(account.getId(), folderName, query);
        } finally {
            if (store != null) {
                imapConnectionService.releaseStore(account.getId());
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> messageToMap(Message msg, IMAPFolder folder) throws MessagingException {
        Map<String, Object> map = new LinkedHashMap<>();

        long uid = folder.getUID(msg);
        map.put("uid", uid);
        map.put("subject", msg.getSubject() != null ? msg.getSubject() : "(no subject)");

        Address[] from = msg.getFrom();
        if (from != null && from.length > 0) {
            InternetAddress addr = (InternetAddress) from[0];
            map.put("fromAddress", addr.getAddress() != null ? addr.getAddress() : "");
            map.put("fromName", addr.getPersonal() != null ? addr.getPersonal() : "");
        } else {
            map.put("fromAddress", "");
            map.put("fromName", "");
        }

        Date receivedDate = msg.getReceivedDate();
        map.put("receivedAt", receivedDate != null
                ? receivedDate.toInstant().atOffset(ZoneOffset.UTC).toString()
                : null);

        boolean seen = msg.isSet(Flags.Flag.SEEN);
        map.put("seen", seen);

        // Snippet from content (best-effort)
        map.put("snippet", extractSnippet(msg));

        map.put("hasAttachments", hasAttachments(msg));

        return map;
    }

    private String extractSnippet(Message msg) {
        try {
            Object content = msg.getContent();
            if (content instanceof String text) {
                return text.length() > SNIPPET_MAX ? text.substring(0, SNIPPET_MAX) : text;
            }
            if (content instanceof Multipart multipart) {
                for (int i = 0; i < multipart.getCount(); i++) {
                    BodyPart part = multipart.getBodyPart(i);
                    if (part.isMimeType("text/plain")) {
                        String text = (String) part.getContent();
                        return text.length() > SNIPPET_MAX ? text.substring(0, SNIPPET_MAX) : text;
                    }
                }
            }
        } catch (Exception ignored) {
            // Content fetch may fail for some messages — return empty snippet
        }
        return "";
    }

    private boolean hasAttachments(Message msg) {
        try {
            if (msg.isMimeType("multipart/*")) {
                Multipart multipart = (Multipart) msg.getContent();
                for (int i = 0; i < multipart.getCount(); i++) {
                    if (Part.ATTACHMENT.equalsIgnoreCase(multipart.getBodyPart(i).getDisposition())) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private List<Map<String, Object>> cacheResultsAsMaps(UUID accountId, String folder, String query) {
        Page<CachedEmail> page = searchCache(accountId, folder, query, DEFAULT_PAGE_SIZE);
        List<Map<String, Object>> results = new ArrayList<>();
        for (CachedEmail email : page.getContent()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("uid", email.getUid());
            map.put("subject", email.getSubject());
            map.put("fromAddress", email.getFromAddress());
            map.put("fromName", email.getFromName());
            map.put("receivedAt", email.getReceivedAt() != null ? email.getReceivedAt().toString() : null);
            map.put("seen", email.isSeen());
            map.put("snippet", email.getSnippet());
            map.put("hasAttachments", email.isHasAttachment());
            results.add(map);
        }
        return results;
    }
}
