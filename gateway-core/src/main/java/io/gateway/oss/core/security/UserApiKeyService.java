package io.gateway.oss.core.security;

import io.gateway.oss.core.contract.security.UserAccount;
import io.gateway.oss.core.error.GatewayException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * API Key 生命周期协作者。
 * <p>
 * 仅承接 key 记录的校验与数据变换；存储、缓存、索引刷新仍保留在 UserAccountService。
 * </p>
 */
final class UserApiKeyService {

    private final Supplier<String> apiKeyGenerator;
    private final Function<Set<String>, Set<String>> allowedModelsNormalizer;
    private final PasswordService passwordService;
    private final UserAccountCodec accountCodec;

    UserApiKeyService(Supplier<String> apiKeyGenerator,
                      Function<Set<String>, Set<String>> allowedModelsNormalizer,
                      PasswordService passwordService,
                      UserAccountCodec accountCodec) {
        this.apiKeyGenerator = Objects.requireNonNull(apiKeyGenerator);
        this.allowedModelsNormalizer = Objects.requireNonNull(allowedModelsNormalizer);
        this.passwordService = Objects.requireNonNull(passwordService);
        this.accountCodec = Objects.requireNonNull(accountCodec);
    }

    UserAccount upgradeLegacyIfNeeded(UserAccount account) {
        if (account == null) {
            return null;
        }
        if (account.apiKeys() != null && !account.apiKeys().isEmpty()) {
            return account;
        }
        if (account.apiKey() == null || account.apiKey().isBlank()) {
            return new UserAccount(account.username(), account.passwordHash(), account.role(), account.apiKey(), account.limits(),
                    account.allowedModels(), account.displayName(), account.email(), List.of(), account.createdAt(),
                    account.tokenVersion(), account.frozen(), account.frozenAt());
        }
        return new UserAccount(account.username(), account.passwordHash(), account.role(), account.apiKey(), account.limits(),
                account.allowedModels(), account.displayName(), account.email(),
                List.of(new UserAccount.ApiKeyRecord("primary", "default", account.apiKey(), Set.of(), true, account.createdAt(), null, 0L, null)),
                account.createdAt(), account.tokenVersion(), account.frozen(), account.frozenAt());
    }

    List<UserAccount.ApiKeyRecord> safeApiKeys(UserAccount account) {
        UserAccount upgraded = upgradeLegacyIfNeeded(account);
        return upgraded == null ? List.of() : upgraded.apiKeys();
    }

    boolean isApiKeyEnabled(UserAccount account, String apiKey) {
        return safeApiKeys(account).stream().anyMatch(k -> matchesApiKey(apiKey, k.apiKey()) && k.enabled());
    }

    UserAccount.ApiKeyRecord createApiKeyRecord(String name, Set<String> allowedModels) {
        long now = System.currentTimeMillis();
        return new UserAccount.ApiKeyRecord(
                UUID.randomUUID().toString(),
                (name == null || name.isBlank()) ? "default" : name,
                apiKeyGenerator.get(),
                allowedModelsNormalizer.apply(allowedModels),
                true,
                now,
                null,
                0L,
                null);
    }

    UserAccount addApiKey(UserAccount existing, UserAccount.ApiKeyRecord record) {
        List<UserAccount.ApiKeyRecord> updatedKeys = new ArrayList<>(safeApiKeys(existing));
        updatedKeys.add(record);
        return new UserAccount(existing.username(), existing.passwordHash(), existing.role(), existing.apiKey(), existing.limits(),
                existing.allowedModels(), existing.displayName(), existing.email(), updatedKeys, existing.createdAt(),
                existing.tokenVersion(), existing.frozen(), existing.frozenAt());
    }

    UserAccount deleteApiKey(UserAccount existing, String keyId) {
        List<UserAccount.ApiKeyRecord> existingKeys = safeApiKeys(existing);
        List<UserAccount.ApiKeyRecord> remaining = existingKeys.stream()
                .filter(k -> !k.keyId().equals(keyId))
                .toList();
        if (remaining.size() == existingKeys.size()) {
            throw new GatewayException(HttpStatus.NOT_FOUND, "key_not_found", "API key not found");
        }
        boolean deletedPrimaryLegacyKey = existingKeys.stream()
                .anyMatch(k -> k.keyId().equals(keyId)
                        && existing.apiKey() != null
                        && existing.apiKey().equals(k.apiKey()));
        return new UserAccount(existing.username(), existing.passwordHash(), existing.role(),
                deletedPrimaryLegacyKey ? null : existing.apiKey(), existing.limits(), existing.allowedModels(),
                existing.displayName(), existing.email(), remaining, existing.createdAt(), existing.tokenVersion(),
                existing.frozen(), existing.frozenAt());
    }

    UserAccount updateApiKey(UserAccount existing, String keyId, Boolean enabled, String name, Set<String> allowedModels) {
        boolean found = false;
        List<UserAccount.ApiKeyRecord> updatedKeys = new ArrayList<>();
        for (UserAccount.ApiKeyRecord key : safeApiKeys(existing)) {
            if (key.keyId().equals(keyId)) {
                found = true;
                boolean newEnabled = enabled != null ? enabled : key.enabled();
                String newName = (name != null && !name.isBlank()) ? name.trim() : key.name();
                Set<String> newModels = allowedModels != null ? allowedModelsNormalizer.apply(allowedModels) : key.allowedModels();
                updatedKeys.add(new UserAccount.ApiKeyRecord(key.keyId(), newName, key.apiKey(), newModels, newEnabled,
                        key.createdAt(), key.lastUsedAt(), key.requestCount(), key.expiresAt()));
            } else {
                updatedKeys.add(key);
            }
        }
        if (!found) {
            throw new GatewayException(HttpStatus.NOT_FOUND, "key_not_found", "API key not found");
        }
        return new UserAccount(existing.username(), existing.passwordHash(), existing.role(), existing.apiKey(), existing.limits(),
                existing.allowedModels(), existing.displayName(), existing.email(), updatedKeys, existing.createdAt(),
                existing.tokenVersion(), existing.frozen(), existing.frozenAt());
    }

    RotateApiKeyResult rotateApiKey(UserAccount existing, String keyId) {
        List<UserAccount.ApiKeyRecord> existingKeys = safeApiKeys(existing);
        UserAccount.ApiKeyRecord target = existingKeys.stream()
                .filter(k -> k.keyId().equals(keyId))
                .findFirst()
                .orElse(null);
        if (target == null) {
            throw new GatewayException(HttpStatus.NOT_FOUND, "key_not_found", "API key not found");
        }
        if (!target.enabled()) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "key_disabled", "Cannot rotate disabled key");
        }
        long now = System.currentTimeMillis();
        UserAccount.ApiKeyRecord newRecord = new UserAccount.ApiKeyRecord(
                UUID.randomUUID().toString(),
                target.name(),
                apiKeyGenerator.get(),
                target.allowedModels(),
                true,
                now,
                null,
                0L,
                null);
        List<UserAccount.ApiKeyRecord> updatedKeys = new ArrayList<>();
        for (UserAccount.ApiKeyRecord key : existingKeys) {
            if (key.keyId().equals(keyId)) {
                updatedKeys.add(newRecord);
            } else {
                updatedKeys.add(key);
            }
        }
        UserAccount updated = new UserAccount(existing.username(), existing.passwordHash(), existing.role(), existing.apiKey(), existing.limits(),
                existing.allowedModels(), existing.displayName(), existing.email(), updatedKeys, existing.createdAt(),
                existing.tokenVersion(), existing.frozen(), existing.frozenAt());
        return new RotateApiKeyResult(updated, newRecord);
    }

    UserAccount markApiKeyUsed(UserAccount existing, String apiKey, long now) {
        List<UserAccount.ApiKeyRecord> updatedKeys = safeApiKeys(existing).stream()
                .map(k -> matchesApiKey(apiKey, k.apiKey())
                        ? new UserAccount.ApiKeyRecord(k.keyId(), k.name(), k.apiKey(), k.allowedModels(), k.enabled(),
                        k.createdAt(), now, k.requestCount() + 1, k.expiresAt())
                        : k)
                .toList();
        return new UserAccount(existing.username(), existing.passwordHash(), existing.role(), existing.apiKey(), existing.limits(),
                existing.allowedModels(), existing.displayName(), existing.email(), updatedKeys, existing.createdAt(),
                existing.tokenVersion(), existing.frozen(), existing.frozenAt());
    }

    UserAccount.ApiKeyRecord findApiKeyRecord(UserAccount account, String apiKey) {
        if (account == null || apiKey == null || apiKey.isBlank()) {
            return null;
        }
        return safeApiKeys(account).stream().filter(k -> matchesApiKey(apiKey, k.apiKey())).findFirst().orElse(null);
    }

    private boolean matchesApiKey(String rawApiKey, String storedApiKey) {
        if (rawApiKey == null || rawApiKey.isBlank() || storedApiKey == null || storedApiKey.isBlank()) {
            return false;
        }
        String apiKeyHash = KeyHashUtil.hash(rawApiKey);
        String deterministicHash = accountCodec.extractDeterministicApiKeyHash(storedApiKey);
        if (deterministicHash != null) {
            if (!apiKeyHash.equals(deterministicHash)) {
                return false;
            }
            String bcryptHash = accountCodec.extractBcryptApiKeyHash(storedApiKey);
            return bcryptHash == null || passwordService.verifyPassword(rawApiKey, bcryptHash);
        }
        return apiKeyHash.equals(KeyHashUtil.hash(storedApiKey.trim())) || rawApiKey.equals(storedApiKey.trim());
    }

    record RotateApiKeyResult(UserAccount updatedAccount, UserAccount.ApiKeyRecord newRecord) {
    }
}
