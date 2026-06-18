package com.emailmanager.backend.cache.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "cached_email",
       uniqueConstraints = @UniqueConstraint(columnNames = {"account_id", "folder", "uid"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CachedEmail {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(nullable = false, length = 512)
    private String folder;

    /** IMAP UID — stable within a UIDVALIDITY epoch */
    @Column(nullable = false)
    private long uid;

    /** RFC Message-ID header for cross-folder dedup */
    @Column(name = "message_id", length = 512)
    private String messageId;

    @Column(columnDefinition = "TEXT")
    private String subject;

    @Column(name = "from_address", length = 512)
    private String fromAddress;

    @Column(name = "from_name", length = 512)
    private String fromName;

    @Column(columnDefinition = "TEXT")
    private String snippet;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean seen = false;

    @Column(name = "has_attachment", nullable = false)
    @Builder.Default
    private boolean hasAttachment = false;

    /**
     * Semicolon-separated list of "Name <addr>" strings for To recipients.
     * Populated during sync so detail view never needs an IMAP round-trip.
     */
    @Column(name = "to_addresses", columnDefinition = "TEXT")
    private String toAddresses;

    @Column(name = "cc_addresses", columnDefinition = "TEXT")
    private String ccAddresses;

    /** Lazy-loaded on first open via GET /emails/{uid} */
    @Column(name = "body_text", columnDefinition = "TEXT")
    private String bodyText;

    @Column(name = "body_html", columnDefinition = "TEXT")
    private String bodyHtml;

    /** Semicolon-separated attachment filenames, populated during lazy body fetch */
    @Column(name = "attachment_names", columnDefinition = "TEXT")
    private String attachmentNames;

    @Column(name = "body_loaded", nullable = false)
    @Builder.Default
    private boolean bodyLoaded = false;
}
