package io.gateway.oss.core.security;

import io.gateway.oss.core.config.ConfigStore;
import io.gateway.oss.core.config.RedisConfigStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Objects;

@Service
public class RefreshTokenBlacklistService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenBlacklistService.class);
    private static final String CONFIG_TYPE = "refresh-token-blacklist";

    private final ConfigStore configStore;
    private final ObjectMapper objectMapper;

    public RefreshTokenBlacklistService(ConfigStore configStore, ObjectMapper objectMapper) {
        this.configStore = Objects.requireNonNull(configStore);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public Mono<Void> blacklist(String token, Claims claims) {
        String key = buildKey(token);
        long now = System.currentTimeMillis();
        long expiresAt = claims.getExpiration() != null ? claims.getExpiration().getTime() : now;
        BlacklistEntry entry = new BlacklistEntry(now, expiresAt, "logout");
        return saveEntry(key, entry)
                .doOnSuccess(unused -> log.info("refresh_token_blacklisted key={} expires_at={}", key, expiresAt));
    }

    /**
     * 原子消费 refresh token：仅首次消费成功。
     */
    public Mono<Boolean> consumeOnce(String token, Claims claims) {
        String key = buildKey(token);
        long now = System.currentTimeMillis();
        long expiresAt = claims.getExpiration() != null ? claims.getExpiration().getTime() : now;
        if (expiresAt <= now) {
            return Mono.just(false);
        }
        BlacklistEntry entry = new BlacklistEntry(now, expiresAt, "consumed");
        return saveIfAbsentOrReplaceExpired(key, entry)
                .doOnNext(consumed -> {
                    if (Boolean.TRUE.equals(consumed)) {
                        log.debug("refresh_token_consumed key={} expires_at={}", key, expiresAt);
                    }
                });
    }

    public Mono<Boolean> isBlacklisted(String token) {
        String key = buildKey(token);
        return loadEntry(key)
                .flatMap(json -> {
                    BlacklistEntry entry = fromJson(json);
                    if (entry == null) {
                        return Mono.just(false);
                    }
                    long now = System.currentTimeMillis();
                    if (entry.expiresAt() <= now) {
                        return deleteEntry(key).thenReturn(false);
                    }
                    return Mono.just(true);
                })
                .switchIfEmpty(Mono.just(false));
    }

    private Mono<Void> saveEntry(String key, BlacklistEntry entry) {
        String json = toJson(entry);
        if (configStore instanceof RedisConfigStore redisConfigStore) {
            // 审查 F3：必须写入与 consumeOnce 相同的命名空间键，否则 logout
            // 写入的键 refresh 的原子消费看不到，Redis 后端下 logout 形同虚设
            return redisConfigStore.set(redisConfigStore.namespacedKey(CONFIG_TYPE + ":" + key), json, ttl(entry.expiresAt())).then();
        }
        return configStore.save(CONFIG_TYPE, key, json);
    }

    private Mono<Boolean> saveIfAbsentOrReplaceExpired(String key, BlacklistEntry entry) {
        String json = toJson(entry);
        return configStore.saveIfAbsentOrReplaceExpired(CONFIG_TYPE, key, json, ttl(entry.expiresAt()));
    }

    private Mono<String> loadEntry(String key) {
        if (configStore instanceof RedisConfigStore redisConfigStore) {
            return redisConfigStore.get(redisConfigStore.namespacedKey(CONFIG_TYPE + ":" + key))
                    .switchIfEmpty(configStore.load(CONFIG_TYPE, key));
        }
        return configStore.load(CONFIG_TYPE, key);
    }

    private Mono<Void> deleteEntry(String key) {
        if (configStore instanceof RedisConfigStore redisConfigStore) {
            return redisConfigStore.deleteKey(redisConfigStore.namespacedKey(CONFIG_TYPE + ":" + key))
                    .then(configStore.delete(CONFIG_TYPE, key));
        }
        return configStore.delete(CONFIG_TYPE, key);
    }

    private boolean isExpiredJson(String json) {
        BlacklistEntry existing = fromJson(json);
        return existing == null || existing.expiresAt() <= System.currentTimeMillis();
    }

    private Duration ttl(long expiresAt) {
        long millis = Math.max(1000L, expiresAt - System.currentTimeMillis());
        return Duration.ofMillis(millis);
    }


    private String buildKey(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hashed[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String toJson(BlacklistEntry entry) {
        try {
            return objectMapper.writeValueAsString(entry);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize blacklist entry", e);
        }
    }

    private BlacklistEntry fromJson(String json) {
        try {
            return objectMapper.readValue(json, BlacklistEntry.class);
        } catch (JsonProcessingException e) {
            log.warn("refresh_token_blacklist_parse_failed error={}", e.getMessage());
            return null;
        }
    }

    private record BlacklistEntry(long blacklistedAt, long expiresAt, String reason) {
    }

}
