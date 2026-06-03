package com.emailmanager.backend.accounts.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/**
 * AES-256 encryption service for storing App Passwords securely.
 * Key must be exactly 32 bytes (256-bit).
 * Uses AES/CBC/PKCS5Padding with a fixed IV derived from the key.
 */
@Service
public class EncryptionService {

    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";

    @Value("${app.encryption-key}")
    private String encryptionKey;

    private SecretKeySpec getKeySpec() {
        // Pad or truncate key to exactly 32 bytes
        byte[] keyBytes = new byte[32];
        byte[] rawKey = encryptionKey.getBytes();
        System.arraycopy(rawKey, 0, keyBytes, 0, Math.min(rawKey.length, 32));
        return new SecretKeySpec(keyBytes, "AES");
    }

    private IvParameterSpec getIv() {
        // Derive IV from first 16 bytes of key
        byte[] ivBytes = new byte[16];
        byte[] rawKey = encryptionKey.getBytes();
        System.arraycopy(rawKey, 0, ivBytes, 0, Math.min(rawKey.length, 16));
        return new IvParameterSpec(ivBytes);
    }

    public String encrypt(String plainText) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, getKeySpec(), getIv());
            byte[] encrypted = cipher.doFinal(plainText.getBytes());
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt value", e);
        }
    }

    public String decrypt(String encryptedText) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, getKeySpec(), getIv());
            byte[] decoded = Base64.getDecoder().decode(encryptedText);
            return new String(cipher.doFinal(decoded));
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt value", e);
        }
    }
}
