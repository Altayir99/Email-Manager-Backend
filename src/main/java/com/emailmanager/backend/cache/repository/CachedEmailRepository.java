package com.emailmanager.backend.cache.repository;

import com.emailmanager.backend.cache.entity.CachedEmail;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CachedEmailRepository extends JpaRepository<CachedEmail, UUID> {

    /** Paginated list — served directly to Flutter client (no IMAP). */
    Page<CachedEmail> findByAccountIdAndFolderOrderByReceivedAtDesc(
            UUID accountId, String folder, Pageable pageable);

    Optional<CachedEmail> findByAccountIdAndFolderAndUid(
            UUID accountId, String folder, long uid);

    int countByAccountIdAndFolderAndSeenFalse(UUID accountId, String folder);

    /** Used for flag reconciliation: load all cached UIDs for a folder window. */
    @Query("SELECT e.uid FROM CachedEmail e WHERE e.accountId = :accountId AND e.folder = :folder ORDER BY e.uid DESC")
    List<Long> findUidsByAccountIdAndFolder(
            @Param("accountId") UUID accountId, @Param("folder") String folder);

    /** Optimistic write-through: mark seen after user opens or marks read. */
    @Modifying
    @Query("UPDATE CachedEmail e SET e.seen = :seen WHERE e.accountId = :accountId AND e.folder = :folder AND e.uid = :uid")
    int updateSeen(@Param("accountId") UUID accountId,
                   @Param("folder") String folder,
                   @Param("uid") long uid,
                   @Param("seen") boolean seen);

    /** Optimistic write-through: remove row on delete/move. */
    @Modifying
    @Query("DELETE FROM CachedEmail e WHERE e.accountId = :accountId AND e.folder = :folder AND e.uid = :uid")
    int deleteByAccountIdAndFolderAndUid(
            @Param("accountId") UUID accountId, @Param("folder") String folder, @Param("uid") long uid);

    /** Wipe all cached emails for a folder when UIDVALIDITY changes. */
    @Modifying
    @Query("DELETE FROM CachedEmail e WHERE e.accountId = :accountId AND e.folder = :folder")
    void deleteByAccountIdAndFolder(
            @Param("accountId") UUID accountId, @Param("folder") String folder);

    /** Count for sync high-water mark calculation. */
    @Query("SELECT MAX(e.uid) FROM CachedEmail e WHERE e.accountId = :accountId AND e.folder = :folder")
    Optional<Long> findMaxUidByAccountIdAndFolder(
            @Param("accountId") UUID accountId, @Param("folder") String folder);
}
