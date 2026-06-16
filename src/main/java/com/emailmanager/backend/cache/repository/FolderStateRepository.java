package com.emailmanager.backend.cache.repository;

import com.emailmanager.backend.cache.entity.FolderState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FolderStateRepository extends JpaRepository<FolderState, UUID> {

    List<FolderState> findByAccountIdOrderByFullNameAsc(UUID accountId);

    Optional<FolderState> findByAccountIdAndFullName(UUID accountId, String fullName);

    void deleteByAccountId(UUID accountId);
}
