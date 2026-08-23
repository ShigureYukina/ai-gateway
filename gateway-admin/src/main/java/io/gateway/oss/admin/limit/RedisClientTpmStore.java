package io.gateway.oss.admin.limit;

import io.gateway.oss.core.contract.SystemConfigView;
import io.gateway.oss.core.util.RedisStoreUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

public class RedisClientTpmStore implements ClientTpmStore {

    private static final String RESERVE_LUA = """
            local current = redis.call('GET', KEYS[1])
            local used = 0
            if current then
                used = tonumber(current) or 0
            end
            if used + tonumber(ARGV[1]) > tonumber(ARGV[2]) then
                return -1
            end
            local next = redis.call('INCRBY', KEYS[1], ARGV[1])
            if next == tonumber(ARGV[1]) then
                redis.call('EXPIRE', KEYS[1], ARGV[3])
            end
            return next
            """;

    private final DefaultRedisScript<Long> reserveScript = new DefaultRedisScript<>(RESERVE_LUA, Long.class);
    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;

    public RedisClientTpmStore(StringRedisTemplate redisTemplate, SystemConfigView configView) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyPrefix = RedisStoreUtils.safePrefix(configView.getSharedState().getKeyPrefix()) + ":tpm:";
    }

    @Override
    public long currentMinuteUsage(String clientId, Instant now) {
        String value = redisTemplate.opsForValue().get(minuteKey(clientId, now));
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    @Override
    public long reserve(String clientId, long tokens, long tpmLimit, Instant now) {
        if (tokens <= 0) {
            return currentMinuteUsage(clientId, now);
        }
        Long result = redisTemplate.execute(
                reserveScript,
                List.of(minuteKey(clientId, now)),
                String.valueOf(tokens),
                String.valueOf(tpmLimit),
                String.valueOf(ttlToNextUtcMinute(now).getSeconds())
        );
        return result != null ? result : -1L;
    }

    @Override
    public void adjust(String clientId, long deltaTokens, Instant now) {
        if (deltaTokens == 0) {
            return;
        }
        String key = minuteKey(clientId, now);
        Long next = redisTemplate.opsForValue().increment(key, deltaTokens);
        if (next != null && Math.abs(next) == Math.abs(deltaTokens)) {
            redisTemplate.expire(key, ttlToNextUtcMinute(now));
        }
        if (next != null && next < 0) {
            redisTemplate.opsForValue().set(key, "0", ttlToNextUtcMinute(now));
        }
    }

    private String minuteKey(String clientId, Instant now) {
        LocalDateTime minute = LocalDateTime.ofInstant(now, ZoneOffset.UTC).withSecond(0).withNano(0);
        return keyPrefix + clientId + ":" + minute;
    }

    private Duration ttlToNextUtcMinute(Instant now) {
        LocalDateTime minute = LocalDateTime.ofInstant(now, ZoneOffset.UTC).withSecond(0).withNano(0);
        Instant next = minute.plusMinutes(1).toInstant(ZoneOffset.UTC);
        Duration ttl = Duration.between(now, next);
        return ttl.isNegative() || ttl.isZero() ? Duration.ofSeconds(1) : ttl;
    }

}
