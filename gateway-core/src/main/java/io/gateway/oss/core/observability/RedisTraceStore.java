package io.gateway.oss.core.observability;

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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class RedisTraceStore implements TraceStore {

    private static final Logger log = LoggerFactory.getLogger(RedisTraceStore.class);
    private static final Duration TRACE_TTL = Duration.ofHours(24);
    private static final long MAX_RECENT = 500;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;
    private final String recentListKey;

    public RedisTraceStore(StringRedisTemplate redisTemplate,
                           GatewayProperties properties,
                           ObjectMapper objectMapper) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        String prefix = RedisStoreUtils.safePrefix(properties.getSharedState().getKeyPrefix());
        this.keyPrefix = prefix + ":trace:";
        this.recentListKey = prefix + ":trace:recent";
    }

    @Override
    public void save(TraceRecord record) {
        String id = record.requestId();
        if (id == null) return;
        try {
            String json = objectMapper.writeValueAsString(record);
            String key = keyPrefix + id;
            redisTemplate.opsForValue().set(key, json, TRACE_TTL);
            redisTemplate.opsForList().leftPush(recentListKey, id);
            redisTemplate.opsForList().trim(recentListKey, 0, MAX_RECENT - 1);
            redisTemplate.expire(recentListKey, TRACE_TTL);
        } catch (JsonProcessingException e) {
            log.warn("trace_serialize_failed requestId={} reason={}", id, e.getMessage());
        }
    }

    @Override
    public TraceRecord getByRequestId(String requestId) {
        if (requestId == null) return null;
        String raw = redisTemplate.opsForValue().get(keyPrefix + requestId);
        if (raw == null || raw.isBlank()) return null;
        try {
            return objectMapper.readValue(raw, TraceRecord.class);
        } catch (JsonProcessingException e) {
            log.warn("trace_deserialize_failed requestId={} reason={}", requestId, e.getMessage());
            return null;
        }
    }

    @Override
    public List<TraceRecord> getRecent(int limit) {
        List<String> ids = redisTemplate.opsForList().range(recentListKey, 0, Math.max(0, limit - 1));
        if (ids == null || ids.isEmpty()) return List.of();
        List<TraceRecord> result = new ArrayList<>();
        for (String id : ids) {
            TraceRecord record = getByRequestId(id);
            if (record != null) result.add(record);
        }
        return result;
    }

    @Override
    public void resetForTests() {
        Set<String> existingKeys = redisTemplate.keys(keyPrefix + "*");
        if (existingKeys != null) {
            for (String key : existingKeys) {
                redisTemplate.delete(key);
            }
        }
        redisTemplate.delete(recentListKey);
    }

    private Set<String> scanKeys(RedisConnection connection, String pattern) {
        Set<String> keys = new LinkedHashSet<>();
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();
        try (Cursor<byte[]> cursor = connection.scan(options)) {
            while (cursor.hasNext()) {
                keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
            }
        }
        return keys;
    }
}
