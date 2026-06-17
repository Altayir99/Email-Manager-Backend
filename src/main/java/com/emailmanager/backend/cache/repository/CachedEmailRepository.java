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

    /**
     * Load the top N UIDs for a folder (most recent first).
     * Used for flag reconciliation and deletion detection.
     */
    @Query("SELECT e.uid FROM CachedEmail e WHERE e.accountId = :accountId AND e.folder = :folder ORDER BY e.uid DESC")
    List<Long> findUidsByAccountIdAndFolder(
            @Param("accountId") UUID accountId,
            @Param("folder") String folder,
            Pageable pageable);

    /** Optimistic write-through: mark seen after user opens or marks read. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE CachedEmail e SET e.seen = :seen WHERE e.accountId = :accountId AND e.folder = :folder AND e.uid = :uid")
    int updateSeen(@Param("accountId") UUID accountId,
                   @Param("folder") String folder,
                   @Param("uid") long uid,
                   @Param("seen") boolean seen);

    /** Bulk flag update for flag-reconciliation pass (update multiple UIDs at once). */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE CachedEmail e SET e.seen = true WHERE e.accountId = :accountId AND e.folder = :folder AND e.uid IN :uids")
    int markSeenBulk(@Param("accountId") UUID accountId,
                     @Param("folder") String folder,
                     @Param("uids") List<Long> uids);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE CachedEmail e SET e.seen = false WHERE e.accountId = :accountId AND e.folder = :folder AND e.uid IN :uids")
    int markUnseenBulk(@Param("accountId") UUID accountId,
                       @Param("folder") String folder,
                       @Param("uids") List<Long> uids);

    /** Optimistic write-through: remove single row on delete/move. */
    @Modifying
    @Query("DELETE FROM CachedEmail e WHERE e.accountId = :accountId AND e.folder = :folder AND e.uid = :uid")
    int deleteByAccountIdAndFolderAndUid(
            @Param("accountId") UUID accountId, @Param("folder") String folder, @Param("uid") long uid);

    /** Bulk evict UIDs that no longer exist on the server (deletion detection). */
    @Modifying
    @Query("DELETE FROM CachedEmail e WHERE e.accountId = :accountId AND e.folder = :folder AND e.uid IN :uids")
    int deleteByAccountIdAndFolderAndUidIn(
            @Param("accountId") UUID accountId,
            @Param("folder") String folder,
            @Param("uids") List<Long> uids);

    /** Wipe all cached emails for a folder when UIDVALIDITY changes. */
    @Modifying
    @Query("DELETE FROM CachedEmail e WHERE e.accountId = :accountId AND e.folder = :folder")
    void deleteByAccountIdAndFolder(
            @Param("accountId") UUID accountId, @Param("folder") String folder);

    /** High-water mark for incremental sync. */
    @Query("SELECT MAX(e.uid) FROM CachedEmail e WHERE e.accountId = :accountId AND e.folder = :folder")
    Optional<Long> findMaxUidByAccountIdAndFolder(
            @Param("accountId") UUID accountId, @Param("folder") String folder);

    @Query("SELECT e.uid FROM CachedEmail e WHERE e.accountId = :accountId AND e.folder = :folder AND e.uid >= :uidFrom AND e.uid <= :uidTo")
    List<Long> findUidsByAccountIdAndFolderInRange(
            @Param("accountId") UUID accountId,
            @Param("folder") String folder,
            @Param("uidFrom") long uidFrom,
            @Param("uidTo") long uidTo);

    /** Full deletion reconciliation: load ALL cached UIDs for a folder (batched by caller). */
    @Query("SELECT e.uid FROM CachedEmail e WHERE e.accountId = :accountId AND e.folder = :folder ORDER BY e.uid ASC")
    List<Long> findAllUidsByAccountIdAndFolder(
            @Param("accountId") UUID accountId,
            @Param("folder") String folder);

    /** Full-text search across subject, sender, snippet. */
    @Query("SELECT e FROM CachedEmail e WHERE e.accountId = :accountId AND e.folder = :folder " +
           "AND (LOWER(e.subject) LIKE LOWER(CONCAT('%', :q, '%')) " +
           "OR LOWER(e.fromAddress) LIKE LOWER(CONCAT('%', :q, '%')) " +
           "OR LOWER(e.fromName) LIKE LOWER(CONCAT('%', :q, '%')) " +
           "OR LOWER(e.snippet) LIKE LOWER(CONCAT('%', :q, '%'))) " +
           "ORDER BY e.receivedAt DESC")
    Page<CachedEmail> searchByAccountIdAndFolder(
            @Param("accountId") UUID accountId,
            @Param("folder") String folder,
            @Param("q") String query,
            Pageable pageable);

