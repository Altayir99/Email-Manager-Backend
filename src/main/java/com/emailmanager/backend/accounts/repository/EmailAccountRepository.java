package com.emailmanager.backend.accounts.repository;

import com.emailmanager.backend.accounts.entity.EmailAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmailAccountRepository extends JpaRepository<EmailAccount, UUID> {
    List<EmailAccount> findByUserUsernameOrderByCreatedAtAsc(String username);
    Optional<EmailAccount> findByIdAndUserUsername(UUID id, String username);
    boolean existsByEmailAddressAndUserUsername(String emailAddress, String username);
}
