package io.gateway.oss.core.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PasswordService} 的单元测试。
 * 覆盖 BCrypt 哈希验证、SHA-256 遗留哈希兼容、以及边界条件。
 */
class PasswordServiceTest {

    private PasswordService service;

    @BeforeEach
    void setUp() {
        service = new PasswordService();
    }

    // ─── hashPassword ───

    @Test
    void hashPasswordShouldReturnBcryptHash() {
        String hash = service.hashPassword("myPassword123");
        assertNotNull(hash);
        assertTrue(hash.startsWith("$2a$") || hash.startsWith("$2b$") || hash.startsWith("$2y$"),
                "hashPassword should produce a BCrypt hash");
    }

    @Test
    void hashPasswordShouldProduceDifferentHashesForSamePassword() {
        String hash1 = service.hashPassword("samePassword");
        String hash2 = service.hashPassword("samePassword");
        // BCrypt uses random salt, so hashes should differ
        assertNotEquals(hash1, hash2);
    }

    // ─── verifyPassword with BCrypt ───

    @Test
    void shouldVerifyCorrectBcryptPassword() {
        String hash = service.hashPassword("correctPassword");
        assertTrue(service.verifyPassword("correctPassword", hash));
    }

    @Test
    void shouldRejectWrongBcryptPassword() {
        String hash = service.hashPassword("correctPassword");
        assertFalse(service.verifyPassword("wrongPassword", hash));
    }

    // ─── verifyPassword with SHA-256 (legacy compatibility) ───

    @Test
    void shouldVerifyCorrectSha256Password() {
        // Pre-computed SHA-256 of "legacyPassword"
        String sha256Hash = "e2b6c9b8c5f3c0e1a2d4f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7";
        // Actually compute it properly
        String expectedHash = sha256Hex("legacyPassword");
        assertTrue(service.verifyPassword("legacyPassword", expectedHash));
    }

    @Test
    void shouldRejectWrongSha256Password() {
        String hash = sha256Hex("realPassword");
        assertFalse(service.verifyPassword("wrongPassword", hash));
    }

    // ─── verifyPassword edge cases ───

    @Test
    void shouldReturnFalseForNullHash() {
        assertFalse(service.verifyPassword("anyPassword", null));
    }

    @Test
    void shouldReturnFalseForBlankHash() {
        assertFalse(service.verifyPassword("anyPassword", ""));
        assertFalse(service.verifyPassword("anyPassword", "   "));
    }

    @Test
    void shouldRejectEmptyPasswordAgainstBcrypt() {
        String hash = service.hashPassword("nonEmpty");
        assertFalse(service.verifyPassword("", hash));
    }

    // ─── isBcryptHash ───

    @Test
    void shouldDetectBcryptHashWithAllPrefixes() {
        assertTrue(service.isBcryptHash("$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"));
        assertTrue(service.isBcryptHash("$2b$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"));
        assertTrue(service.isBcryptHash("$2y$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"));
    }

    @Test
    void shouldRejectNonBcryptHash() {
        assertFalse(service.isBcryptHash(sha256Hex("test")));
        assertFalse(service.isBcryptHash("plaintext"));
        assertFalse(service.isBcryptHash(""));
        assertFalse(service.isBcryptHash(null));
    }

    // ─── helper: compute SHA-256 hex (mirrors PasswordService.sha256Hex) ───

    private static String sha256Hex(String input) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
