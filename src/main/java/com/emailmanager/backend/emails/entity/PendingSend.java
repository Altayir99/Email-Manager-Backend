package com.emailmanager.backend.emails.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A queued undo-send, persisted so a server restart inside the delay window
 * does not lose the mail. Recovery re-schedules future rows and immediately
 * delivers past-due ones (idempotently — see {@code status}).
 */
@Entity
@Table(name = "pending_send")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PendingSend {

    public static final String PENDING   = "PENDING";
    public static final String SENT      = "SENT";
    public static final String CANCELLED = "CANCELLED";

    /** Assigned by the service (UUID.randomUUID) so it is known before persist. */
    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "to_address", nullable = false, length = 512)
    private String toAddress;

    /** Semicolon-joined CC / BCC recipients (null when none). */
    @Column(name = "cc_addresses", columnDefinition = "TEXT")
    private String ccAddresses;

    @Column(name = "bcc_addresses", columnDefinition = "TEXT")
    private String bccAddresses;

    @Column(columnDefinition = "TEXT")
    private String subject;

    @Column(name = "body_text", columnDefinition = "TEXT")
    private String bodyText;

    @Column(name = "body_html", columnDefinition = "TEXT")
    private String bodyHtml;

    /** Message-ID this send replies to (nullable) — threads the sent mail. */
    @Column(name = "in_reply_to", length = 512)
    private String inReplyTo;

    /**
     * Attachment bytes, eagerly copied at queue time (null when no attachment).
     * Plain byte[] (no @Lob) so Hibernate maps it to Postgres {@code bytea},
     * matching the V4 migration (a @Lob byte[] would map to a large-object OID).
     */
    @Column(name = "attachment_bytes")
    private byte[] attachmentBytes;

    @Column(name = "attachment_filename", length = 512)
    private String attachmentFilename;

    @Column(name = "attachment_content_type", length = 255)
    private String attachmentContentType;

    @Column(name = "send_at", nullable = false)
    private LocalDateTime sendAt;

    /** PENDING → SENT | CANCELLED. */
    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = PENDING;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
