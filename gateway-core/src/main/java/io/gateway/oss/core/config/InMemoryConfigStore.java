package io.gateway.oss.core.config;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 {@link ConcurrentHashMap} 的进程内配置存储。
 * <p>
 * 用于无 Redis 时的 fallback，或测试场景。
 * </p>
 */
public class InMemoryConfigStore implements ConfigStore {

    /**
     * 外层 key = configType，内层 key = config key，value = JSON 字符串。
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> store = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> save(String configType, String key, String jsonValue) {
        store.computeIfAbsent(configType, k -> new ConcurrentHashMap<>()).put(key, jsonValue);
        return Mono.empty();
    }

    @Override
    public Mono<String> load(String configType, String key) {
        ConcurrentHashMap<String, String> typeMap = store.get(configType);
        if (typeMap == null) {
            return Mono.empty();
        }
        String value = typeMap.get(key);
        return value != null ? Mono.just(value) : Mono.empty();
    }

    @Override
    public Mono<Void> delete(String configType, String key) {
        ConcurrentHashMap<String, String> typeMap = store.get(configType);
        if (typeMap != null) {
            typeMap.remove(key);
        }
        return Mono.empty();
    }

    @Override
    public Mono<Map<String, String>> loadAll(String configType) {
        ConcurrentHashMap<String, String> typeMap = store.get(configType);
        if (typeMap == null || typeMap.isEmpty()) {
            return Mono.just(Collections.emptyMap());
        }
        return Mono.just(Map.copyOf(typeMap));
    }

    @Override
    public Mono<Boolean> saveIfAbsentOrReplaceExpired(String configType,
                                                      String key,
                                                      String jsonValue,
                                                      Duration ttl) {
        ConcurrentHashMap<String, String> typeMap = store.computeIfAbsent(configType, k -> new ConcurrentHashMap<>());
        final boolean[] inserted = {false};
        typeMap.compute(key, (ignored, existingValue) -> {
            if (existingValue == null || isExpired(existingValue)) {
                inserted[0] = true;
                return jsonValue;
            }
            return existingValue;
        });
        return Mono.just(inserted[0]);
    }

    private boolean isExpired(String jsonValue) {
        int expiresAtIndex = jsonValue.indexOf("\"expiresAt\":");
        if (expiresAtIndex < 0) {
            return true;
        }
        int valueStart = expiresAtIndex + 12;
        int valueEnd = valueStart;
        while (valueEnd < jsonValue.length() && Character.isDigit(jsonValue.charAt(valueEnd))) {
            valueEnd++;
        }
        if (valueEnd == valueStart) {
            return true;
        }
        try {
            long expiresAt = Long.parseLong(jsonValue.substring(valueStart, valueEnd));
            return expiresAt <= System.currentTimeMillis();
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /**
     * 清空所有数据（测试辅助方法）。
     */
    public void clear() {
        store.clear();
    }
}
