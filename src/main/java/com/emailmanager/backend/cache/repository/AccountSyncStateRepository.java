package com.emailmanager.backend.cache.repository;

import com.emailmanager.backend.cache.entity.AccountSyncState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AccountSyncStateRepository extends JpaRepository<AccountSyncState, UUID> {
    // findById(accountId) covers all needed lookups
}
