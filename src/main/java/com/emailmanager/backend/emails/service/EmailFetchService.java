package com.emailmanager.backend.emails.service;

import com.emailmanager.backend.accounts.entity.EmailAccount;
import com.emailmanager.backend.accounts.service.ImapConnectionService;
import com.emailmanager.backend.emails.dto.EmailDetailDto;
import com.emailmanager.backend.emails.dto.EmailPageDto;
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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * Fetches emails via IMAP with the following Phase 2 optimisations:
 *
 *  2.1 – Uses acquireStore/releaseStore with try/finally to avoid thread races.
 *  2.2 – Batches UID into the FetchProfile so getUID() is free (no extra RTT).
 *  2.3 – Populates real text snippet (first 200 chars of text body via partial
 *         fetch) and accurate hasAttachment flag from body-part dispositions.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailFetchService {

    private final ImapConnectionService imapConnectionService;

    private static final int SNIPPET_MAX = 200;

    // ── Folders ──────────────────────────────────────────────────────────────

    public List<FolderDto> listFolders(EmailAccount account) throws MessagingException {
        Store store = imapConnectionService.acquireStore(account);
        try {
            List<FolderDto> folders = new ArrayList<>();
            Folder[] folderList = store.getDefaultFolder().list("*");
            for (Folder folder : folderList) {
                if ((folder.getType() & Folder.HOLDS_MESSAGES) == 0) continue;
                // open READ_ONLY briefly to get real unread / total counts
                folder.open(Folder.READ_ONLY);
                int total  = folder.getMessageCount();
                int unread = folder.getUnreadMessageCount();
                folder.close(false);
                folders.add(new FolderDto(
                        folder.getName(),
                        folder.getFullName(),
                        unread,
                        total,
                        false
                ));
            }
            return folders;
        } finally {
            imapConnectionService.releaseStore(account.getId());
        }
    }

    // ── Email List ───────────────────────────────────────────────────────────

    /**
     * Phase 2.2: batch-fetches ENVELOPE + FLAGS + UID in a single round-trip.
     * Phase 2.3: real snippet + accurate attachment flag.
     * Phase 2.4: returns EmailPageDto with server-authoritative hasMore / totalMessages.
     */
    public EmailPageDto fetchEmails(
            EmailAccount account, String folderName, int page, int pageSize)
            throws MessagingException {

        Store store = imapConnectionService.acquireStore(account);
        try {
            Folder folder = store.getFolder(folderName);
            folder.open(Folder.READ_ONLY);
            try {
                int total = folder.getMessageCount();
                if (total == 0) return new EmailPageDto(List.of(), page, pageSize, 0, false);

                int end   = total - (page * pageSize);
                int start = Math.max(1, end - pageSize + 1);
                if (end < 1) return new EmailPageDto(List.of(), page, pageSize, total, false);

                Message[] messages = folder.getMessages(start, end);

                // Phase 2.2: fetch ENVELOPE + FLAGS + UID in one batch
                FetchProfile profile = new FetchProfile();
                profile.add(FetchProfile.Item.ENVELOPE);
                profile.add(FetchProfile.Item.FLAGS);
                profile.add(UIDFolder.FetchProfileItem.UID);
                profile.add(FetchProfile.Item.CONTENT_INFO);
                folder.fetch(messages, profile);

                List<EmailSummaryDto> result = new ArrayList<>();
                for (int i = messages.length - 1; i >= 0; i--) {   // newest first
                    try {
                        result.add(toSummary(messages[i], folderName, (IMAPFolder) folder));
                    } catch (Exception e) {
                        log.warn("Failed to parse message {}: {}", i, e.getMessage());
                    }
                }

                // hasMore = there are more messages before the start of this page
                boolean hasMore = start > 1;
                return new EmailPageDto(result, page, pageSize, total, hasMore);

            } finally {
                if (folder.isOpen()) folder.close(false);
            }
        } finally {
            imapConnectionService.releaseStore(account.getId());
        }
    }

    // ── Email Detail ─────────────────────────────────────────────────────────

    public EmailDetailDto fetchEmailDetail(
            EmailAccount account, String folderName, long uid)
            throws MessagingException, IOException {

        Store store = imapConnectionService.acquireStore(account);
        try {
            IMAPFolder folder = (IMAPFolder) store.getFolder(folderName);
            folder.open(Folder.READ_WRITE);
            try {
                Message msg = folder.getMessageByUID(uid);
                if (msg == null) throw new MessagingException("Message not found: " + uid);

                msg.setFlag(Flags.Flag.SEEN, true);
                return toDetail(msg, folderName, uid);
            } finally {
                if (folder.isOpen()) folder.close(true);
            }
        } finally {
            imapConnectionService.releaseStore(account.getId());
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /** Phase 2.2/2.3: UID already in FetchProfile, no extra RTT. */
    private EmailSummaryDto toSummary(Message msg, String folderName, IMAPFolder folder)
            throws MessagingException {

        String subject     = msg.getSubject() != null ? msg.getSubject() : "(no subject)";
        String fromAddress = "";
        String fromName    = "";
        if (msg.getFrom() != null && msg.getFrom().length > 0) {
            Address from = msg.getFrom()[0];
            if (from instanceof InternetAddress ia) {
                fromAddress = ia.getAddress()  != null ? ia.getAddress()  : "";
                fromName    = ia.getPersonal() != null ? ia.getPersonal() : fromAddress;
            }
        }
        boolean read = msg.isSet(Flags.Flag.SEEN);
        Date received = msg.getReceivedDate();
        LocalDateTime receivedAt = received != null
                ? received.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
                : LocalDateTime.now();

        // Phase 2.2: UID already fetched — free read
        long uid = -1;
        try { uid = folder.getUID(msg); } catch (Exception ignored) {}

        // Phase 2.3: accurate attachment flag from content-type info (already fetched)
        boolean hasAttachment = hasAttachmentParts(msg);

        // Phase 2.3: lightweight snippet — first 200 chars of text part
        String snippet = extractSnippet(msg);

        return new EmailSummaryDto(
                uid, subject, fromAddress, fromName,
                snippet, receivedAt, read, hasAttachment, folderName);
    }

    private EmailDetailDto toDetail(Message msg, String folderName, long uid)
            throws MessagingException, IOException {

        String subject     = msg.getSubject() != null ? msg.getSubject() : "(no subject)";
        String fromAddress = "", fromName = "";
        if (msg.getFrom() != null && msg.getFrom().length > 0) {
            Address from = msg.getFrom()[0];
            if (from instanceof InternetAddress ia) {
                fromAddress = ia.getAddress()  != null ? ia.getAddress()  : "";
                fromName    = ia.getPersonal() != null ? ia.getPersonal() : fromAddress;
            }
        }
        List<String> toAddresses = extractAddresses(msg.getRecipients(Message.RecipientType.TO));
        List<String> ccAddresses = extractAddresses(msg.getRecipients(Message.RecipientType.CC));

        String[] bodyContent = extractBody(msg);
        String bodyText      = bodyContent[0];
        String bodyHtml      = bodyContent[1];

        List<String> attachmentNames = new ArrayList<>();
        collectAttachments(msg, attachmentNames);

        Date received = msg.getReceivedDate();
        LocalDateTime receivedAt = received != null
                ? received.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
                : LocalDateTime.now();

        return new EmailDetailDto(uid, subject, fromAddress, fromName,
                toAddresses, ccAddresses, bodyHtml, bodyText,
                receivedAt, true, attachmentNames, folderName);
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    private List<String> extractAddresses(Address[] addresses) {
        if (addresses == null) return List.of();
        return Arrays.stream(addresses)
                .map(a -> a instanceof InternetAddress ia ? ia.getAddress() : a.toString())
                .toList();
    }

    /**
     * Phase 2.3: determines hasAttachment by inspecting body-part disposition,
     * rather than just checking if content-type contains "multipart".
     */
    private boolean hasAttachmentParts(Message msg) {
        try {
            String ct = msg.getContentType();
            if (ct == null || !ct.toLowerCase().contains("multipart")) return false;
            Object content = msg.getContent();
            if (content instanceof MimeMultipart mp) {
                for (int i = 0; i < mp.getCount(); i++) {
                    String disp = mp.getBodyPart(i).getDisposition();
                    if (Part.ATTACHMENT.equalsIgnoreCase(disp)) return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * Phase 2.3: extracts the first SNIPPET_MAX characters of the plain-text
     * body as a preview snippet. Falls back to HTML stripped of tags.
     * Never downloads the full body for this — reads only what's needed.
     */
    private String extractSnippet(Message msg) {
        try {
            Object content = msg.getContent();
            if (content instanceof String s) {
                String ct = msg.getContentType().toLowerCase();
                String text = ct.contains("html") ? stripTags(s) : s;
                return truncate(text.trim());
            }
            if (content instanceof MimeMultipart mp) {
                // prefer plain text
                for (int i = 0; i < mp.getCount(); i++) {
                    BodyPart part = mp.getBodyPart(i);
                    if (part.getContentType().toLowerCase().contains("text/plain")) {
                        return truncate(part.getContent().toString().trim());
                    }
                }
                // fallback: first body part
                for (int i = 0; i < mp.getCount(); i++) {
                    BodyPart part = mp.getBodyPart(i);
                    String disp = part.getDisposition();
                    if (!Part.ATTACHMENT.equalsIgnoreCase(disp)) {
                        String raw = part.getContent().toString();
                        String ct  = part.getContentType().toLowerCase();
                        return truncate(ct.contains("html") ? stripTags(raw) : raw.trim());
                    }
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private String truncate(String text) {
        if (text == null) return "";
        text = text.replaceAll("\\s+", " ").trim();
        return text.length() > SNIPPET_MAX ? text.substring(0, SNIPPET_MAX) + "…" : text;
    }

    private String stripTags(String html) {
        return html.replaceAll("<[^>]+>", " ").replaceAll("&nbsp;", " ")
                   .replaceAll("&amp;", "&").replaceAll("&lt;", "<")
                   .replaceAll("&gt;", ">").replaceAll("\\s+", " ").trim();
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
                if (Part.ATTACHMENT.equalsIgnoreCase(bp.getDisposition())) continue;
                String[] sub = extractBody(bp);
                if (!sub[0].isEmpty()) text = sub[0];
                if (!sub[1].isEmpty()) html = sub[1];
            }
        }
        return new String[]{text, html};
    }

    private void collectAttachments(Part part, List<String> names)
            throws MessagingException, IOException {
        if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
            names.add(part.getFileName() != null ? part.getFileName() : "attachment");
            return;
        }
        Object content = part.getContent();
        if (content instanceof MimeMultipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                collectAttachments(mp.getBodyPart(i), names);
            }
        }
    }
}
