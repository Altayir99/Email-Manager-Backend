package com.emailmanager.backend.accounts.controller;

import com.emailmanager.backend.accounts.dto.AccountResponse;
import com.emailmanager.backend.accounts.dto.AddAccountRequest;
import com.emailmanager.backend.accounts.entity.EmailAccount;
import com.emailmanager.backend.accounts.repository.EmailAccountRepository;
import com.emailmanager.backend.accounts.service.EmailAccountService;
import com.emailmanager.backend.accounts.service.ImapConnectionService;
import jakarta.mail.Folder;
import jakarta.mail.Store;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class EmailAccountController {

    private final EmailAccountService    accountService;
    private final EmailAccountRepository accountRepository;
    private final ImapConnectionService  imapConnectionService;

    @GetMapping
    public ResponseEntity<List<AccountResponse>> getAccounts(
            @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(accountService.getAccounts(user.getUsername()));
    }

    @PostMapping
    public ResponseEntity<AccountResponse> addAccount(
            @AuthenticationPrincipal UserDetails user,
            @Valid @RequestBody AddAccountRequest request) {
        AccountResponse response = accountService.addAccount(user.getUsername(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAccount(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID id) {
        accountService.deleteAccount(user.getUsername(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, String>> testConnection(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID id) {
        accountService.testConnection(user.getUsername(), id);
        return ResponseEntity.ok(Map.of("status", "ok", "message", "Connection successful"));
    }

    /**
     * Lists all IMAP folders available on the server for a given account.
     * Useful for diagnosing the correct folder names (e.g. "Sent" vs "Sent Items").
     */
    @GetMapping("/{id}/folders")
    public ResponseEntity<List<String>> listFolders(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID id) {
        EmailAccount account = accountRepository.findById(id).orElseThrow();
        Store store = imapConnectionService.acquireStore(account);
        try {
            Folder[] folders = store.getDefaultFolder().list("*");
            List<String> names = Arrays.stream(folders)
                    .map(Folder::getFullName)
                    .sorted()
                    .collect(Collectors.toList());
            return ResponseEntity.ok(names);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(List.of("Error: " + e.getMessage()));
        } finally {
            imapConnectionService.releaseStore(id);
        }
    }
}
