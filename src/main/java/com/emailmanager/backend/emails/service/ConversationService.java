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
import java.util.HashSet;
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
 * <p><b>Threading (Gmail-style):</b> primarily by the RFC 5322 reply chain —
 * the anchor's {@code Message-ID} / {@code In-Reply-To} / {@code References}
 * headers determine the thread root, and every cached message across the
 * relevant folders that links into that chain is merged. When the anchor has no
 * header data (old cached rows synced before V5), it <b>falls back</b> to the
 * normalized-subject grouping. Results are de-duplicated by Message-ID
 * (Gmail mirrors the same message in INBOX/Sent AND All-Mail), ordered
 * ascending by time, and capped.
 *
 * <p>Note: old cache rows lack the new headers, so the subject fallback applies
 * to them; newly synced / re-synced mails thread exactly by reference.
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

    /** Splits an In-Reply-To / References header into individual Message-ID tokens. */
    static List<String> parseIds(String header) {
        if (header == null || header.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String tok : header.trim().split("\\s+")) {
            String s = tok.trim();
            if (!s.isBlank()) out.add(s);
        }
        return out;
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
        String sentFolder = resolveSent(account);

        // Folders to search: INBOX + resolved Sent + resolved All-Mail + the anchor's own folder.
        Set<String> folders = new LinkedHashSet<>();
        folders.add(INBOX);
        if (sentFolder != null) folders.add(sentFolder);
        folderResolver.resolve(account, SpecialUse.ALL_MAIL).ifPresent(folders::add);
        folders.add(folder);

        // Primary: reference-chain threading. Falls back to subject when the
        // anchor carries no usable header data (old rows).
        List<CachedEmail> matched = threadByReferences(accountId, anchor, folders);
        if (matched == null) {
            matched = threadBySubject(accountId, anchor, folders);
        }
        if (matched == null || matched.isEmpty()) {
            return List.of(toDto(anchor, sentFolder, accountAddress));
        }

        List<CachedEmail> deduped = dedupeByMessageId(matched, sentFolder, account);
        deduped.sort(Comparator.comparing(CachedEmail::getReceivedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));

        return deduped.stream()
                .limit(RESULT_CAP)
                .map(e -> toDto(e, sentFolder, accountAddress))
                .toList();
    }

    // ── Reference-chain threading (primary) ──────────────────────────────────

    /**
     * Groups by the RFC reply chain. Returns null when the anchor has no header
     * data at all (caller then falls back to subject grouping).
     */
    private List<CachedEmail> threadByReferences(UUID accountId, CachedEmail anchor, Set<String> folders) {
        List<String> anchorRefs = parseIds(anchor.getReferences());
        String rootId = !anchorRefs.isEmpty()
                ? anchorRefs.get(0)
                : (notBlank(anchor.getMessageId()) ? anchor.getMessageId() : null);
        if (rootId == null) return null; // no header data → subject fallback

        Set<String> ids = new HashSet<>();
        addIfNotBlank(ids, rootId);
        addIfNotBlank(ids, anchor.getMessageId());
        addIfNotBlank(ids, anchor.getInReplyTo());
        ids.addAll(anchorRefs);

        List<CachedEmail> candidates = cachedEmailRepository.findThreadByReferences(
                accountId, folders, ids, rootId, PageRequest.of(0, CANDIDATE_CAP));

        // Transitive membership expansion to a fixpoint (bounded by candidate cap).
        Set<String> threadIds = new HashSet<>(ids);
        Set<CachedEmail> accepted = new LinkedHashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (CachedEmail e : candidates) {
                if (accepted.contains(e)) continue;
                if (belongsToChain(e, threadIds, rootId)) {
                    accepted.add(e);
                    if (notBlank(e.getMessageId()) && threadIds.add(e.getMessageId())) {
                        changed = true;
                    }
                }
            }
        }

        List<CachedEmail> matched = new ArrayList<>(accepted);
        if (matched.stream().noneMatch(e -> sameRow(e, anchor))) {
            matched.add(anchor); // safety: always include the anchor
        }
        return matched;
    }

    private boolean belongsToChain(CachedEmail e, Set<String> threadIds, String rootId) {
        if (notBlank(e.getMessageId()) && threadIds.contains(e.getMessageId())) return true;
        if (notBlank(e.getInReplyTo()) && threadIds.contains(e.getInReplyTo())) return true;
        for (String r : parseIds(e.getReferences())) {
            if (threadIds.contains(r)) return true;
        }
        return notBlank(e.getReferences()) && e.getReferences().contains(rootId);
    }

    // ── Subject threading (fallback) ─────────────────────────────────────────

    /** Returns null when the normalized subject is blank (nothing to thread on). */
    private List<CachedEmail> threadBySubject(UUID accountId, CachedEmail anchor, Set<String> folders) {
        String key = normalizeSubject(anchor.getSubject());
        if (key.isBlank()) return null;

        List<CachedEmail> candidates = cachedEmailRepository.findThreadCandidates(
                accountId, folders, key, PageRequest.of(0, CANDIDATE_CAP));

        List<CachedEmail> matched = new ArrayList<>();
        boolean anchorSeen = false;
        for (CachedEmail e : candidates) {
            if (normalizeSubject(e.getSubject()).equals(key)) {
                matched.add(e);
                if (sameRow(e, anchor)) anchorSeen = true;
            }
        }
        if (!anchorSeen) matched.add(anchor);
        return matched;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String resolveSent(EmailAccount account) {
        return folderResolver.resolve(account, SpecialUse.SENT).orElse(null);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static void addIfNotBlank(Set<String> set, String s) {
        if (notBlank(s)) set.add(s);
    }

    private static boolean sameRow(CachedEmail a, CachedEmail b) {
        return a.getUid() == b.getUid() && a.getFolder().equals(b.getFolder());
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
                e.getUid(), e.getFolder(), e.getMessageId(), e.getSubject(),
                e.getFromAddress(), e.getFromName(),
                e.getReceivedAt(), e.isSeen(), e.isHasAttachment(),
                outgoing);
    }
}
