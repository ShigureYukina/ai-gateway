package io.gateway.oss.core.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class KeyHashUtil {
    private static final String PEPPER = System.getProperty("gateway.auth.key-pepper", "change-me-in-production");

    private KeyHashUtil() {
    }

    public static String hash(String plaintext) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(PEPPER.getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
