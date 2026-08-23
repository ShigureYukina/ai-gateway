package io.gateway.oss.admin.observability;

import io.gateway.oss.core.contract.SystemConfigView;
import io.gateway.oss.core.util.RedisStoreUtils;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class RedisAggregateMetricStore implements AggregateMetricStore {

    private static final int COST_SCALE = 6;

    /**
     * Lua script for atomic multi-field hash increment.
     * KEYS[1] = hash key
     * ARGV[1] = displayName (empty string to skip)
     * ARGV[2] = requests delta
     * ARGV[3] = tokens delta
     * ARGV[4] = costMicros delta
     * ARGV[5] = TTL seconds
     */
    private static final String RECORD_BUCKET_LUA = """
            if ARGV[1] ~= '' then
              redis.call('HSET', KEYS[1], 'displayName', ARGV[1])
            end
            redis.call('HINCRBY', KEYS[1], 'requests', ARGV[2])
            redis.call('HINCRBY', KEYS[1], 'tokens', ARGV[3])
            redis.call('HINCRBY', KEYS[1], 'costMicros', ARGV[4])
            redis.call('EXPIRE', KEYS[1], ARGV[5])
            return OK
            """;

    private final StringRedisTemplate redisTemplate;
    private final HashOperations<String, String, String> hashOps;
    private final String keyPrefix;
    private final DefaultRedisScript<String> recordBucketScript;

    public RedisAggregateMetricStore(StringRedisTemplate redisTemplate, SystemConfigView configView) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.hashOps = redisTemplate.opsForHash();
        this.keyPrefix = RedisStoreUtils.safePrefix(configView.getSharedState().getKeyPrefix()) + ":reporting:";
        this.recordBucketScript = new DefaultRedisScript<>();
        this.recordBucketScript.setScriptText(RECORD_BUCKET_LUA);
        this.recordBucketScript.setResultType(String.class);
    }

    @Override
    public void record(String dimensionType,
                       String dimensionKey,
                       String displayName,
                       long requests,
                       long tokens,
                       BigDecimal costUsd,
                       Instant now) {
        recordBucket(dayKey(dimensionType, dimensionKey, now), displayName, requests, tokens, costUsd, ttlToNextUtcDay(now));
        recordBucket(monthKey(dimensionType, dimensionKey, now), displayName, requests, tokens, costUsd, ttlToNextUtcMonth(now));
    }

    @Override
    public List<AggregateMetric> getDaily(String dimensionType, LocalDate day) {
        return collect(dimensionType, bucketPrefix(dimensionType, day.toString()));
    }

    @Override
    public List<AggregateMetric> getMonthly(String dimensionType, YearMonth month) {
        return collect(dimensionType, bucketPrefix(dimensionType, month.toString()));
    }

    private void recordBucket(String key,
                              String displayName,
                              long requests,
                              long tokens,
                              BigDecimal costUsd,
                              Duration ttl) {
        long costMicros = (costUsd == null ? BigDecimal.ZERO : costUsd)
                .setScale(COST_SCALE, RoundingMode.HALF_UP)
                .movePointRight(COST_SCALE)
                .longValue();
        String name = (displayName != null && !displayName.isBlank()) ? displayName : "";
        long ttlSeconds = Math.max(1, ttl.getSeconds());
        redisTemplate.execute(recordBucketScript,
                List.of(key),
                name,
                String.valueOf(requests),
                String.valueOf(tokens),
                String.valueOf(costMicros),
                String.valueOf(ttlSeconds));
    }

    private List<AggregateMetric> collect(String dimensionType, String prefix) {
        Set<String> keys = scanKeys(prefix + "*");
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<AggregateMetric> result = new ArrayList<>();
        for (String key : new LinkedHashSet<>(keys)) {
            Map<String, String> values = hashOps.entries(key);
            if (values == null || values.isEmpty()) {
                continue;
            }
            ParsedKey parsed = parse(key);
            if (parsed == null || !dimensionType.equals(parsed.dimensionType())) {
                continue;
            }
            result.add(new AggregateMetric(
                    parsed.dimensionType(),
                    parsed.dimensionKey(),
                    values.get("displayName"),
                    parseLong(values.get("requests")),
                    parseLong(values.get("tokens")),
                    parseCost(values.get("costMicros")),
                    parsed.bucket()
            ));
        }
        result.sort(Comparator.comparing(AggregateMetric::dimensionKey));
        return result;
    }

    private String dayKey(String dimensionType, String dimensionKey, Instant now) {
        return bucketPrefix(dimensionType, now.atZone(ZoneOffset.UTC).toLocalDate().toString()) + dimensionKey;
    }

    private String monthKey(String dimensionType, String dimensionKey, Instant now) {
        return bucketPrefix(dimensionType, YearMonth.from(now.atZone(ZoneOffset.UTC)).toString()) + dimensionKey;
    }

    private String bucketPrefix(String dimensionType, String bucket) {
        return keyPrefix + dimensionType + ":" + bucket + ":";
    }

    private ParsedKey parse(String key) {
        if (key == null || !key.startsWith(keyPrefix)) {
            return null;
        }
        String raw = key.substring(keyPrefix.length());
        String[] parts = raw.split(":", 3);
        if (parts.length != 3) {
            return null;
        }
        return new ParsedKey(parts[0], parts[2], parts[1]);
    }

    private long parseLong(String value) {
        try {
            return value == null ? 0L : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private BigDecimal parseCost(String value) {
        try {
            return value == null ? BigDecimal.ZERO : new BigDecimal(value).movePointLeft(COST_SCALE);
        } catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }

    private Duration ttlToNextUtcDay(Instant now) {
        Instant next = now.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return positive(Duration.between(now, next));
    }

    private Duration ttlToNextUtcMonth(Instant now) {
        Instant next = now.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return positive(Duration.between(now, next));
    }

    private Duration positive(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return Duration.ofSeconds(1);
        }
        return duration;
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



    private record ParsedKey(String dimensionType, String dimensionKey, String bucket) {
    }
}
