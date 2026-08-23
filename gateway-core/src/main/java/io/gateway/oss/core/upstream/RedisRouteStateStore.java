package io.gateway.oss.core.upstream;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.ResilienceConfig;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class RedisRouteStateStore implements RouteStateStore {

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;

    public RedisRouteStateStore(StringRedisTemplate redisTemplate, GatewayProperties properties) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyPrefix = sanitizePrefix(properties.getSharedState().getKeyPrefix());
    }

    @Override
    public boolean isAvailable(String routeId, Instant now) {
        String openKey = openUntilKey(routeId);
        String raw = redisTemplate.opsForValue().get(openKey);
        if (raw == null || raw.isBlank()) {
            return true;
        }
        long openUntilEpochMs = Long.parseLong(raw);
        return now.toEpochMilli() >= openUntilEpochMs;
    }

    @Override
    public void recordSuccess(String routeId) {
        redisTemplate.delete(failureKey(routeId));
        redisTemplate.delete(openUntilKey(routeId));
    }

    @Override
    public void recordRetryableFailure(String routeId, Instant now, ResilienceConfig config) {
        String failureKey = failureKey(routeId);
        long nowMs = now.toEpochMilli();
        long cutoffMs = now.minus(config.getFailureWindow()).toEpochMilli();
        String member = nowMs + ":" + UUID.randomUUID();

        redisTemplate.opsForZSet().removeRangeByScore(failureKey, Double.NEGATIVE_INFINITY, cutoffMs);
        redisTemplate.opsForZSet().add(failureKey, member, nowMs);
        redisTemplate.expire(failureKey, ttl(config.getFailureWindow().plus(config.getOpenDuration())));

        Long size = redisTemplate.opsForZSet().zCard(failureKey);
        if (size != null && size >= config.getRetryableFailureThreshold()) {
            Instant openUntil = now.plus(config.getOpenDuration());
            String openKey = openUntilKey(routeId);
            redisTemplate.opsForValue().set(openKey, Long.toString(openUntil.toEpochMilli()), ttl(config.getOpenDuration()));
        }
    }

    private String failureKey(String routeId) {
        return keyPrefix + ":resilience:" + routeId + ":failures";
    }

    private String openUntilKey(String routeId) {
        return keyPrefix + ":resilience:" + routeId + ":open-until";
    }

    private String sanitizePrefix(String value) {
        if (value == null || value.isBlank()) {
            return "gateway";
        }
        return value;
    }

    private Duration ttl(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return Duration.ofSeconds(1);
        }
        return duration;
    }
}
