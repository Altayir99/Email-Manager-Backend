package com.emailmanager.backend.accounts.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for EncryptionService — AES-256/GCM and legacy CBC paths.
 *
 * Uses ReflectionTestUtils to inject the encryption key without Spring context.
 */
class EncryptionServiceTest {

    private EncryptionService service;

    // 32-byte key — exactly 256 bits
    private static final String VALID_KEY = "12345678901234567890123456789012";

    @BeforeEach
    void setUp() {
        service = new EncryptionService();
        ReflectionTestUtils.setField(service, "encryptionKeyStr", VALID_KEY);
    }

    // ════════════════════════════════════════════════════════════════════════
    // GCM round-trip
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AES-256/GCM round-trip")
    class GcmRoundTrip {

        @Test
        @DisplayName("encrypt → decrypt returns original plaintext")
        void roundTrip() {
            String plainText = "mySecretAppPassword!123";
            String encrypted = service.encrypt(plainText);
            String decrypted = service.decrypt(encrypted);

            assertThat(decrypted).isEqualTo(plainText);
        }

        @Test
        @DisplayName("encrypted text starts with GCM: prefix")
        void gcmPrefix() {
            String encrypted = service.encrypt("test");
            assertThat(encrypted).startsWith("GCM:");
        }

        @Test
        @DisplayName("encrypting the same value twice yields different ciphertexts (random IV)")
        void differentCiphertextsPerEncrypt() {
            String value = "same-password";
            String enc1 = service.encrypt(value);
            String enc2 = service.encrypt(value);

            assertThat(enc1).isNotEqualTo(enc2);
            // Both must decrypt back to the same value
            assertThat(service.decrypt(enc1)).isEqualTo(value);
            assertThat(service.decrypt(enc2)).isEqualTo(value);
        }

        @Test
        @DisplayName("handles empty string")
        void emptyString() {
            String encrypted = service.encrypt("");
            assertThat(service.decrypt(encrypted)).isEmpty();
        }

        @Test
        @DisplayName("handles unicode characters (German umlauts)")
        void unicodeChars() {
            String plainText = "Passwort mit Umlauten: ÄÖÜäöüß";
            String encrypted = service.encrypt(plainText);
            assertThat(service.decrypt(encrypted)).isEqualTo(plainText);
        }

        @Test
        @DisplayName("handles long passwords")
        void longPassword() {
            String longPassword = "x".repeat(1000);
            String encrypted = service.encrypt(longPassword);
            assertThat(service.decrypt(encrypted)).isEqualTo(longPassword);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Key validation
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Key validation")
    class KeyValidation {

        @Test
        @DisplayName("throws when key is shorter than 32 bytes")
        void shortKeyThrows() {
            EncryptionService shortKeyService = new EncryptionService();
            ReflectionTestUtils.setField(shortKeyService, "encryptionKeyStr", "tooShort");

            assertThatThrownBy(() -> shortKeyService.encrypt("test"))
                    .isInstanceOf(RuntimeException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("accepts keys longer than 32 bytes (uses first 32)")
        void longKeyAccepted() {
            EncryptionService longKeyService = new EncryptionService();
            String longKey = VALID_KEY + "extra-padding-data";
            ReflectionTestUtils.setField(longKeyService, "encryptionKeyStr", longKey);

            String plainText = "test-value";
            String encrypted = longKeyService.encrypt(plainText);
            assertThat(longKeyService.decrypt(encrypted)).isEqualTo(plainText);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Tamper detection
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GCM tamper detection")
    class TamperDetection {

        @Test
        @DisplayName("decrypt fails on corrupted ciphertext (GCM authentication)")
        void corruptedCiphertext() {
            String encrypted = service.encrypt("valid");
            // Corrupt a character in the Base64 payload
            String corrupted = encrypted.substring(0, encrypted.length() - 2) + "XX";

            assertThatThrownBy(() -> service.decrypt(corrupted))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("decrypt fails with wrong key")
        void wrongKeyFails() {
            String encrypted = service.encrypt("secret");

            EncryptionService otherService = new EncryptionService();
            ReflectionTestUtils.setField(otherService, "encryptionKeyStr",
                    "different_key_32_bytes_long_here!");

            assertThatThrownBy(() -> otherService.decrypt(encrypted))
                    .isInstanceOf(RuntimeException.class);
        }
    }
}
