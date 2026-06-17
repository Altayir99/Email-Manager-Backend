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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages IMAP connections per account with thread safety.
 *
 * Phase 2.1 fix: JavaMail Store/Folder objects are NOT thread-safe.
 * The previous implementation shared a single cached Store across concurrent
 * user requests and the 60s polling scheduler, causing "Folder already open"
 * and other race conditions.
 *
 * Fix: per-account ReentrantLock ensures only one thread uses a given
 * account's Store at a time. The cached Store is reused across calls but
 * protected by the lock so concurrent access is serialised per-account.
 *
 * Phase 2.2 fix: use tryLock(20s) instead of lock() to prevent infinite
 * deadlock when an IMAP TCP connection hangs (e.g. slow server, dropped
 * network). If the lock can't be acquired in 20s we throw immediately so
 * the Flutter client gets an error instead of spinning forever.
 */
@Service
@Slf4j
public class ImapConnectionService {

    private final EncryptionService encryptionService;

    // One cached Store per accountId
    private final ConcurrentHashMap<UUID, Store> connectionCache = new ConcurrentHashMap<>();
    // One lock per accountId — prevents concurrent use of the same Store
    private final ConcurrentHashMap<UUID, ReentrantLock> accountLocks = new ConcurrentHashMap<>();

    // How long a thread waits for the per-account lock before giving up.
    // This prevents infinite loading when another thread is stuck on a slow IMAP server.
    private static final long LOCK_TIMEOUT_SECONDS = 20;

    public ImapConnectionService(EncryptionService encryptionService) {
        this.encryptionService = encryptionService;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Acquires the per-account lock (with a 20s timeout) and returns a connected Store.
     * IMPORTANT: caller MUST release via releaseStore(accountId) in a finally block.
     *
     * @throws AccountConnectionException if the lock cannot be acquired within 20s
     *         (indicates another thread is stuck on this account's IMAP connection)
     */
    public Store acquireStore(EmailAccount account) {
        ReentrantLock lock = accountLocks.computeIfAbsent(account.getId(), id -> new ReentrantLock());
        boolean acquired;
        try {
            acquired = lock.tryLock(LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AccountConnectionException("Interrupted while waiting for IMAP lock: " + account.getEmailAddress(), e);
        }

        if (!acquired) {
            log.error("[IMAP] Lock timeout for {} — another thread may be stuck. Evicting cached store.",
                    account.getEmailAddress());
            // Evict the potentially-dead store so the next caller gets a fresh connection
            connectionCache.remove(account.getId());
            throw new AccountConnectionException(
                    "IMAP connection busy for " + account.getEmailAddress() + ". Try again shortly.");
        }

        try {
            Store existing = connectionCache.get(account.getId());
            if (existing != null && existing.isConnected()) {
                return existing;
            }
            connectionCache.remove(account.getId());
            return connect(account);
        } catch (Exception e) {
            lock.unlock(); // must release on error — caller won't reach finally
            throw e;
        }
        // Lock stays held — caller releases via releaseStore()
    }

    /**
     * Release the per-account lock after folder operations are complete.
     * Always call in a finally block paired with acquireStore().
     */
    public void releaseStore(UUID accountId) {
        ReentrantLock lock = accountLocks.get(accountId);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    /**
     * Test connection without caching — used during account validation (addAccount).
     */
    public void testConnection(EmailAccount account) {
        try {
            String password = encryptionService.decrypt(account.getEncryptedPassword());
            Store store = session(account, 8000).getStore("imaps");
            store.connect(account.getImapHost(), account.getImapPort(), account.getUsername(), password);
            store.close();
            log.info("IMAP connection test OK: {}", account.getEmailAddress());
        } catch (MessagingException e) {
            throw new AccountConnectionException("Connection test failed: " + e.getMessage(), e);
        }
    }

    /**
     * Close and remove cached connection for an account.
     */
    public void disconnect(UUID accountId) {
        Store store = connectionCache.remove(accountId);
        if (store != null && store.isConnected()) {
            try { store.close(); } catch (MessagingException e) {
                log.warn("Error closing IMAP store for {}: {}", accountId, e.getMessage());
            }
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private Store connect(EmailAccount account) {
        try {
            String password = encryptionService.decrypt(account.getEncryptedPassword());
            Store store = session(account, 10000).getStore("imaps");
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

    private Session session(EmailAccount account, int timeoutMs) {
        Properties props = new Properties();
        props.put("mail.store.protocol",            "imaps");
        props.put("mail.imaps.host",                account.getImapHost());
        props.put("mail.imaps.port",                account.getImapPort());
        props.put("mail.imaps.ssl.enable",          "true");
        props.put("mail.imaps.connectiontimeout",   String.valueOf(timeoutMs)); // TCP connect
        props.put("mail.imaps.timeout",             String.valueOf(timeoutMs)); // read timeout
        props.put("mail.imaps.writetimeout",        String.valueOf(timeoutMs)); // write timeout (was missing!)
        props.put("mail.imaps.connectionpooltimeout", String.valueOf(timeoutMs));
        return Session.getInstance(props);
    }
}
