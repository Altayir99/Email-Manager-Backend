package com.emailmanager.backend.emails.service;

import com.emailmanager.backend.accounts.entity.EmailAccount;
import com.emailmanager.backend.accounts.service.ImapConnectionService;
import com.emailmanager.backend.config.exception.AccountConnectionException;
import com.sun.mail.imap.IMAPFolder;
import jakarta.mail.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailActionService {

    private final ImapConnectionService imapConnectionService;

    public void markAsRead(EmailAccount account, String folderName, long uid) {
        setFlag(account, folderName, uid, Flags.Flag.SEEN, true);
    }

    public void markAsUnread(EmailAccount account, String folderName, long uid) {
        setFlag(account, folderName, uid, Flags.Flag.SEEN, false);
    }

    public void deleteEmail(EmailAccount account, String folderName, long uid) {
        moveEmail(account, folderName, "[Gmail]/Trash", uid);
    }

    public void archiveEmail(EmailAccount account, String folderName, long uid) {
        moveEmail(account, folderName, "[Gmail]/All Mail", uid);
    }

    public void moveEmail(EmailAccount account, String fromFolderName, String toFolderName, long uid) {
        try {
            Store store = imapConnectionService.getStore(account);
            IMAPFolder fromFolder = (IMAPFolder) store.getFolder(fromFolderName);
            Folder toFolder = store.getFolder(toFolderName);

            fromFolder.open(Folder.READ_WRITE);

            Message msg = fromFolder.getMessageByUID(uid);
            if (msg == null) {
                fromFolder.close(false);
                throw new AccountConnectionException("Message not found: " + uid);
            }

            fromFolder.copyMessages(new Message[]{msg}, toFolder);
            msg.setFlag(Flags.Flag.DELETED, true);
            fromFolder.expunge();
            fromFolder.close(true);

            log.info("Moved message {} from {} to {}", uid, fromFolderName, toFolderName);
        } catch (MessagingException e) {
            throw new AccountConnectionException("Failed to move email: " + e.getMessage(), e);
        }
    }

    private void setFlag(EmailAccount account, String folderName, long uid, Flags.Flag flag, boolean value) {
        try {
            Store store = imapConnectionService.getStore(account);
            IMAPFolder folder = (IMAPFolder) store.getFolder(folderName);
            folder.open(Folder.READ_WRITE);
            Message msg = folder.getMessageByUID(uid);
            if (msg != null) {
                msg.setFlag(flag, value);
            }
            folder.close(true);
        } catch (MessagingException e) {
            throw new AccountConnectionException("Failed to update flag: " + e.getMessage(), e);
        }
    }
}
