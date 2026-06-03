package com.emailmanager.backend.accounts.controller;

import com.emailmanager.backend.accounts.dto.AccountResponse;
import com.emailmanager.backend.accounts.dto.AddAccountRequest;
import com.emailmanager.backend.accounts.service.EmailAccountService;
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

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class EmailAccountController {

    private final EmailAccountService accountService;

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
}
