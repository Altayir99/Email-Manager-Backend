package com.emailmanager.backend.emails.service;

import com.emailmanager.backend.accounts.entity.EmailAccount;
import com.emailmanager.backend.accounts.service.ImapConnectionService;
import com.emailmanager.backend.accounts.service.ImapFolderResolver;
import com.emailmanager.backend.accounts.service.ImapFolderResolver.SpecialUse;
import com.emailmanager.backend.config.exception.AccountConnectionException;
import com.sun.mail.imap.IMAPFolder;
import jakarta.mail.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Phase 2.1: all IMAP operations now use acquireStore/releaseStore in
 * try/finally blocks so folders are always closed and the per-account lock
 * is always released even if an exception is thrown.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailActionService {

    private final ImapConnectionService imapConnectionService;
    private final ImapFolderResolver folderResolver;

    public void markAsRead(EmailAccount account, String folderName, long uid) {
        setFlag(account, folderName, uid, Flags.Flag.SEEN, true);
    }

    public void markAsUnread(EmailAccount account, String folderName, long uid) {
        setFlag(account, folderName, uid, Flags.Flag.SEEN, false);
    }

    public void deleteEmail(EmailAccount account, String folderName, long uid) {
        // Resolve the real Trash folder (locale-independent) — e.g. a German Gmail
        // uses [Gmail]/Papierkorb, not [Gmail]/Trash.
        String trash = resolveOrFail(account, SpecialUse.TRASH, "Papierkorb");
        moveEmail(account, folderName, trash, uid);
    }

    public void archiveEmail(EmailAccount account, String folderName, long uid) {
        // Resolve the real All-Mail/Archive folder — e.g. [Gmail]/Alle Nachrichten.
        String allMail = resolveOrFail(account, SpecialUse.ALL_MAIL, "Archiv/Alle Nachrichten");
        moveEmail(account, folderName, allMail, uid);
    }

    /**
     * Resolves a special-use target folder; throws a clear, client-facing
     * (HTTP 400) error instead of an opaque 500 when the account has no such
     * folder — rather than moving to a non-existent hard-coded name.
     */
    private String resolveOrFail(EmailAccount account, SpecialUse use, String label) {
        return folderResolver.resolve(account, use).orElseThrow(() -> {
            log.warn("[Action] No {} folder found for {} — cannot perform action",
                    use, account.getEmailAddress());
            return new AccountConnectionException(
                    "Kein " + label + "-Ordner für " + account.getEmailAddress() + " gefunden.");
        });
    }

    public void moveEmail(EmailAccount account, String fromFolderName,
                          String toFolderName, long uid) {
        Store store = imapConnectionService.acquireStore(account);
        try {
            IMAPFolder fromFolder = (IMAPFolder) store.getFolder(fromFolderName);
            Folder     toFolder   = store.getFolder(toFolderName);
            fromFolder.open(Folder.READ_WRITE);
            try {
                Message msg = fromFolder.getMessageByUID(uid);
                if (msg == null) throw new AccountConnectionException("Message not found: " + uid);
                fromFolder.copyMessages(new Message[]{msg}, toFolder);
                msg.setFlag(Flags.Flag.DELETED, true);
                fromFolder.expunge();
                log.info("Moved message {} from {} to {}", uid, fromFolderName, toFolderName);
            } finally {
                if (fromFolder.isOpen()) fromFolder.close(true);
            }
        } catch (MessagingException e) {
            throw new AccountConnectionException("Failed to move email: " + e.getMessage(), e);
        } finally {
            imapConnectionService.releaseStore(account.getId());
        }
    }

    private void setFlag(EmailAccount account, String folderName,
                         long uid, Flags.Flag flag, boolean value) {
        Store store = imapConnectionService.acquireStore(account);
        try {
            IMAPFolder folder = (IMAPFolder) store.getFolder(folderName);
            folder.open(Folder.READ_WRITE);
            try {
                Message msg = folder.getMessageByUID(uid);
                if (msg != null) msg.setFlag(flag, value);
            } finally {
                if (folder.isOpen()) folder.close(true);
            }
        } catch (MessagingException e) {
            throw new AccountConnectionException("Failed to update flag: " + e.getMessage(), e);
        } finally {
            imapConnectionService.releaseStore(account.getId());
        }
    }
}
