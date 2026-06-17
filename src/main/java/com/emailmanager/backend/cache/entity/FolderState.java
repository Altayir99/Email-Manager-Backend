package com.emailmanager.backend.cache.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "folder_state",
       uniqueConstraints = @UniqueConstraint(columnNames = {"account_id", "full_name"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FolderState {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    /** IMAP full folder name, e.g. "[Gmail]/Sent Mail" */
    @Column(name = "full_name", nullable = false, length = 512)
    private String fullName;

    /** Display-friendly short name, e.g. "Sent Mail" */
    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    /** IMAP UIDVALIDITY — changes when the server resets its UID sequence */
    @Column(name = "uid_validity")
    private Long uidValidity;

    /** High-water mark: highest UID seen in this folder during sync */
    @Column(name = "last_seen_uid", nullable = false)
    @Builder.Default
    private long lastSeenUid = 0L;

    @Column(name = "unread_count", nullable = false)
    @Builder.Default
    private int unreadCount = 0;

    @Column(name = "total_count", nullable = false)
    @Builder.Default
    private int totalCount = 0;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    /** Timestamp of last flag-reconciliation pass. Used to throttle to once per 5 min. */
    @Column(name = "last_flag_reconcile_at")
    private LocalDateTime lastFlagReconcileAt;
}
