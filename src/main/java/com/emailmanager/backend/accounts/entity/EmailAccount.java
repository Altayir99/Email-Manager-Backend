package com.emailmanager.backend.accounts.entity;

import com.emailmanager.backend.user.User;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "email_accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String emailAddress;

    @Column(nullable = false)
    private String displayName;

    /** Hex color for account badge in UI, e.g. "#6366F1" */
    @Column(nullable = false)
    @Builder.Default
    private String color = "#6366F1";

    // IMAP settings
    @Column(nullable = false)
    @Builder.Default
    private String imapHost = "imap.gmail.com";

    @Column(nullable = false)
    @Builder.Default
    private Integer imapPort = 993;

    // SMTP settings
    @Column(nullable = false)
    @Builder.Default
    private String smtpHost = "smtp.gmail.com";

    @Column(nullable = false)
    @Builder.Default
    private Integer smtpPort = 587;

    /** IMAP/SMTP username — usually same as emailAddress */
    @Column(nullable = false)
    private String username;

    /** AES-256 encrypted App Password */
    @Column(nullable = false)
    private String encryptedPassword;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column
    private LocalDateTime lastSyncedAt;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
