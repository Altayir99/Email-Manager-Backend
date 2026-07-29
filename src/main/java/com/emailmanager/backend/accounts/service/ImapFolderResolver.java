package com.emailmanager.backend.accounts.service;

import com.emailmanager.backend.accounts.entity.EmailAccount;
import com.sun.mail.imap.IMAPFolder;
import jakarta.mail.Folder;
import jakarta.mail.Store;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the real, locale-independent IMAP folder name for a logical
 * special-use target (Sent / Drafts / Junk / Trash / All-Mail).
 *
 * <p>Why this exists: Gmail folder names are localised — a German Gmail account
 * has {@code [Gmail]/Gesendet}, {@code [Gmail]/Papierkorb},
 * {@code [Gmail]/Alle Nachrichten} etc. Hard-coding English names
 * ({@code [Gmail]/Trash}, {@code [Gmail]/All Mail}) made write actions
 * (delete/archive/sent-append) target non-existent folders and fail.
 *
 * <p>Resolution strategy (same as the previous SyncService discovery, now the
 * single source of truth):
 * <ol>
 *   <li>RFC 6154 special-use attributes ({@code \Sent}, {@code \Trash},
 *       {@code \All}, …) — locale-independent, advertised by Gmail & most servers.</li>
 *   <li>Fallback to well-known name candidates (English AND German) when the
 *       server does not advertise the attribute.</li>
 * </ol>
 *
 * <p>Results are cached per account for the process lifetime. A failed discovery
 * (e.g. transient connection error) is NOT cached, so it is retried next time.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImapFolderResolver {

    private final ImapConnectionService imapConnectionService;

    /** Logical special-use folder targets with their RFC 6154 attribute + name fallbacks. */
    public enum SpecialUse {
        SENT("\\sent", List.of(
                "[Gmail]/Sent Mail", "[Gmail]/Gesendet",
                "Sent", "Sent Items", "Sent Messages", "Gesendet", "INBOX.Sent")),
        DRAFTS("\\drafts", List.of(
                "[Gmail]/Drafts", "[Gmail]/Entwürfe",
                "Drafts", "Draft", "Entwürfe", "INBOX.Drafts")),
        JUNK("\\junk", List.of(
                "[Gmail]/Spam",
                "Junk", "Spam", "Junk Email", "INBOX.Junk")),
        TRASH("\\trash", List.of(
                "[Gmail]/Trash", "[Gmail]/Papierkorb",
                "Trash", "Deleted Items", "Deleted Messages", "Papierkorb", "INBOX.Trash")),
        ALL_MAIL("\\all", List.of(
                "[Gmail]/All Mail", "[Gmail]/Alle Nachrichten",
                "All Mail", "Archive", "Archiv", "Alle Nachrichten"));

        private final String attribute;          // lower-case RFC 6154 attribute
        private final List<String> candidates;   // fallback names, checked in order

        SpecialUse(String attribute, List<String> candidates) {
            this.attribute = attribute;
            this.candidates = candidates;
        }
    }

    /** Folders synced in the background poll: INBOX + resolved sent/drafts/junk/trash. */
    private static final List<SpecialUse> SYNC_SET =
            List.of(SpecialUse.SENT, SpecialUse.DRAFTS, SpecialUse.JUNK, SpecialUse.TRASH);

    /** Generic IMAP fallbacks used only when discovery itself fails (not cached). */
    private static final Map<SpecialUse, String> GENERIC_FALLBACK = Map.of(
            SpecialUse.SENT, "Sent",
            SpecialUse.DRAFTS, "Drafts",
            SpecialUse.JUNK, "Junk",
            SpecialUse.TRASH, "Trash");

    /** Per-account resolved map. Only successful discoveries are cached. */
    private final ConcurrentHashMap<UUID, Map<SpecialUse, String>> cache = new ConcurrentHashMap<>();

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Resolves the real folder name for the given logical target, or empty if the
     * account has no such folder (e.g. an IMAP server without an Archive folder).
     */
    public Optional<String> resolve(EmailAccount account, SpecialUse use) {
        return Optional.ofNullable(resolvedMap(account).get(use));
    }

    /**
     * Ordered list of folders to sync for the background poll:
     * INBOX first, then the resolved Sent/Drafts/Junk/Trash (skipping any the
     * account does not have). All-Mail is intentionally excluded (perf) — it is
     * still resolvable via {@link #resolve} for archive moves.
     */
    public List<String> syncFolders(EmailAccount account) {
        Map<SpecialUse, String> map = resolvedMap(account);
        List<String> out = new ArrayList<>();
        out.add("INBOX");
        for (SpecialUse u : SYNC_SET) {
            String name = map.get(u);
            if (name != null) out.add(name);
        }
        return out;
    }

    /** Drop the cached mapping for an account (e.g. after folder structure changes). */
    public void invalidate(UUID accountId) {
        cache.remove(accountId);
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private Map<SpecialUse, String> resolvedMap(EmailAccount account) {
        Map<SpecialUse, String> cached = cache.get(account.getId());
        if (cached != null) return cached;

        Discovery d = discover(account);
        if (d.success) {
            cache.put(account.getId(), d.map);  // cache only real discoveries
        }
        return d.map;
    }

    private record Discovery(Map<SpecialUse, String> map, boolean success) {}

    private Discovery discover(EmailAccount account) {
        EnumMap<SpecialUse, String> result = new EnumMap<>(SpecialUse.class);
        Store store = imapConnectionService.acquireStore(account);
        try {
            Folder[] all = store.getDefaultFolder().list("*");

            Set<String> existingNames = new HashSet<>();
            for (Folder f : all) existingNames.add(f.getFullName());

            // Phase 1: RFC 6154 special-use attributes (locale-independent).
            for (Folder f : all) {
                if (!(f instanceof IMAPFolder imapF)) continue;
                String[] attrs = imapF.getAttributes();
                if (attrs == null) continue;
                for (String attr : attrs) {
                    String a = attr.toLowerCase();
                    for (SpecialUse u : SpecialUse.values()) {
                        if (u.attribute.equals(a) && !result.containsKey(u)) {
                            result.put(u, f.getFullName());
                        }
                    }
                }
            }

            // Phase 2: fall back to well-known names (English + German).
            for (SpecialUse u : SpecialUse.values()) {
                if (!result.containsKey(u)) {
                    u.candidates.stream()
                            .filter(existingNames::contains)
                            .findFirst()
                            .ifPresent(name -> result.put(u, name));
                }
            }

            log.info("[FolderResolver] Resolved for {}: {}", account.getEmailAddress(), result);
            return new Discovery(result, true);
        } catch (Exception e) {
            log.warn("[FolderResolver] Discovery failed for {}: {} — using generic fallbacks (not cached)",
                    account.getEmailAddress(), e.getMessage());
            // Best-effort generic fallbacks so a transient failure does not hard-crash
            // a write; ALL_MAIL is left unresolved (no safe generic default).
            return new Discovery(new EnumMap<>(GENERIC_FALLBACK), false);
        } finally {
            imapConnectionService.releaseStore(account.getId());
        }
    }
}
