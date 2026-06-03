package com.emailmanager.backend.accounts.service;

import com.emailmanager.backend.accounts.dto.AccountResponse;
import com.emailmanager.backend.accounts.dto.AddAccountRequest;
import com.emailmanager.backend.accounts.entity.EmailAccount;
import com.emailmanager.backend.accounts.repository.EmailAccountRepository;
import com.emailmanager.backend.config.exception.ResourceNotFoundException;
import com.emailmanager.backend.user.User;
import com.emailmanager.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailAccountService {

    private final EmailAccountRepository accountRepository;
    private final UserRepository userRepository;
    private final EncryptionService encryptionService;
    private final ImapConnectionService imapConnectionService;

    public List<AccountResponse> getAccounts(String username) {
        return accountRepository.findByUserUsernameOrderByCreatedAtAsc(username)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public AccountResponse addAccount(String username, AddAccountRequest request) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        if (accountRepository.existsByEmailAddressAndUserUsername(request.emailAddress(), username)) {
            throw new IllegalArgumentException("Account already added: " + request.emailAddress());
        }

        EmailAccount account = EmailAccount.builder()
                .user(user)
                .emailAddress(request.emailAddress())
                .displayName(request.displayName())
                .color(request.color() != null ? request.color() : "#6366F1")
                .imapHost(request.imapHost() != null ? request.imapHost() : "imap.gmail.com")
                .imapPort(request.imapPort() != null ? request.imapPort() : 993)
                .smtpHost(request.smtpHost() != null ? request.smtpHost() : "smtp.gmail.com")
                .smtpPort(request.smtpPort() != null ? request.smtpPort() : 587)
                .username(request.emailAddress())
                .encryptedPassword(encryptionService.encrypt(request.appPassword()))
                .build();

        // Test connection before saving
        imapConnectionService.testConnection(account);

        accountRepository.save(account);
        log.info("Account added: {} for user {}", request.emailAddress(), username);
        return toResponse(account);
    }

    @Transactional
    public void deleteAccount(String username, UUID accountId) {
        EmailAccount account = accountRepository.findByIdAndUserUsername(accountId, username)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountId));
        imapConnectionService.disconnect(accountId);
        accountRepository.delete(account);
        log.info("Account deleted: {}", accountId);
    }

    public EmailAccount getAccountEntity(String username, UUID accountId) {
        return accountRepository.findByIdAndUserUsername(accountId, username)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountId));
    }

    public void testConnection(String username, UUID accountId) {
        EmailAccount account = getAccountEntity(username, accountId);
        imapConnectionService.testConnection(account);
    }

    private AccountResponse toResponse(EmailAccount a) {
        return new AccountResponse(
                a.getId(),
                a.getEmailAddress(),
                a.getDisplayName(),
                a.getColor(),
                a.getImapHost(),
                a.getImapPort(),
                a.getSmtpHost(),
                a.getSmtpPort(),
                a.isActive(),
                a.getLastSyncedAt(),
                a.getCreatedAt()
        );
    }
}
