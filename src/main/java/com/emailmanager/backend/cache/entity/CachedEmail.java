package com.emailmanager.backend.cache.entity;

import com.emailmanager.backend.common.TextSanitizer;
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

    /** RFC 5322 In-Reply-To header — the Message-ID this message replies to. */
    @Column(name = "in_reply_to", length = 512)
    private String inReplyTo;

    /** RFC 5322 References header — the full space-separated Message-ID chain. */
    @Column(name = "msg_references", columnDefinition = "TEXT")
    private String references;

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

    /**
     * Strip NUL bytes from every text field before insert/update — Postgres
     * rejects 0x00 in text/varchar (SQLState 22021). Covers ALL save() paths
     * (sync, lazy body load, self-healing snippet, send write-through) and any
     * future String field automatically.
     */
    @PrePersist
    @PreUpdate
    private void sanitizeText() {
        folder          = TextSanitizer.clean(folder);
        messageId       = TextSanitizer.clean(messageId);
        inReplyTo       = TextSanitizer.clean(inReplyTo);
        references      = TextSanitizer.clean(references);
        subject         = TextSanitizer.clean(subject);
        fromAddress     = TextSanitizer.clean(fromAddress);
        fromName        = TextSanitizer.clean(fromName);
        snippet         = TextSanitizer.clean(snippet);
        toAddresses     = TextSanitizer.clean(toAddresses);
        ccAddresses     = TextSanitizer.clean(ccAddresses);
        bodyText        = TextSanitizer.clean(bodyText);
        bodyHtml        = TextSanitizer.clean(bodyHtml);
        attachmentNames = TextSanitizer.clean(attachmentNames);
    }
}
