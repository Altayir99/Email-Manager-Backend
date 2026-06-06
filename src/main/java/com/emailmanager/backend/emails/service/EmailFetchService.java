package com.emailmanager.backend.emails.service;

import com.emailmanager.backend.accounts.entity.EmailAccount;
import com.emailmanager.backend.accounts.service.ImapConnectionService;
import com.emailmanager.backend.emails.dto.EmailDetailDto;
import com.emailmanager.backend.emails.dto.EmailSummaryDto;
import com.emailmanager.backend.emails.dto.FolderDto;
import com.sun.mail.imap.IMAPFolder;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMultipart;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailFetchService {

    private final ImapConnectionService imapConnectionService;

    /**
     * List IMAP folders for an account.
     */
    public List<FolderDto> listFolders(EmailAccount account) throws MessagingException {
        Store store = imapConnectionService.getStore(account);
        List<FolderDto> folders = new ArrayList<>();

        Folder[] folderList = store.getDefaultFolder().list("*");
        for (Folder folder : folderList) {
            // Skip non-mail-holding folders (e.g. [Gmail] parent container)
            if ((folder.getType() & Folder.HOLDS_MESSAGES) == 0) continue;
            folders.add(new FolderDto(
                    folder.getName(),
                    folder.getFullName(),
                    0, 0, false
            ));
        }
        return folders;
    }


    /**
     * Fetch paginated email list from a folder.
     * Returns newest first (reverse chronological).
     */
    public List<EmailSummaryDto> fetchEmails(EmailAccount account, String folderName, int page, int pageSize)
            throws MessagingException {

        Store store = imapConnectionService.getStore(account);
        Folder folder = store.getFolder(folderName);
        folder.open(Folder.READ_ONLY);

        int total = folder.getMessageCount();
        if (total == 0) {
            folder.close(false);
            return List.of();
        }

        // Calculate range (newest first = highest message numbers first)
        int end = total - (page * pageSize);
        int start = Math.max(1, end - pageSize + 1);
        if (end < 1) {
            folder.close(false);
            return List.of();
        }

        Message[] messages = folder.getMessages(start, end);
        // Fetch envelope data efficiently
        FetchProfile profile = new FetchProfile();
        profile.add(FetchProfile.Item.ENVELOPE);
        profile.add(FetchProfile.Item.FLAGS);
        folder.fetch(messages, profile);

        List<EmailSummaryDto> result = new ArrayList<>();
        // Reverse so newest first
        for (int i = messages.length - 1; i >= 0; i--) {
            Message msg = messages[i];
            try {
                result.add(toSummary(msg, folderName));
            } catch (Exception e) {
                log.warn("Failed to parse message {}: {}", i, e.getMessage());
            }
        }

        folder.close(false);
        return result;
    }

    /**
     * Fetch full email detail by UID.
     */
    public EmailDetailDto fetchEmailDetail(EmailAccount account, String folderName, long uid)
            throws MessagingException, IOException {

        Store store = imapConnectionService.getStore(account);
        IMAPFolder folder = (IMAPFolder) store.getFolder(folderName);
        folder.open(Folder.READ_WRITE);

        Message msg = folder.getMessageByUID(uid);
        if (msg == null) {
            folder.close(false);
            throw new MessagingException("Message not found: " + uid);
        }

        // Mark as read
        msg.setFlag(Flags.Flag.SEEN, true);

        EmailDetailDto detail = toDetail(msg, folderName, uid);
        folder.close(true);
        return detail;
    }

    // ---- Helpers ----

    private EmailSummaryDto toSummary(Message msg, String folderName) throws MessagingException {
        String subject = msg.getSubject() != null ? msg.getSubject() : "(no subject)";
        String fromAddress = "";
        String fromName = "";
        if (msg.getFrom() != null && msg.getFrom().length > 0) {
            Address from = msg.getFrom()[0];
            if (from instanceof InternetAddress ia) {
                fromAddress = ia.getAddress() != null ? ia.getAddress() : "";
                fromName = ia.getPersonal() != null ? ia.getPersonal() : fromAddress;
            }
        }
        boolean read = msg.isSet(Flags.Flag.SEEN);
        Date received = msg.getReceivedDate();
        LocalDateTime receivedAt = received != null
                ? received.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
                : LocalDateTime.now();

        long uid = -1;
        if (msg.getFolder() instanceof IMAPFolder imapFolder) {
            try {
                uid = imapFolder.getUID(msg);
            } catch (Exception ignored) {}
        }

        boolean hasAttachment = false;
        try {
            hasAttachment = msg.getContentType() != null
                    && msg.getContentType().toLowerCase().contains("multipart");
        } catch (Exception ignored) {}

        return new EmailSummaryDto(uid, subject, fromAddress, fromName, "", receivedAt, read, hasAttachment, folderName);
    }

    private EmailDetailDto toDetail(Message msg, String folderName, long uid)
            throws MessagingException, IOException {

        String subject = msg.getSubject() != null ? msg.getSubject() : "(no subject)";
        String fromAddress = "", fromName = "";
        if (msg.getFrom() != null && msg.getFrom().length > 0) {
            Address from = msg.getFrom()[0];
            if (from instanceof InternetAddress ia) {
                fromAddress = ia.getAddress() != null ? ia.getAddress() : "";
                fromName = ia.getPersonal() != null ? ia.getPersonal() : fromAddress;
            }
        }

        List<String> toAddresses = extractAddresses(msg.getRecipients(Message.RecipientType.TO));
        List<String> ccAddresses = extractAddresses(msg.getRecipients(Message.RecipientType.CC));

        String[] bodyContent = extractBody(msg);
        String bodyText = bodyContent[0];
        String bodyHtml = bodyContent[1];

        List<String> attachmentNames = new ArrayList<>();
        if (msg.getContent() instanceof MimeMultipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart part = mp.getBodyPart(i);
                String disposition = part.getDisposition();
                if (Part.ATTACHMENT.equalsIgnoreCase(disposition)) {
                    attachmentNames.add(part.getFileName());
                }
            }
        }

        Date received = msg.getReceivedDate();
        LocalDateTime receivedAt = received != null
                ? received.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
                : LocalDateTime.now();

        return new EmailDetailDto(uid, subject, fromAddress, fromName, toAddresses, ccAddresses,
                bodyHtml, bodyText, receivedAt, true, attachmentNames, folderName);
    }

    private List<String> extractAddresses(Address[] addresses) {
        if (addresses == null) return List.of();
        return Arrays.stream(addresses)
                .map(a -> a instanceof InternetAddress ia ? ia.getAddress() : a.toString())
                .toList();
    }

    private String[] extractBody(Part part) throws MessagingException, IOException {
        String text = "", html = "";
        Object content = part.getContent();
        if (content instanceof String s) {
            if (part.getContentType().toLowerCase().contains("html")) html = s;
            else text = s;
        } else if (content instanceof MimeMultipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart bp = mp.getBodyPart(i);
                String[] sub = extractBody(bp);
                if (!sub[0].isEmpty()) text = sub[0];
                if (!sub[1].isEmpty()) html = sub[1];
            }
        }
        return new String[]{text, html};
    }
}
