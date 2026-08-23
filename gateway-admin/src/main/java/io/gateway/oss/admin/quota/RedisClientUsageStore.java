package io.gateway.oss.admin.quota;

import io.gateway.oss.core.contract.SystemConfigView;
import io.gateway.oss.core.util.RedisStoreUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

public class RedisClientUsageStore implements ClientUsageStore {

    private static final String CHECK_AND_RECORD_LUA = """
            local current = redis.call('GET', KEYS[1])
            local used = 0
            if current then
                used = tonumber(current) or 0
            end
            if used + tonumber(ARGV[1]) > tonumber(ARGV[2]) then
                return -1
            end
            local tokens = redis.call('INCRBY', KEYS[1], ARGV[1])
            local requests = redis.call('INCR', KEYS[2])
            if tokens == tonumber(ARGV[1]) then
                redis.call('EXPIRE', KEYS[1], ARGV[3])
            end
            if requests == 1 then
                redis.call('EXPIRE', KEYS[2], ARGV[3])
            end
            return tokens
            """;

    private static final String CHECK_AND_RECORD_MONTHLY_LUA = """
            local current = redis.call('GET', KEYS[1])
            local used = 0
            if current then
                used = tonumber(current) or 0
            end
            if used + tonumber(ARGV[1]) > tonumber(ARGV[2]) then
                return -1
            end
            local tokens = redis.call('INCRBY', KEYS[1], ARGV[1])
            if tokens == tonumber(ARGV[1]) then
                redis.call('EXPIRE', KEYS[1], ARGV[3])
            end
            return tokens
            """;

    private final DefaultRedisScript<Long> checkAndRecordScript =
            new DefaultRedisScript<>(CHECK_AND_RECORD_LUA, Long.class);
    private final DefaultRedisScript<Long> checkAndRecordMonthlyScript =
            new DefaultRedisScript<>(CHECK_AND_RECORD_MONTHLY_LUA, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;

    public RedisClientUsageStore(StringRedisTemplate redisTemplate, SystemConfigView configView) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyPrefix = RedisStoreUtils.safePrefix(configView.getSharedState().getKeyPrefix()) + ":usage:";
    }

    @Override
    public long currentDailyUsage(String clientId, Instant now) {
        String value = redisTemplate.opsForValue().get(dayKey(clientId, now));
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
    public long currentMonthlyUsage(String clientId, Instant now) {
        String value = redisTemplate.opsForValue().get(monthKey(clientId, now));
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
    public void addDailyUsage(String clientId, long tokens, Instant now) {
        if (tokens <= 0) {
            return;
        }
        String key = dayKey(clientId, now);
        Long current = redisTemplate.opsForValue().increment(key, tokens);
        if (current != null && current == tokens) {
            redisTemplate.expire(key, ttlToNextUtcDay(now));
        }
    }

    @Override
    public void addMonthlyUsage(String clientId, long tokens, Instant now) {
        if (tokens <= 0) {
            return;
        }
        String key = monthKey(clientId, now);
        Long current = redisTemplate.opsForValue().increment(key, tokens);
        if (current != null && current == tokens) {
            redisTemplate.expire(key, ttlToNextUtcMonth(now));
        }
    }

    @Override
    public long currentDailyRequestCount(String clientId, Instant now) {
        String value = redisTemplate.opsForValue().get(requestCountKey(clientId, now));
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
    public void addDailyRequestCount(String clientId, Instant now) {
        String key = requestCountKey(clientId, now);
        Long current = redisTemplate.opsForValue().increment(key, 1);
        if (current != null && current == 1) {
            redisTemplate.expire(key, ttlToNextUtcDay(now));
        }
    }

    @Override
    public long checkAndRecord(String clientId, long tokens, long dailyQuota, Instant now) {
        if (tokens <= 0) {
            // skip quota check, just record request count
            addDailyRequestCount(clientId, now);
            return currentDailyUsage(clientId, now);
        }
        long ttlSeconds = ttlToNextUtcDay(now).getSeconds();
        Long result = redisTemplate.execute(
                checkAndRecordScript,
                List.of(dayKey(clientId, now), requestCountKey(clientId, now)),
                String.valueOf(tokens),
                String.valueOf(dailyQuota),
                String.valueOf(ttlSeconds));
        return result != null ? result : -1L;
    }

    @Override
    public long checkAndRecordMonthly(String clientId, long tokens, long monthlyQuota, Instant now) {
        if (tokens <= 0) {
            return currentMonthlyUsage(clientId, now);
        }
        long ttlSeconds = ttlToNextUtcMonth(now).getSeconds();
        Long result = redisTemplate.execute(
                checkAndRecordMonthlyScript,
                List.of(monthKey(clientId, now)),
                String.valueOf(tokens),
                String.valueOf(monthlyQuota),
                String.valueOf(ttlSeconds));
        return result != null ? result : -1L;
    }

    private String dayKey(String clientId, Instant now) {
        LocalDate day = now.atZone(ZoneOffset.UTC).toLocalDate();
        return keyPrefix + clientId + ":" + day;
    }

    private String monthKey(String clientId, Instant now) {
        LocalDate month = now.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        return keyPrefix + "month:" + clientId + ":" + month;
    }

    private String requestCountKey(String clientId, Instant now) {
        LocalDate day = now.atZone(ZoneOffset.UTC).toLocalDate();
        return keyPrefix + "req:" + clientId + ":" + day;
    }

    private Duration ttlToNextUtcDay(Instant now) {
        ZonedDateTime zdt = now.atZone(ZoneOffset.UTC);
        ZonedDateTime next = zdt.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC);
        Duration ttl = Duration.between(zdt, next);
        if (ttl.isNegative() || ttl.isZero()) {
            return Duration.ofSeconds(1);
        }
        return ttl;
    }

    private Duration ttlToNextUtcMonth(Instant now) {
        ZonedDateTime zdt = now.atZone(ZoneOffset.UTC);
        ZonedDateTime next = zdt.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(ZoneOffset.UTC);
        Duration ttl = Duration.between(zdt, next);
        if (ttl.isNegative() || ttl.isZero()) {
            return Duration.ofSeconds(1);
        }
        return ttl;
    }

}
