package com.emailmanager.backend.accounts.controller;

import com.emailmanager.backend.accounts.dto.AccountResponse;
import com.emailmanager.backend.accounts.dto.AddAccountRequest;
import com.emailmanager.backend.accounts.entity.EmailAccount;
import com.emailmanager.backend.accounts.repository.EmailAccountRepository;
import com.emailmanager.backend.accounts.service.EmailAccountService;
import com.emailmanager.backend.accounts.service.ImapConnectionService;
import com.emailmanager.backend.cache.entity.FolderState;
import com.emailmanager.backend.cache.repository.FolderStateRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

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
    private final FolderStateRepository  folderStateRepository;

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
     * Returns folders for an account from the folder_state table.
     * Returns EmailFolder-compatible JSON: {name, fullName, unreadCount, totalCount, hasChildren}.
     */
    @GetMapping("/{id}/folders")
    public ResponseEntity<List<Map<String, Object>>> listFolders(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID id) {
        List<FolderState> states = folderStateRepository.findByAccountIdOrderByFullNameAsc(id);
        List<Map<String, Object>> result = states.stream()
                .map(fs -> Map.<String, Object>of(
                        "fullName",    fs.getFullName(),
                        "name",        fs.getDisplayName(),
                        "unreadCount", fs.getUnreadCount(),
                        "totalCount",  fs.getTotalCount(),
                        "hasChildren", false
                ))
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }
}
