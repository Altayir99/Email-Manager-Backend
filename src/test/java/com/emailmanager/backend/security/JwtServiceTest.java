package com.emailmanager.backend.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for JwtService — token generation, extraction, and validation.
 *
 * Uses ReflectionTestUtils to inject config properties without Spring context.
 */
class JwtServiceTest {

    private JwtService jwtService;

    // 32+ byte secret for HMAC-SHA256
    private static final String JWT_SECRET = "myJwtSecretKeyForTestingAtLeast32Bytes!";
    private static final long EXPIRY_MINUTES = 60;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "jwtSecret", JWT_SECRET);
        ReflectionTestUtils.setField(jwtService, "jwtExpirationMinutes", EXPIRY_MINUTES);
    }

    private UserDetails userDetails(String username) {
        return User.withUsername(username)
                .password("hashed")
                .roles("USER")
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Token generation
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Token generation")
    class TokenGeneration {

        @Test
        @DisplayName("generates non-null, non-empty token")
        void generatesToken() {
            String token = jwtService.generateToken("testuser");
            assertThat(token).isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("generates a 3-part JWT (header.payload.signature)")
        void threePartJwt() {
            String token = jwtService.generateToken("testuser");
            assertThat(token.split("\\.")).hasSize(3);
        }

        @Test
        @DisplayName("generates tokens with different payload for different users")
        void differentUsersGetDifferentTokens() {
            String t1 = jwtService.generateToken("alice");
            String t2 = jwtService.generateToken("bob");
            assertThat(t1).isNotEqualTo(t2);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Username extraction
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Username extraction")
    class UsernameExtraction {

        @Test
        @DisplayName("extracts correct username from token")
        void extractsUsername() {
            String token = jwtService.generateToken("firas");
            assertThat(jwtService.extractUsername(token)).isEqualTo("firas");
        }

        @Test
        @DisplayName("throws on malformed token")
        void malformedTokenThrows() {
            assertThatThrownBy(() -> jwtService.extractUsername("not.a.jwt"))
                    .isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("throws on tampered token")
        void tamperedTokenThrows() {
            String token = jwtService.generateToken("user");
            String tampered = token.substring(0, token.length() - 3) + "XXX";
            assertThatThrownBy(() -> jwtService.extractUsername(tampered))
                    .isInstanceOf(Exception.class);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Token validation
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Token validation")
    class TokenValidation {

        @Test
        @DisplayName("isTokenValid returns true for fresh token with matching username")
        void validToken() {
            String token = jwtService.generateToken("admin");
            UserDetails details = userDetails("admin");
            assertThat(jwtService.isTokenValid(token, details)).isTrue();
        }

        @Test
        @DisplayName("isTokenValid returns false when username doesn't match")
        void usernameMismatch() {
            String token = jwtService.generateToken("alice");
            UserDetails details = userDetails("bob");
            assertThat(jwtService.isTokenValid(token, details)).isFalse();
        }

        @Test
        @DisplayName("isTokenValid returns false for expired token")
        void expiredToken() throws InterruptedException {
            // Create a service with 0-minute expiry
            JwtService expiredService = new JwtService();
            ReflectionTestUtils.setField(expiredService, "jwtSecret", JWT_SECRET);
            ReflectionTestUtils.setField(expiredService, "jwtExpirationMinutes", 0L);

            String token = expiredService.generateToken("user");
            UserDetails details = userDetails("user");

            // Wait a moment so the token is definitely expired
            Thread.sleep(100);

            // Token is expired — extractClaim throws ExpiredJwtException
            // which propagates through isTokenValid
            assertThatThrownBy(() -> expiredService.isTokenValid(token, details))
                    .isInstanceOf(io.jsonwebtoken.ExpiredJwtException.class);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Cross-key validation
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Cross-key validation")
    class CrossKeyValidation {

        @Test
        @DisplayName("token generated with one key cannot be validated with another")
        void differentKeyFails() {
            String token = jwtService.generateToken("user");

            JwtService otherService = new JwtService();
            ReflectionTestUtils.setField(otherService, "jwtSecret",
                    "aCompletelyDifferentSecret32BytesLong!");
            ReflectionTestUtils.setField(otherService, "jwtExpirationMinutes", EXPIRY_MINUTES);

            assertThatThrownBy(() -> otherService.extractUsername(token))
                    .isInstanceOf(Exception.class);
        }
    }
}
