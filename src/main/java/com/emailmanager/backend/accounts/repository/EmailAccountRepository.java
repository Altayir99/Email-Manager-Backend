package com.emailmanager.backend.accounts.repository;

import com.emailmanager.backend.accounts.entity.EmailAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmailAccountRepository extends JpaRepository<EmailAccount, UUID> {
    List<EmailAccount> findByUserUsernameOrderByCreatedAtAsc(String username);
    Optional<EmailAccount> findByIdAndUserUsername(UUID id, String username);
    boolean existsByEmailAddressAndUserUsername(String emailAddress, String username);

    /** Eagerly join User so polling scheduler can read fcmToken without LazyInit. */
    @Query("SELECT a FROM EmailAccount a JOIN FETCH a.user WHERE a.active = true")
    List<EmailAccount> findAllWithUser();
}
