package com.emailmanager.backend.emails.service;

import com.emailmanager.backend.accounts.entity.EmailAccount;
import com.emailmanager.backend.accounts.service.EncryptionService;
import com.emailmanager.backend.cache.entity.CachedEmail;
import com.emailmanager.backend.cache.repository.CachedEmailRepository;
import com.emailmanager.backend.config.exception.AccountConnectionException;
import com.emailmanager.backend.emails.dto.SendEmailRequest;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Properties;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailSendService {

    private final EncryptionService      encryptionService;
    private final CachedEmailRepository  cachedEmailRepository;

    // Gmail stores sent mail here; generic IMAP uses "Sent"
    private static final String GMAIL_SENT_FOLDER   = "[Gmail]/Sent Mail";
    private static final String GENERIC_SENT_FOLDER = "Sent";

    /**
     * Overload without attachment — kept for internal calls that don't need a file.
     */
    public void sendEmail(EmailAccount account, SendEmailRequest request) {
        sendEmail(account, request, null);
    }

    /**
     * Send an email with an optional PDF (or any file) attachment.
     *
     * @param attachment nullable — when null, behaves identically to the old code
     */
    public void sendEmail(EmailAccount account, SendEmailRequest request, MultipartFile attachment) {
        MimeMessage message;
        Session session;

        try {
            String password = encryptionService.decrypt(account.getEncryptedPassword());

            Properties props = new Properties();
            props.put("mail.smtp.host",              account.getSmtpHost());
            props.put("mail.smtp.port",              account.getSmtpPort());
            props.put("mail.smtp.auth",              "true");
            props.put("mail.smtp.starttls.enable",   "true");
            props.put("mail.smtp.starttls.required", "true");
            props.put("mail.smtp.connectiontimeout", "10000");
            props.put("mail.smtp.timeout",           "10000");

            session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(account.getUsername(), password);
                }
            });

            message = new MimeMessage(session);
            message.setFrom(new InternetAddress(account.getEmailAddress(), account.getDisplayName()));
            message.addRecipient(Message.RecipientType.TO, new InternetAddress(request.to()));

            if (request.cc() != null) {
                for (String cc : request.cc()) {
                    message.addRecipient(Message.RecipientType.CC, new InternetAddress(cc));
                }
            }
            if (request.bcc() != null) {
                for (String bcc : request.bcc()) {
                    message.addRecipient(Message.RecipientType.BCC, new InternetAddress(bcc));
                }
            }

            message.setSubject(request.subject(), "UTF-8");
            message.setSentDate(new Date());

            // ── Build body part ───────────────────────────────────────────────
            MimeBodyPart bodyPart = new MimeBodyPart();
            if (request.bodyHtml() != null && !request.bodyHtml().isBlank()) {
                if (request.bodyText() != null && !request.bodyText().isBlank()) {
                    // alternative: text + html
                    MimeMultipart altMultipart = new MimeMultipart("alternative");
                    MimeBodyPart textPart = new MimeBodyPart();
                    textPart.setText(request.bodyText(), "UTF-8");
                    altMultipart.addBodyPart(textPart);
                    MimeBodyPart htmlPart = new MimeBodyPart();
                    htmlPart.setContent(request.bodyHtml(), "text/html; charset=UTF-8");
                    altMultipart.addBodyPart(htmlPart);
                    bodyPart.setContent(altMultipart);
                } else {
                    bodyPart.setContent(request.bodyHtml(), "text/html; charset=UTF-8");
                }
            } else {
                bodyPart.setText(request.bodyText() != null ? request.bodyText() : "", "UTF-8");
            }

            // ── Attach file if provided ────────────────────────────────────────
            if (attachment != null && !attachment.isEmpty()) {
                MimeMultipart mixedMultipart = new MimeMultipart("mixed");
                mixedMultipart.addBodyPart(bodyPart);

                MimeBodyPart attachPart = new MimeBodyPart();
                String filename = attachment.getOriginalFilename() != null
                        ? attachment.getOriginalFilename() : "attachment";
                attachPart.setFileName(MimeUtility.encodeText(filename, "UTF-8", "B"));
                attachPart.setContent(attachment.getBytes(),
                        attachment.getContentType() != null
                                ? attachment.getContentType()
                                : "application/octet-stream");
                mixedMultipart.addBodyPart(attachPart);
                message.setContent(mixedMultipart);

                log.info("Attaching '{}' ({} bytes) to email from {}",
                        filename, attachment.getSize(), account.getEmailAddress());
            } else {
                // No attachment — set body directly
                if (request.bodyHtml() != null && !request.bodyHtml().isBlank()) {
                    MimeMultipart multipart = new MimeMultipart("alternative");
                    if (request.bodyText() != null && !request.bodyText().isBlank()) {
                        MimeBodyPart textPart = new MimeBodyPart();
                        textPart.setText(request.bodyText(), "UTF-8");
                        multipart.addBodyPart(textPart);
                    }
                    MimeBodyPart htmlPart = new MimeBodyPart();
                    htmlPart.setContent(request.bodyHtml(), "text/html; charset=UTF-8");
                    multipart.addBodyPart(htmlPart);
                    message.setContent(multipart);
                } else {
                    message.setText(request.bodyText() != null ? request.bodyText() : "", "UTF-8");
                }
            }

            Transport.send(message);
            log.info("Email sent from {} to {}", account.getEmailAddress(), request.to());

        } catch (Exception e) {
            log.error("Failed to send email from {}: {}", account.getEmailAddress(), e.getMessage());
            throw new AccountConnectionException("Failed to send email: " + e.getMessage(), e);
        }

        // ── Task 1: Cache the sent email immediately ──────────────────────────
        // Send succeeded — write to local cache so Sent folder is instant.
        // Failure here must NOT bubble up (send already succeeded).
        try {
            String sentFolder = account.getSmtpHost().contains("gmail")
                    ? GMAIL_SENT_FOLDER : GENERIC_SENT_FOLDER;

            // Use a synthetic negative UID so it doesn't clash with IMAP UIDs.
            // The next incremental sync will overwrite with the real UID.
            long syntheticUid = -System.currentTimeMillis();

            String snippet = request.bodyText() != null && !request.bodyText().isBlank()
                    ? request.bodyText().substring(0, Math.min(200, request.bodyText().length()))
                    : (request.bodyHtml() != null
                        ? request.bodyHtml().replaceAll("<[^>]+>", "").substring(
                            0, Math.min(200, request.bodyHtml().replaceAll("<[^>]+>", "").length()))
                        : "");

            boolean hasAttach = attachment != null && !attachment.isEmpty();
            String attachmentName = hasAttach
                    ? (attachment.getOriginalFilename() != null
                            ? attachment.getOriginalFilename() : "attachment.pdf")
                    : null;

            CachedEmail sent = CachedEmail.builder()
                    .accountId(account.getId())
                    .folder(sentFolder)
                    .uid(syntheticUid)
                    .subject(request.subject())
                    .fromAddress(account.getEmailAddress())
                    .fromName(account.getDisplayName() != null ? account.getDisplayName() : account.getEmailAddress())
                    .toAddresses(request.to())
                    .ccAddresses(request.cc() != null ? String.join(";", request.cc()) : null)
                    .snippet(snippet)
                    .bodyText(request.bodyText())
                    .bodyHtml(request.bodyHtml())
                    .bodyLoaded(true)
                    .seen(true)           // sent mail is always "read"
                    .hasAttachment(hasAttach)
                    .attachmentNames(attachmentName)
                    .receivedAt(LocalDateTime.now(ZoneOffset.UTC))
                    .build();

            cachedEmailRepository.save(sent);
            log.debug("Cached sent email to {} in folder '{}'", request.to(), sentFolder);

        } catch (Exception cacheEx) {
            // Non-fatal — the email was already sent successfully
            log.warn("Failed to cache sent email (non-fatal): {}", cacheEx.getMessage());
        }
    }
}
