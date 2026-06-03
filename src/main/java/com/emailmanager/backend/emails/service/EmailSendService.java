package com.emailmanager.backend.emails.service;

import com.emailmanager.backend.accounts.entity.EmailAccount;
import com.emailmanager.backend.accounts.service.EncryptionService;
import com.emailmanager.backend.config.exception.AccountConnectionException;
import com.emailmanager.backend.emails.dto.SendEmailRequest;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Properties;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailSendService {

    private final EncryptionService encryptionService;

    public void sendEmail(EmailAccount account, SendEmailRequest request) {
        try {
            String password = encryptionService.decrypt(account.getEncryptedPassword());

            Properties props = new Properties();
            props.put("mail.smtp.host", account.getSmtpHost());
            props.put("mail.smtp.port", account.getSmtpPort());
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
            props.put("mail.smtp.connectiontimeout", "10000");
            props.put("mail.smtp.timeout", "10000");

            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(account.getUsername(), password);
                }
            });

            MimeMessage message = new MimeMessage(session);
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

            // Prefer HTML body, fallback to text
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

            Transport.send(message);
            log.info("Email sent from {} to {}", account.getEmailAddress(), request.to());

        } catch (Exception e) {
            log.error("Failed to send email from {}: {}", account.getEmailAddress(), e.getMessage());
            throw new AccountConnectionException("Failed to send email: " + e.getMessage(), e);
        }
    }
}
