package com.emailmanager.backend.emails.service;

import com.emailmanager.backend.accounts.entity.EmailAccount;
import com.emailmanager.backend.accounts.service.ImapFolderResolver;
import com.emailmanager.backend.accounts.service.ImapFolderResolver.SpecialUse;
import com.emailmanager.backend.cache.entity.CachedEmail;
import com.emailmanager.backend.cache.repository.CachedEmailRepository;
import com.emailmanager.backend.emails.dto.ConversationMessageDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Builds a cross-folder conversation view (INBOX + Sent + All-Mail) for a
 * message, cache-first (no IMAP in the request path).
 *
 * <p>Threading is subject-based (no DB migration): the anchor's subject is
 * normalized by iteratively stripping leading reply/forward prefixes
 * (re:/fwd:/fw:/aw:/wg:), and every cached message across the relevant folders
 * whose normalized subject matches is merged, de-duplicated by Message-ID
 * (Gmail mirrors the same message in INBOX/Sent AND All-Mail) and returned in
 * ascending chronological order.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationService {

    private static final String INBOX = "INBOX";
    private static final int CANDIDATE_CAP = 200;   // SQL pre-filter cap
    private static final int RESULT_CAP    = 50;    // final thread size cap

    /** Leading reply/forward prefixes, matched at the start, case-insensitive. */
    private static final Pattern PREFIX =
            Pattern.compile("^(re|fwd|fw|aw|wg)\\s*:\\s*", Pattern.CASE_INSENSITIVE);

    private final CachedEmailRepository cachedEmailRepository;
    private final ImapFolderResolver folderResolver;

    /**
     * Normalizes a subject into a thread key: strips all leading Re:/Fwd:/Aw:…
     * prefixes iteratively, trims, lower-cases. Mirrors the Flutter {@code threadKey}.
     */
    static String normalizeSubject(String subject) {
        if (subject == null) return "";
        String s = subject.trim();
        while (true) {
            String stripped = PREFIX.matcher(s).replaceFirst("").trim();
            if (stripped.equals(s)) break;   // no prefix left
            s = stripped;
        }
        return s.toLowerCase();
    }

    /**
     * Returns the conversation for the message identified by (folder, uid),
     * ascending by received time. Cache-only. Returns just the anchor if no
     * other messages match (or the anchor is missing from cache).
     */
    public List<ConversationMessageDto> getConversation(EmailAccount account, String folder, long uid) {
        UUID accountId = account.getId();

        CachedEmail anchor = cachedEmailRepository
                .findByAccountIdAndFolderAndUid(accountId, folder, uid)
                .orElse(null);
        if (anchor == null) {
            log.debug("[Conversation] anchor not in cache: account={} folder={} uid={}", accountId, folder, uid);
            return List.of();
        }

        String accountAddress = account.getEmailAddress();
        String key = normalizeSubject(anchor.getSubject());
        if (key.isBlank()) {
            // Nothing meaningful to thread on — return just the anchor.
            return List.of(toDto(anchor, resolveSent(account), accountAddress));
        }

        // Folders to search: INBOX + resolved Sent + resolved All-Mail + the anchor's own folder.
        String sentFolder = resolveSent(account);
        Set<String> folders = new LinkedHashSet<>();
        folders.add(INBOX);
        if (sentFolder != null) folders.add(sentFolder);
        folderResolver.resolve(account, SpecialUse.ALL_MAIL).ifPresent(folders::add);
        folders.add(folder); // ensure the anchor's folder is covered (e.g. a custom label)

        List<CachedEmail> candidates = cachedEmailRepository.findThreadCandidates(
                accountId, folders, key, PageRequest.of(0, CANDIDATE_CAP));

        // Exact match on the normalized key (SQL LIKE is a coarse pre-filter).
        List<CachedEmail> matched = new ArrayList<>();
        boolean anchorSeen = false;
        for (CachedEmail e : candidates) {
            if (normalizeSubject(e.getSubject()).equals(key)) {
                matched.add(e);
                if (e.getFolder().equals(folder) && e.getUid() == uid) anchorSeen = true;
            }
        }
        if (!anchorSeen) matched.add(anchor); // safety: always include the anchor

        // De-duplicate by Message-ID, preferring INBOX/Sent over All-Mail.
        List<CachedEmail> deduped = dedupeByMessageId(matched, sentFolder, account);

        // Ascending chronological order (nulls last), capped.
        deduped.sort(Comparator.comparing(CachedEmail::getReceivedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));

        return deduped.stream()
                .limit(RESULT_CAP)
                .map(e -> toDto(e, sentFolder, accountAddress))
                .toList();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String resolveSent(EmailAccount account) {
        return folderResolver.resolve(account, SpecialUse.SENT).orElse(null);
    }

    /**
     * Keeps one message per Message-ID. Priority: INBOX &lt; Sent &lt; All-Mail
     * &lt; other (lower wins). Messages without a Message-ID cannot be de-duped
     * and are all kept.
     */
    private List<CachedEmail> dedupeByMessageId(List<CachedEmail> messages, String sentFolder, EmailAccount account) {
        String allMail = folderResolver.resolve(account, SpecialUse.ALL_MAIL).orElse(null);
        Map<String, CachedEmail> byId = new LinkedHashMap<>();
        List<CachedEmail> noId = new ArrayList<>();

        for (CachedEmail e : messages) {
            String mid = e.getMessageId();
            if (mid == null || mid.isBlank()) {
                noId.add(e);
                continue;
            }
            CachedEmail existing = byId.get(mid);
            if (existing == null
                    || folderPriority(e.getFolder(), sentFolder, allMail)
                       < folderPriority(existing.getFolder(), sentFolder, allMail)) {
                byId.put(mid, e);
            }
        }

        List<CachedEmail> out = new ArrayList<>(byId.values());
        out.addAll(noId);
        return out;
    }

    private int folderPriority(String folder, String sentFolder, String allMail) {
        if (INBOX.equals(folder)) return 0;
        if (folder.equals(sentFolder)) return 1;
        if (folder.equals(allMail)) return 3;
        return 2; // other real labels rank above All-Mail (the mirror)
    }

    private ConversationMessageDto toDto(CachedEmail e, String sentFolder, String accountAddress) {
        boolean outgoing = e.getFolder().equals(sentFolder)
                || (e.getFromAddress() != null && accountAddress != null
                    && e.getFromAddress().equalsIgnoreCase(accountAddress));
        return new ConversationMessageDto(
                e.getUid(), e.getFolder(), e.getSubject(),
                e.getFromAddress(), e.getFromName(),
                e.getReceivedAt(), e.isSeen(), e.isHasAttachment(),
                outgoing);
    }
}
