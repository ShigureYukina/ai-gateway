package io.gateway.oss.core.security;

import io.gateway.oss.core.contract.security.UserAccount;
import io.gateway.oss.core.config.ConfigStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户账户 write-behind 刷盘缓冲区。
 */
@Component
final class DirtyAccountFlushBuffer {

    private static final Logger log = LoggerFactory.getLogger(DirtyAccountFlushBuffer.class);

    private final ConfigStore configStore;
    private final UserApiKeyService userApiKeyService;
    private final UserAccountCodec accountCodec;
    private final ConcurrentHashMap<String, UserAccount> dirtyAccounts = new ConcurrentHashMap<>();

    /**
     * 已删除用户墓碑：删除与 in-flight flush 并发时（审查 D5），flush 在保存前
     * 检查该标记，避免把已删账户写回存储。窗口从 load→save 收敛为 check→save。
     */
    private final Set<String> deletedUsernames = ConcurrentHashMap.newKeySet();

    DirtyAccountFlushBuffer(ConfigStore configStore,
                            ObjectMapper objectMapper,
                            PasswordService passwordService) {
        this.configStore = Objects.requireNonNull(configStore);
        this.accountCodec = new UserAccountCodec(objectMapper, passwordService);
        this.userApiKeyService = new UserApiKeyService(accountCodec::generateApiKey, this::normalizeAllowedModels, passwordService, accountCodec);
    }

    @PostConstruct
    void start() {
        log.info("DirtyAccountFlushBuffer started");
    }

    void markDirty(UserAccount account) {
        if (account != null) {
            dirtyAccounts.put(account.username(), account);
        }
    }

    void removeDirty(String username) {
        if (username != null) {
            dirtyAccounts.remove(username);
        }
    }

    void markDeleted(String username) {
        if (username != null) {
            deletedUsernames.add(username);
            dirtyAccounts.remove(username);
        }
    }

    boolean isMarkedDeleted(String username) {
        return username != null && deletedUsernames.contains(username);
    }

    void resetForTests() {
        dirtyAccounts.clear();
        deletedUsernames.clear();
    }

    @PreDestroy
    void shutdown() {
        flushDirtyAccounts(true);
        log.info("DirtyAccountFlushBuffer stopped");
    }

    @Scheduled(fixedDelay = 5000)
    private void flushDirtyAccounts() {
        flushDirtyAccounts(false);
    }

    void flushDirtyAccounts(boolean waitForCompletion) {
        List<Map.Entry<String, UserAccount>> toFlush = new ArrayList<>(dirtyAccounts.entrySet());
        for (Map.Entry<String, UserAccount> entry : toFlush) {
            Mono<Void> flush = flushDirtyAccount(entry.getKey(), entry.getValue());
            if (waitForCompletion) {
                flush.block();
            } else {
                flush.subscribe();
            }
        }
        if (!toFlush.isEmpty()) {
            log.debug("apikey_flush_batch count={}", toFlush.size());
        }
    }

    private Mono<Void> flushDirtyAccount(String username, UserAccount snapshot) {
        if (snapshot == null) {
            return Mono.empty();
        }
        return configStore.load(UserAccountService.CONFIG_TYPE, username)
                .mapNotNull(accountCodec::fromJson)
                .switchIfEmpty(Mono.empty())
                .flatMap(current -> {
                    if (deletedUsernames.contains(username)) {
                        // 用户已删除：不得把账户写回存储（D5）
                        return Mono.empty();
                    }
                    UserAccount merged = mergeUsageMetadata(current, snapshot);
                    return configStore.save(UserAccountService.CONFIG_TYPE, username, accountCodec.toJsonForStorage(merged));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(ignored -> dirtyAccounts.remove(username, snapshot))
                .doOnError(error -> log.warn("apikey_flush_failed username={} cause={}", username, error.toString()))
                .onErrorResume(error -> Mono.empty());
    }

    /**
     * 仅合并 API Key 使用元数据，避免 write-behind 覆盖并发直接写。
     */
    private UserAccount mergeUsageMetadata(UserAccount current, UserAccount dirty) {
        List<UserAccount.ApiKeyRecord> mergedKeys = userApiKeyService.safeApiKeys(current).stream().map(currentKey -> {
            UserAccount.ApiKeyRecord dirtyKey = userApiKeyService.safeApiKeys(dirty).stream()
                    .filter(key -> key.apiKey().equals(currentKey.apiKey()))
                    .findFirst()
                    .orElse(null);
            if (dirtyKey != null && dirtyKey.lastUsedAt() != null
                    && (currentKey.lastUsedAt() == null || dirtyKey.lastUsedAt() > currentKey.lastUsedAt())) {
                return new UserAccount.ApiKeyRecord(
                        currentKey.keyId(), currentKey.name(), currentKey.apiKey(), currentKey.allowedModels(), currentKey.enabled(),
                        currentKey.createdAt(), dirtyKey.lastUsedAt(), dirtyKey.requestCount(), currentKey.expiresAt());
            }
            return currentKey;
        }).toList();
        return new UserAccount(
                current.username(), current.passwordHash(), current.role(), current.apiKey(),
                current.limits(), current.allowedModels(), current.displayName(), current.email(), mergedKeys, current.createdAt(),
                current.tokenVersion(), current.frozen(), current.frozenAt());
    }

    private Set<String> normalizeAllowedModels(Set<String> allowedModels) {
        if (allowedModels == null || allowedModels.isEmpty()) {
            return Set.of();
        }
        return allowedModels.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
