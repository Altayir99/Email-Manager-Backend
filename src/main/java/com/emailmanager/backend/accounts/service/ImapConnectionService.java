package com.emailmanager.backend.accounts.service;

import com.emailmanager.backend.accounts.entity.EmailAccount;
import com.emailmanager.backend.config.exception.AccountConnectionException;
import com.sun.mail.imap.IMAPStore;
import jakarta.mail.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages IMAP connections per account.
 * Caches open IMAPStore instances to avoid reconnecting on every request.
 */
@Service
@Slf4j
public class ImapConnectionService {

    private final EncryptionService encryptionService;
    // Cache: accountId -> IMAPStore
    private final ConcurrentHashMap<UUID, Store> connectionCache = new ConcurrentHashMap<>();

    public ImapConnectionService(EncryptionService encryptionService) {
        this.encryptionService = encryptionService;
    }

    /**
     * Get (or create) an IMAP Store for the given account.
     */
    public Store getStore(EmailAccount account) {
        Store existing = connectionCache.get(account.getId());
        if (existing != null && existing.isConnected()) {
            return existing;
        }
        // Remove stale entry and reconnect
        connectionCache.remove(account.getId());
        return connect(account);
    }


    /**
     * Open a fresh IMAP connection and cache it.
     */
    public Store connect(EmailAccount account) {
        try {
            String password = encryptionService.decrypt(account.getEncryptedPassword());

            Properties props = new Properties();
            props.put("mail.store.protocol", "imaps");
            props.put("mail.imaps.host", account.getImapHost());
            props.put("mail.imaps.port", account.getImapPort());
            props.put("mail.imaps.ssl.enable", "true");
            props.put("mail.imaps.connectiontimeout", "10000");
            props.put("mail.imaps.timeout", "10000");

            Session session = Session.getInstance(props);
            Store store = session.getStore("imaps");
            store.connect(account.getImapHost(), account.getImapPort(), account.getUsername(), password);

            connectionCache.put(account.getId(), store);
            log.info("IMAP connected: {}", account.getEmailAddress());
            return store;

        } catch (MessagingException e) {
            log.error("IMAP connection failed for {}: {}", account.getEmailAddress(), e.getMessage());
            throw new AccountConnectionException(
                    "Cannot connect to " + account.getEmailAddress() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Test connection without caching — used for account validation.
     */
    public void testConnection(EmailAccount account) {
        try {
            String password = encryptionService.decrypt(account.getEncryptedPassword());

            Properties props = new Properties();
            props.put("mail.store.protocol", "imaps");
            props.put("mail.imaps.host", account.getImapHost());
            props.put("mail.imaps.port", account.getImapPort());
            props.put("mail.imaps.ssl.enable", "true");
            props.put("mail.imaps.connectiontimeout", "8000");
            props.put("mail.imaps.timeout", "8000");

            Session session = Session.getInstance(props);
            Store store = session.getStore("imaps");
            store.connect(account.getImapHost(), account.getImapPort(), account.getUsername(), password);
            store.close();
            log.info("IMAP connection test OK: {}", account.getEmailAddress());
        } catch (MessagingException e) {
            throw new AccountConnectionException(
                    "Connection test failed: " + e.getMessage(), e);
        }
    }

    /**
     * Close and remove cached connection.
     */
    public void disconnect(UUID accountId) {
        Store store = connectionCache.remove(accountId);
        if (store != null && store.isConnected()) {
            try {
                store.close();
            } catch (MessagingException e) {
                log.warn("Error closing IMAP store for account {}: {}", accountId, e.getMessage());
            }
        }
    }
}
