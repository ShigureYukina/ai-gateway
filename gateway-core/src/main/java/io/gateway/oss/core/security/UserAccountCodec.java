package io.gateway.oss.core.security;

import io.gateway.oss.core.contract.security.UserAccount;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * UserAccount 序列化/反序列化与 API 密钥工具方法的协作者。
 * <p>
 * 仅做数据变换；不持有存储、缓存或业务状态。
 * </p>
 */
public final class UserAccountCodec {

    static final String API_KEY_HASH_PREFIX = "v2:";

    private final ObjectMapper objectMapper;
    private final PasswordService passwordService;

    UserAccountCodec(ObjectMapper objectMapper, PasswordService passwordService) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.passwordService = Objects.requireNonNull(passwordService);
    }

    public static String maskApiKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        if (key.length() <= 4) {
            return "****";
        }
        return "****" + key.substring(key.length() - 4);
    }

    String generateApiKey() {
        return "gw-" + UUID.randomUUID().toString().replace("-", "");
    }

    String hashApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return apiKey;
        }
        if (isHashedApiKey(apiKey)) {
            return apiKey;
        }
        String deterministicHash = KeyHashUtil.hash(apiKey);
        String bcryptHash = passwordService.hashPassword(apiKey);
        return API_KEY_HASH_PREFIX + deterministicHash + ":" + bcryptHash;
    }

    boolean isHashedApiKey(String apiKey) {
        return extractDeterministicApiKeyHash(apiKey) != null;
    }

    String extractDeterministicApiKeyHash(String storedApiKey) {
        if (storedApiKey == null || storedApiKey.isBlank()) {
            return null;
        }
        if (storedApiKey.startsWith(API_KEY_HASH_PREFIX)) {
            int separatorIndex = storedApiKey.indexOf(':', API_KEY_HASH_PREFIX.length());
            if (separatorIndex > API_KEY_HASH_PREFIX.length()) {
                return storedApiKey.substring(API_KEY_HASH_PREFIX.length(), separatorIndex).toLowerCase(java.util.Locale.ROOT);
            }
            return null;
        }
        if (storedApiKey.length() == 64 && storedApiKey.chars().allMatch(ch ->
                (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F'))) {
            return storedApiKey.toLowerCase(java.util.Locale.ROOT);
        }
        return null;
    }

    String extractBcryptApiKeyHash(String storedApiKey) {
        if (storedApiKey == null || !storedApiKey.startsWith(API_KEY_HASH_PREFIX)) {
            return null;
        }
        int separatorIndex = storedApiKey.indexOf(':', API_KEY_HASH_PREFIX.length());
        if (separatorIndex < 0 || separatorIndex + 1 >= storedApiKey.length()) {
            return null;
        }
        return storedApiKey.substring(separatorIndex + 1);
    }

    String toJson(UserAccount account) {
        try {
            return objectMapper.writeValueAsString(account);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize UserAccount", e);
        }
    }

    String toJsonForStorage(UserAccount account) {
        return toJson(hashApiKeysIfNeeded(account));
    }

    UserAccount fromJson(String json) {
        try {
            return objectMapper.readValue(json, UserAccount.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    UserAccount hashApiKeysIfNeeded(UserAccount account) {
        if (account == null) {
            return null;
        }
        String hashedPrimaryApiKey = hashApiKey(account.apiKey());
        List<UserAccount.ApiKeyRecord> hashedApiKeys = (account.apiKeys() == null ? List.<UserAccount.ApiKeyRecord>of() : account.apiKeys())
                .stream()
                .map(key -> new UserAccount.ApiKeyRecord(
                        key.keyId(),
                        key.name(),
                        hashApiKey(key.apiKey()),
                        key.allowedModels(),
                        key.enabled(),
                        key.createdAt(),
                        key.lastUsedAt(),
                        key.requestCount(),
                        key.expiresAt()))
                .toList();
        return new UserAccount(
                account.username(),
                account.passwordHash(),
                account.role(),
                hashedPrimaryApiKey,
                account.limits(),
                account.allowedModels(),
                account.displayName(),
                account.email(),
                hashedApiKeys,
                account.createdAt(),
                account.tokenVersion(),
                account.frozen(),
                account.frozenAt());
    }
}
