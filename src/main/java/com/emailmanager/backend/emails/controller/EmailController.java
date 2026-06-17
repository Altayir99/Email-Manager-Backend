package com.emailmanager.backend.emails.controller;

import com.emailmanager.backend.accounts.entity.EmailAccount;
import com.emailmanager.backend.accounts.service.EmailAccountService;
import com.emailmanager.backend.cache.entity.CachedEmail;
import com.emailmanager.backend.cache.entity.FolderState;
import com.emailmanager.backend.cache.repository.CachedEmailRepository;
import com.emailmanager.backend.cache.repository.FolderStateRepository;
import com.emailmanager.backend.emails.dto.*;
import com.emailmanager.backend.emails.service.EmailActionService;
import com.emailmanager.backend.emails.service.EmailFetchService;
import com.emailmanager.backend.emails.service.EmailSendService;
import com.emailmanager.backend.sync.SyncService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 1 rewrite: ALL reads are served from the local Postgres cache.
 * IMAP is never touched on the request path for reads.
 *
 * Write actions (send, mark-read, move, delete, archive) still go to IMAP
 * and are then written through to the cache immediately.
 */
@RestController
@RequestMapping("/api/accounts/{accountId}")
@RequiredArgsConstructor
@Slf4j
public class EmailController {

    private final EmailAccountService accountService;
    private final EmailFetchService fetchService;      // kept for lazy body fetch + writes
    private final EmailSendService sendService;
    private final EmailActionService actionService;
    private final SyncService syncService;

    // ── Cache repositories (read path) ───────────────────────────────────────
    private final CachedEmailRepository cachedEmailRepo;
    private final FolderStateRepository folderStateRepo;

    // ── Folders — served from cache ──────────────────────────────────────────

    @GetMapping("/folders")
    public ResponseEntity<List<FolderDto>> getFolders(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID accountId) {

        accountService.getAccountEntity(user.getUsername(), accountId); // auth check
        List<FolderState> states = folderStateRepo.findByAccountIdOrderByFullNameAsc(accountId);

        if (states.isEmpty()) {
            // Cache not yet populated — trigger sync and return empty for now
            EmailAccount account = accountService.getAccountEntity(user.getUsername(), accountId);
            syncService.syncAccountNow(account);
            states = folderStateRepo.findByAccountIdOrderByFullNameAsc(accountId);
        }

        List<FolderDto> result = states.stream()
                .map(s -> new FolderDto(s.getDisplayName(), s.getFullName(),
                        s.getUnreadCount(), s.getTotalCount(), false))
                .toList();

        return ResponseEntity.ok(result);
    }

    // ── Email List — served from cache ───────────────────────────────────────

    @GetMapping("/emails")
    public ResponseEntity<EmailPageDto> getEmails(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID accountId,
            @RequestParam(defaultValue = "INBOX") String folder,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize) {

        accountService.getAccountEntity(user.getUsername(), accountId); // auth check
        Page<CachedEmail> cached = cachedEmailRepo
                .findByAccountIdAndFolderOrderByReceivedAtDesc(
                        accountId, folder, PageRequest.of(page, pageSize));

        if (cached.isEmpty() && page == 0) {
            // Cache cold — trigger a sync, then re-query
            EmailAccount account = accountService.getAccountEntity(user.getUsername(), accountId);
            syncService.syncAccountNow(account);
            cached = cachedEmailRepo.findByAccountIdAndFolderOrderByReceivedAtDesc(
                    accountId, folder, PageRequest.of(page, pageSize));
        }

        List<EmailSummaryDto> emails = cached.stream()
                .map(this::toSummaryDto)
                .toList();

        boolean hasMore = cached.hasNext();
        long total = cached.getTotalElements();

        return ResponseEntity.ok(new EmailPageDto(emails, page, pageSize, (int) total, hasMore));
    }

    // ── Email Detail — cache hit + lazy body load ────────────────────────────

    @GetMapping("/emails/{uid}")
    @Transactional
    public ResponseEntity<EmailDetailDto> getEmailDetail(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID accountId,
            @PathVariable long uid,
            @RequestParam(defaultValue = "INBOX") String folder) throws Exception {

        EmailAccount account = accountService.getAccountEntity(user.getUsername(), accountId);

        CachedEmail cached = cachedEmailRepo
                .findByAccountIdAndFolderAndUid(accountId, folder, uid)
                .orElse(null);

        if (cached != null && cached.isBodyLoaded()) {
            // Full cache hit — no IMAP needed
            cachedEmailRepo.updateSeen(accountId, folder, uid, true);
            return ResponseEntity.ok(toDetailDto(cached));
        }

        // Lazy body fetch via IMAP (only for bodies, not metadata)
        try {
            EmailDetailDto detail = fetchService.fetchEmailDetail(account, folder, uid);
            // Write body back to cache
            if (cached != null) {
                cached.setBodyText(detail.bodyText());
                cached.setBodyHtml(detail.bodyHtml());
                cached.setBodyLoaded(true);
                cached.setSeen(true);
                cachedEmailRepo.save(cached);
            }
            return ResponseEntity.ok(detail);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // ── Force sync (pull-to-refresh or folder open) ─────────────────────────

    @PostMapping("/sync")
    public ResponseEntity<Map<String, String>> triggerSync(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID accountId,
            @RequestParam(defaultValue = "INBOX") String folder) {

        EmailAccount account = accountService.getAccountEntity(user.getUsername(), accountId);
        syncService.syncAccountNow(account, folder);
        return ResponseEntity.ok(Map.of("status", "synced", "folder", folder));
    }

    // ── Send ─────────────────────────────────────────────────────────────────

    @PostMapping("/emails/send")
    public ResponseEntity<Map<String, String>> sendEmail(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID accountId,
            @Valid @RequestBody SendEmailRequest request) {

        EmailAccount account = accountService.getAccountEntity(user.getUsername(), accountId);
        sendService.sendEmail(account, request);
        return ResponseEntity.ok(Map.of("status", "sent"));
    }

    // ── Actions — IMAP + write-through cache ─────────────────────────────────

    @PatchMapping("/emails/{uid}/read")
    @Transactional
    public ResponseEntity<Void> markRead(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID accountId,
            @PathVariable long uid,
            @RequestParam(defaultValue = "INBOX") String folder,
            @RequestParam(defaultValue = "true") boolean read) {

        EmailAccount account = accountService.getAccountEntity(user.getUsername(), accountId);
        if (read) actionService.markAsRead(account, folder, uid);
        else actionService.markAsUnread(account, folder, uid);
        // Write-through: reflect in cache immediately
        cachedEmailRepo.updateSeen(accountId, folder, uid, read);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/emails/{uid}/move")
    @Transactional
    public ResponseEntity<Void> moveEmail(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID accountId,
            @PathVariable long uid,
            @RequestParam String fromFolder,
            @RequestParam String toFolder) {

        EmailAccount account = accountService.getAccountEntity(user.getUsername(), accountId);
        actionService.moveEmail(account, fromFolder, toFolder, uid);
        // Write-through: remove from old folder cache row
        cachedEmailRepo.deleteByAccountIdAndFolderAndUid(accountId, fromFolder, uid);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/emails/{uid}")
    @Transactional
    public ResponseEntity<Void> deleteEmail(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID accountId,
            @PathVariable long uid,
            @RequestParam(defaultValue = "INBOX") String folder) {

        EmailAccount account = accountService.getAccountEntity(user.getUsername(), accountId);
        actionService.deleteEmail(account, folder, uid);
        // Write-through: remove from cache
        cachedEmailRepo.deleteByAccountIdAndFolderAndUid(accountId, folder, uid);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/emails/{uid}/archive")
    @Transactional
    public ResponseEntity<Void> archiveEmail(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID accountId,
            @PathVariable long uid,
            @RequestParam(defaultValue = "INBOX") String folder) {

        EmailAccount account = accountService.getAccountEntity(user.getUsername(), accountId);
        actionService.archiveEmail(account, folder, uid);
        // Write-through: remove from inbox cache
        cachedEmailRepo.deleteByAccountIdAndFolderAndUid(accountId, folder, uid);
        return ResponseEntity.noContent().build();
    }

    // ── DTO mappers ──────────────────────────────────────────────────────────

    private EmailSummaryDto toSummaryDto(CachedEmail e) {
        return new EmailSummaryDto(
                e.getUid(),
                e.getSubject(),
                e.getFromAddress(),
                e.getFromName(),
                e.getSnippet() != null ? e.getSnippet() : "",
                e.getReceivedAt(),
                e.isSeen(),
                e.isHasAttachment(),
                e.getFolder()
        );
    }

    private EmailDetailDto toDetailDto(CachedEmail e) {
        return new EmailDetailDto(
                e.getUid(),
                e.getSubject(),
                e.getFromAddress(),
                e.getFromName(),
                parseAddressList(e.getToAddresses()),
                parseAddressList(e.getCcAddresses()),
                e.getBodyHtml(),
                e.getBodyText(),
                e.getReceivedAt(),
                e.isSeen(),
                List.of(),
                e.getFolder()
        );
    }

    /** Parse semicolon-delimited address string back into a list. */
    private List<String> parseAddressList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.asList(raw.split(";"));
    }
}
