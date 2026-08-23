package io.gateway.oss.core.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 密码哈希与验证服务。
 * <p>
 * 支持 BCrypt 哈希（首选）和 SHA-256 遗留哈希的验证。
 * </p>
 */
@Service
public class PasswordService {

    private final BCryptPasswordEncoder encoder;

    public PasswordService() {
        this.encoder = new BCryptPasswordEncoder();
    }

    String hashPassword(String password) {
        return encoder.encode(password);
    }

    public boolean verifyPassword(String rawPassword, String storedHash) {
        if (storedHash == null || storedHash.isBlank()) {
            return false;
        }
        if (isBcryptHash(storedHash)) {
            return encoder.matches(rawPassword, storedHash);
        }
        return sha256Hex(rawPassword).equals(storedHash);
    }

    boolean isBcryptHash(String hash) {
        return hash != null && (hash.startsWith("$2a$") || hash.startsWith("$2b$") || hash.startsWith("$2y$"));
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
