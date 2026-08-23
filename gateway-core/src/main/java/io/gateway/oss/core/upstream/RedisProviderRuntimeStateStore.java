package io.gateway.oss.core.upstream;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.util.RedisStoreUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class RedisProviderRuntimeStateStore implements ProviderRuntimeStateStore {

    private static final Logger log = LoggerFactory.getLogger(RedisProviderRuntimeStateStore.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;

    public RedisProviderRuntimeStateStore(StringRedisTemplate redisTemplate,
                                          GatewayProperties properties,
                                          ObjectMapper objectMapper) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.keyPrefix = RedisStoreUtils.safePrefix(properties.getSharedState().getKeyPrefix()) + ":provider-runtime:";
    }

    @Override
    public ProviderRuntimeState get(String provider) {
        String raw = redisTemplate.opsForValue().get(key(provider));
        if (raw == null || raw.isBlank()) {
            return ProviderRuntimeState.unknown();
        }
        try {
            return objectMapper.readValue(raw, ProviderRuntimeState.class);
        } catch (JsonProcessingException e) {
            log.warn("provider_runtime_state_read_failed provider={} reason={}", provider, e.getMessage());
            return ProviderRuntimeState.unknown();
        }
    }

    @Override
    public void save(String provider, ProviderRuntimeState state) {
        try {
            redisTemplate.opsForValue().set(key(provider), objectMapper.writeValueAsString(state), Duration.ofDays(31));
        } catch (JsonProcessingException e) {
            log.warn("provider_runtime_state_write_failed provider={} reason={}", provider, e.getMessage());
        }
    }

    @Override
    public Map<String, ProviderRuntimeState> getAll() {
        Set<String> keys = scanKeys(keyPrefix + "*");
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        Map<String, ProviderRuntimeState> result = new LinkedHashMap<>();
        for (String key : keys) {
            result.put(key.substring(keyPrefix.length()), get(key.substring(keyPrefix.length())));
        }
        return result;
    }

    private String key(String provider) {
        return keyPrefix + provider;
    }

    private Set<String> scanKeys(String pattern) {
        return redisTemplate.execute((RedisConnection connection) -> {
            Set<String> keys = new LinkedHashSet<>();
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(1000).build();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            }
            return keys;
        });
    }


}
