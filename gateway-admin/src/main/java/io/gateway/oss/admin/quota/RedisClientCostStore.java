package io.gateway.oss.admin.quota;

import io.gateway.oss.core.contract.SystemConfigView;
import io.gateway.oss.core.util.RedisStoreUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

public class RedisClientCostStore implements ClientCostStore {

    private static final int COST_SCALE = 6;

    private static final String CHECK_AND_RECORD_LUA = """
            local current = redis.call('GET', KEYS[1])
            local used = 0
            if current then
                used = tonumber(current) or 0
            end
            if used + tonumber(ARGV[1]) > tonumber(ARGV[2]) then
                return -1
            end
            local newTotal = redis.call('INCRBY', KEYS[1], ARGV[1])
            if newTotal == tonumber(ARGV[1]) then
                redis.call('EXPIRE', KEYS[1], ARGV[3])
            end
            return newTotal
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
            local newTotal = redis.call('INCRBY', KEYS[1], ARGV[1])
            if newTotal == tonumber(ARGV[1]) then
                redis.call('EXPIRE', KEYS[1], ARGV[3])
            end
            return newTotal
            """;

    private final DefaultRedisScript<Long> checkAndRecordScript =
            new DefaultRedisScript<>(CHECK_AND_RECORD_LUA, Long.class);
    private final DefaultRedisScript<Long> checkAndRecordMonthlyScript =
            new DefaultRedisScript<>(CHECK_AND_RECORD_MONTHLY_LUA, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;

    public RedisClientCostStore(StringRedisTemplate redisTemplate, SystemConfigView configView) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyPrefix = RedisStoreUtils.safePrefix(configView.getSharedState().getKeyPrefix()) + ":cost:";
    }

    @Override
    public BigDecimal currentDailyCost(String clientId, Instant now) {
        String value = redisTemplate.opsForValue().get(dayKey(clientId, now));
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value).movePointLeft(COST_SCALE);
        } catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }

    @Override
    public BigDecimal currentMonthlyCost(String clientId, Instant now) {
        String value = redisTemplate.opsForValue().get(monthKey(clientId, now));
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value).movePointLeft(COST_SCALE);
        } catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }

    @Override
    public void addDailyCost(String clientId, BigDecimal cost, Instant now) {
        if (cost == null || cost.signum() <= 0) {
            return;
        }
        String key = dayKey(clientId, now);
        long deltaMicros = cost.setScale(COST_SCALE, RoundingMode.HALF_UP).movePointRight(COST_SCALE).longValueExact();
        Long next = redisTemplate.opsForValue().increment(key, deltaMicros);
        if (next != null && next == deltaMicros) {
            redisTemplate.expire(key, ttlToNextUtcDay(now));
        }
    }

    @Override
    public void addMonthlyCost(String clientId, BigDecimal cost, Instant now) {
        if (cost == null || cost.signum() <= 0) {
            return;
        }
        String key = monthKey(clientId, now);
        long deltaMicros = cost.setScale(COST_SCALE, RoundingMode.HALF_UP).movePointRight(COST_SCALE).longValueExact();
        Long next = redisTemplate.opsForValue().increment(key, deltaMicros);
        if (next != null && next == deltaMicros) {
            redisTemplate.expire(key, ttlToNextUtcMonth(now));
        }
    }

    @Override
    public long checkAndRecord(String clientId, long costMicros, long dailyBudgetMicros, Instant now) {
        if (costMicros <= 0) {
            return currentDailyCostMicros(clientId, now);
        }
        long ttlSeconds = ttlToNextUtcDay(now).getSeconds();
        Long result = redisTemplate.execute(
                checkAndRecordScript,
                List.of(dayKey(clientId, now)),
                String.valueOf(costMicros),
                String.valueOf(dailyBudgetMicros),
                String.valueOf(ttlSeconds));
        return result != null ? result : -1L;
    }

    @Override
    public long checkAndRecordMonthly(String clientId, long costMicros, long monthlyBudgetMicros, Instant now) {
        if (costMicros <= 0) {
            return currentMonthlyCostMicros(clientId, now);
        }
        long ttlSeconds = ttlToNextUtcMonth(now).getSeconds();
        Long result = redisTemplate.execute(
                checkAndRecordMonthlyScript,
                List.of(monthKey(clientId, now)),
                String.valueOf(costMicros),
                String.valueOf(monthlyBudgetMicros),
                String.valueOf(ttlSeconds));
        return result != null ? result : -1L;
    }

    private long currentDailyCostMicros(String clientId, Instant now) {
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

    private long currentMonthlyCostMicros(String clientId, Instant now) {
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

    private String dayKey(String clientId, Instant now) {
        LocalDate day = now.atZone(ZoneOffset.UTC).toLocalDate();
        return keyPrefix + clientId + ":" + day;
    }

    private String monthKey(String clientId, Instant now) {
        LocalDate month = now.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        return keyPrefix + "month:" + clientId + ":" + month;
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
