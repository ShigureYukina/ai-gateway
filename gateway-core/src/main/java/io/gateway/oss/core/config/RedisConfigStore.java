package io.gateway.oss.core.config;

import io.gateway.oss.core.util.RedisStoreUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 基于 Redis Hash 的配置存储实现。
 * <p>
 * Redis key 格式：{@code {keyPrefix}:config:{configType}}
 * hash field = config key，hash value = JSON 字符串。
 * </p>
 * <p>
 * 使用 {@code opsForHash()} 管理某 configType 下的所有配置，
 * 比多个独立 key 更高效（单次 HGETALL 即可加载全部）。
 * </p>
 */
public class RedisConfigStore implements ConfigStore {

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final Scheduler scheduler;

    public RedisConfigStore(StringRedisTemplate redisTemplate,
                            GatewayProperties properties,
                            Scheduler scheduler) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyPrefix = RedisStoreUtils.safePrefix(properties.getSharedState().getKeyPrefix());
        this.scheduler = Objects.requireNonNull(scheduler);
    }

    @Override
    public Mono<Void> save(String configType, String key, String jsonValue) {
        return Mono.fromRunnable(() ->
                redisTemplate.opsForHash().put(redisKey(configType), key, jsonValue)
        ).subscribeOn(scheduler).then();
    }

    @Override
    public Mono<String> load(String configType, String key) {
        return Mono.fromCallable(() -> {
            Object value = redisTemplate.opsForHash().get(redisKey(configType), key);
            return value != null ? value.toString() : null;
        }).subscribeOn(scheduler);
    }

    @Override
    public Mono<Void> delete(String configType, String key) {
        return Mono.fromRunnable(() ->
                redisTemplate.opsForHash().delete(redisKey(configType), key)
        ).subscribeOn(scheduler).then();
    }

    @Override
    public Mono<Map<String, String>> loadAll(String configType) {
        return Mono.fromCallable(() -> {
            Map<Object, Object> raw = redisTemplate.opsForHash().entries(redisKey(configType));
            if (raw.isEmpty()) {
                return Collections.<String, String>emptyMap();
            }
            Map<String, String> result = new HashMap<>(raw.size());
            raw.forEach((k, v) -> result.put(k.toString(), v.toString()));
            return result;
        }).subscribeOn(scheduler);
    }

    @Override
    public Mono<Boolean> saveIfAbsentOrReplaceExpired(String configType,
                                                      String key,
                                                      String jsonValue,
                                                      Duration ttl) {
        return setIfAbsent(namespacedKey(configType + ":" + key), jsonValue, ttl);
    }

    /**
     * 使用独立字符串 key + TTL 持久化一次性状态，适合 refresh token 原子消费。
     */
    public Mono<Boolean> setIfAbsent(String key, String value, Duration ttl) {
        return Mono.fromCallable(() -> Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, value, ttl)))
                .subscribeOn(scheduler);
    }

    public Mono<Void> set(String key, String value, Duration ttl) {
        return Mono.fromRunnable(() -> redisTemplate.opsForValue().set(key, value, ttl))
                .subscribeOn(scheduler)
                .then();
    }

    public Mono<String> get(String key) {
        return Mono.fromCallable(() -> redisTemplate.opsForValue().get(key))
                .subscribeOn(scheduler);
    }

    public Mono<Void> deleteKey(String key) {
        return Mono.fromRunnable(() -> redisTemplate.delete(key))
                .subscribeOn(scheduler)
                .then();
    }

    public String namespacedKey(String suffix) {
        return keyPrefix + ":" + suffix;
    }

    private String redisKey(String configType) {
        return keyPrefix + ":config:" + configType;
    }

}
