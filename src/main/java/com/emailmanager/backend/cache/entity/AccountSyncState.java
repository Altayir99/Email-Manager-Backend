package com.emailmanager.backend.cache.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "account_sync_state")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccountSyncState {

    @Id
    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "last_full_sync_at")
    private LocalDateTime lastFullSyncAt;

    /**
     * Persistent high-water mark for push notifications.
     * Only UIDs above this value trigger an FCM push.
     * Stored in Postgres so it survives backend restarts/redeploys —
     * fixes the "missed/duplicate notification on restart" bug.
     */
    @Column(name = "last_notified_uid", nullable = false)
    @Builder.Default
    private long lastNotifiedUid = 0L;

    /** IDLE | SYNCING | ERROR */
    @Column(name = "sync_status", nullable = false, length = 32)
    @Builder.Default
    private String syncStatus = "IDLE";

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;
}
