package com.emailmanager.backend.accounts.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256/GCM encryption service for storing Gmail App Passwords securely.
 *
 * SECURITY IMPROVEMENTS over the previous AES/CBC implementation:
 *  - Random 12-byte IV per encrypt() → same plaintext → different ciphertext
 *  - GCM provides authenticated encryption (detects tampering)
 *  - Fails fast if the key is shorter than 32 bytes
 *  - IV is prepended to ciphertext and stripped during decrypt()
 *
 * MIGRATION:
 *  Existing passwords were encrypted with AES/CBC. They start with a different
 *  Base64 pattern than the new format (which begins with "GCM:" prefix).
 *  decrypt() auto-detects and decrypts legacy values via AES/CBC for backward
 *  compatibility. Re-encrypts them with GCM the next time the password is saved.
 */
@Service
public class EncryptionService {

    private static final String GCM_ALGORITHM = "AES/GCM/NoPadding";
    private static final String CBC_ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final int GCM_IV_LEN   = 12;  // bytes
    private static final int GCM_TAG_BITS = 128; // authentication tag
    private static final String GCM_PREFIX = "GCM:";

    private static final SecureRandom RANDOM = new SecureRandom();

    @Value("${app.encryption-key}")
    private String encryptionKeyStr;

    // ── Key helpers ──────────────────────────────────────────────────────────

    private SecretKey getKey() {
        byte[] raw = encryptionKeyStr.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (raw.length < 32) {
            throw new IllegalStateException(
                "ENCRYPTION_KEY must be at least 32 bytes (256-bit). Current length: " + raw.length);
        }
        // Use exactly the first 32 bytes
        return new SecretKeySpec(Arrays.copyOf(raw, 32), "AES");
    }

    // Legacy CBC key — pad/truncate to 32 bytes (same as old behaviour)
    private SecretKey getLegacyCbcKey() {
        byte[] raw = encryptionKeyStr.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] keyBytes = Arrays.copyOf(raw, 32); // pads with 0-bytes if shorter
        return new SecretKeySpec(keyBytes, "AES");
    }

    // Legacy CBC IV — first 16 bytes of raw key (same as old behaviour)
    private javax.crypto.spec.IvParameterSpec getLegacyCbcIv() {
        byte[] raw = encryptionKeyStr.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] iv = Arrays.copyOf(raw, 16);
        return new javax.crypto.spec.IvParameterSpec(iv);
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Encrypts with AES/GCM. Output format: "GCM:" + base64(iv || ciphertext+tag).
     * Calling this twice on the same value always yields a different result.
     */
    public String encrypt(String plainText) {
        try {
            byte[] iv = new byte[GCM_IV_LEN];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(GCM_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, getKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherAndTag = cipher.doFinal(
                    plainText.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // Prepend IV
            byte[] payload = new byte[GCM_IV_LEN + cipherAndTag.length];
            System.arraycopy(iv, 0, payload, 0, GCM_IV_LEN);
            System.arraycopy(cipherAndTag, 0, payload, GCM_IV_LEN, cipherAndTag.length);

            return GCM_PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt value", e);
        }
    }

    /**
     * Decrypts both GCM (new) and legacy CBC (old) ciphertexts transparently.
     */
    public String decrypt(String encryptedText) {
        if (encryptedText != null && encryptedText.startsWith(GCM_PREFIX)) {
            return decryptGcm(encryptedText.substring(GCM_PREFIX.length()));
        } else {
            // Legacy CBC path — for passwords saved before the migration
            return decryptLegacyCbc(encryptedText);
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private String decryptGcm(String base64Payload) {
        try {
            byte[] payload = Base64.getDecoder().decode(base64Payload);
            byte[] iv          = Arrays.copyOfRange(payload, 0, GCM_IV_LEN);
            byte[] cipherAndTag = Arrays.copyOfRange(payload, GCM_IV_LEN, payload.length);

            Cipher cipher = Cipher.getInstance(GCM_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(cipherAndTag), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt GCM value", e);
        }
    }

    private String decryptLegacyCbc(String base64Ciphertext) {
        try {
            Cipher cipher = Cipher.getInstance(CBC_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, getLegacyCbcKey(), getLegacyCbcIv());
            byte[] decoded = Base64.getDecoder().decode(base64Ciphertext);
            return new String(cipher.doFinal(decoded), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt legacy CBC value", e);
        }
    }
}
