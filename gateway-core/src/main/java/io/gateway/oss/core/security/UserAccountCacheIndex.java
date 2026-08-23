package io.gateway.oss.core.security;

import io.gateway.oss.core.contract.security.UserAccount;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户账户缓存与 API Key 索引协作者。
 */
final class UserAccountCacheIndex {

    private final ConcurrentHashMap<String, String> apiKeyIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UserAccount> accountCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> deletedAccountVersions = new ConcurrentHashMap<>();

    private final UserApiKeyService userApiKeyService;
    private final UserAccountCodec accountCodec;

    UserAccountCacheIndex(UserApiKeyService userApiKeyService, UserAccountCodec accountCodec) {
        this.userApiKeyService = Objects.requireNonNull(userApiKeyService);
        this.accountCodec = Objects.requireNonNull(accountCodec);
    }

    UserAccount findCachedByUsername(String username) {
        return accountCache.get(username);
    }

    String findUsernameByApiKey(String apiKey) {
        String lookupKey = toApiKeyLookupKey(apiKey);
        return lookupKey == null ? null : apiKeyIndex.get(lookupKey);
    }

    int cachedAccountCount() {
        return accountCache.size();
    }

    boolean hasCachedAccounts() {
        return !accountCache.isEmpty();
    }

    java.util.Collection<UserAccount> cachedAccounts() {
        return accountCache.values();
    }

    boolean isDeletedUserWithOldToken(String username, int tokenVersion) {
        Integer deletedVersion = deletedAccountVersions.get(username);
        return deletedVersion != null && deletedVersion == tokenVersion;
    }

    boolean isDeleted(String username) {
        return deletedAccountVersions.containsKey(username);
    }

    void recordDeletedVersion(String username, int tokenVersion) {
        deletedAccountVersions.put(username, tokenVersion);
    }

    void refreshIndexes(UserAccount existing, UserAccount updatedRaw) {
        UserAccount updated = userApiKeyService.upgradeLegacyIfNeeded(updatedRaw);
        accountCache.put(updated.username(), updated);
        if (existing != null) {
            userApiKeyService.safeApiKeys(existing).forEach(key -> removeApiKeyIndex(key.apiKey()));
        }
        userApiKeyService.safeApiKeys(updated).forEach(key -> putApiKeyIndex(key.apiKey(), updated.username()));
    }

    void replaceCachedAccount(UserAccount account) {
        if (account != null) {
            accountCache.put(account.username(), account);
        }
    }

    void evictAccount(UserAccount existing) {
        if (existing == null) {
            return;
        }
        accountCache.remove(existing.username());
        userApiKeyService.safeApiKeys(existing).forEach(key -> removeApiKeyIndex(key.apiKey()));
    }

    void resetForTests() {
        apiKeyIndex.clear();
        accountCache.clear();
    }

    private void putApiKeyIndex(String apiKey, String username) {
        String lookupKey = toApiKeyLookupKey(apiKey);
        if (lookupKey != null) {
            apiKeyIndex.put(lookupKey, username);
        }
    }

    private void removeApiKeyIndex(String apiKey) {
        String lookupKey = toApiKeyLookupKey(apiKey);
        if (lookupKey != null) {
            apiKeyIndex.remove(lookupKey);
        }
    }

    private String toApiKeyLookupKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        String trimmed = apiKey.trim();
        String deterministicHash = accountCodec.extractDeterministicApiKeyHash(trimmed);
        if (deterministicHash != null) {
            return deterministicHash;
        }
        return KeyHashUtil.hash(trimmed);
    }
}
