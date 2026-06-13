package com.emailmanager.backend.emails.controller;

import com.emailmanager.backend.accounts.entity.EmailAccount;
import com.emailmanager.backend.accounts.service.EmailAccountService;
import com.emailmanager.backend.emails.dto.*;
import com.emailmanager.backend.emails.service.EmailActionService;
import com.emailmanager.backend.emails.service.EmailFetchService;
import com.emailmanager.backend.emails.service.EmailSendService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts/{accountId}")
@RequiredArgsConstructor
public class EmailController {

    private final EmailAccountService accountService;
    private final EmailFetchService fetchService;
    private final EmailSendService sendService;
    private final EmailActionService actionService;

    // ---- Folders ----

    @GetMapping("/folders")
    public ResponseEntity<List<FolderDto>> getFolders(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID accountId) throws Exception {

        EmailAccount account = accountService.getAccountEntity(user.getUsername(), accountId);
        return ResponseEntity.ok(fetchService.listFolders(account));
    }

    // ---- Email List ----

    @GetMapping("/emails")
    public ResponseEntity<EmailPageDto> getEmails(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID accountId,
            @RequestParam(defaultValue = "INBOX") String folder,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize) throws Exception {

        EmailAccount account = accountService.getAccountEntity(user.getUsername(), accountId);
        return ResponseEntity.ok(fetchService.fetchEmails(account, folder, page, pageSize));
    }

    // ---- Email Detail ----

    @GetMapping("/emails/{uid}")
    public ResponseEntity<EmailDetailDto> getEmailDetail(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID accountId,
            @PathVariable long uid,
            @RequestParam(defaultValue = "INBOX") String folder) throws Exception {

        EmailAccount account = accountService.getAccountEntity(user.getUsername(), accountId);
        return ResponseEntity.ok(fetchService.fetchEmailDetail(account, folder, uid));
    }

    // ---- Send ----

    @PostMapping("/emails/send")
    public ResponseEntity<Map<String, String>> sendEmail(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID accountId,
            @Valid @RequestBody SendEmailRequest request) {

        EmailAccount account = accountService.getAccountEntity(user.getUsername(), accountId);
        sendService.sendEmail(account, request);
        return ResponseEntity.ok(Map.of("status", "sent"));
    }

    // ---- Actions ----

    @PatchMapping("/emails/{uid}/read")
    public ResponseEntity<Void> markRead(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID accountId,
            @PathVariable long uid,
            @RequestParam(defaultValue = "INBOX") String folder,
            @RequestParam(defaultValue = "true") boolean read) {

        EmailAccount account = accountService.getAccountEntity(user.getUsername(), accountId);
        if (read) actionService.markAsRead(account, folder, uid);
        else actionService.markAsUnread(account, folder, uid);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/emails/{uid}/move")
    public ResponseEntity<Void> moveEmail(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID accountId,
            @PathVariable long uid,
            @RequestParam String fromFolder,
            @RequestParam String toFolder) {

        EmailAccount account = accountService.getAccountEntity(user.getUsername(), accountId);
        actionService.moveEmail(account, fromFolder, toFolder, uid);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/emails/{uid}")
    public ResponseEntity<Void> deleteEmail(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID accountId,
            @PathVariable long uid,
            @RequestParam(defaultValue = "INBOX") String folder) {

        EmailAccount account = accountService.getAccountEntity(user.getUsername(), accountId);
        actionService.deleteEmail(account, folder, uid);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/emails/{uid}/archive")
    public ResponseEntity<Void> archiveEmail(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID accountId,
            @PathVariable long uid,
            @RequestParam(defaultValue = "INBOX") String folder) {

        EmailAccount account = accountService.getAccountEntity(user.getUsername(), accountId);
        actionService.archiveEmail(account, folder, uid);
        return ResponseEntity.noContent().build();
    }
}
