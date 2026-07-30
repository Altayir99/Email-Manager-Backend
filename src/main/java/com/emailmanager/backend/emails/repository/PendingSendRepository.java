package com.emailmanager.backend.emails.repository;

import com.emailmanager.backend.emails.entity.PendingSend;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface PendingSendRepository extends JpaRepository<PendingSend, UUID> {

    List<PendingSend> findByStatus(String status);

    /**
     * Atomically claim a pending send for delivery: PENDING → SENT.
     * Returns 1 if this caller won the claim, 0 if it was already sent or
     * cancelled — the single source of truth that prevents double delivery
     * (in-memory schedule vs. startup recovery).
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE PendingSend p SET p.status = 'SENT' WHERE p.id = :id AND p.status = 'PENDING'")
    int markSentIfPending(@Param("id") UUID id);

    /** Cancel a pending send if it has not been claimed yet: PENDING → CANCELLED. */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE PendingSend p SET p.status = 'CANCELLED' WHERE p.id = :id AND p.status = 'PENDING'")
    int markCancelledIfPending(@Param("id") UUID id);
}
