package io.gateway.oss.core.limit;

import io.gateway.oss.core.config.ClientConfig;
import io.gateway.oss.core.config.ClientLimits;
import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.util.RedisStoreUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public class RedisRateLimiter implements ClientRateLimiter {

    private static final String RATE_LIMIT_LUA = """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """;

    private final DefaultRedisScript<Long> rateLimitScript =
            new DefaultRedisScript<>(RATE_LIMIT_LUA, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final GatewayProperties properties;
    private final String keyPrefix;

    public RedisRateLimiter(StringRedisTemplate redisTemplate, GatewayProperties properties) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        GatewayProperties safeProperties = Objects.requireNonNull(properties);
        this.properties = safeProperties;
        this.keyPrefix = RedisStoreUtils.safePrefix(safeProperties.getSharedState().getKeyPrefix()) + ":limit:";
    }

    @Override
    public void check(String clientId) {
        int maxRequests = resolveMaxRequests(clientId);
        Duration window = resolveWindow(clientId);
        String key = keyPrefix + clientId;
        Long count = redisTemplate.execute(rateLimitScript, List.of(key),
                String.valueOf(window.getSeconds()));
        if (count != null && count > maxRequests) {
            throw new GatewayException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Request limit exceeded");
        }
    }

    private Duration resolveWindow(String clientId) {
        ClientLimits clientLimits = resolveClientLimits(clientId);
        if (clientLimits != null && clientLimits.getWindow() != null) {
            Duration w = clientLimits.getWindow();
            if (!w.isZero() && !w.isNegative()) return w;
        }
        Duration globalWindow = properties.getLimit().getWindow();
        if (globalWindow == null || globalWindow.isZero() || globalWindow.isNegative()) {
            return Duration.ofSeconds(1);
        }
        return globalWindow;
    }

    private int resolveMaxRequests(String clientId) {
        ClientLimits clientLimits = resolveClientLimits(clientId);
        if (clientLimits != null && clientLimits.getRequestsPerWindow() != null) {
            return clientLimits.getRequestsPerWindow();
        }
        return properties.getLimit().getRequestsPerWindow();
    }

    private ClientLimits resolveClientLimits(String clientId) {
        if (clientId == null) return null;
        var clients = properties.getClients();
        if (clients == null) return null;
        ClientConfig clientConfig = clients.get(clientId);
        if (clientConfig == null) return null;
        return clientConfig.getLimits();
    }

    @Override
    public RateLimitStatus getCurrentStatus(String clientId) {
        int maxRequests = properties.getLimit().getRequestsPerWindow();
        String key = keyPrefix + clientId;
        String val = redisTemplate.opsForValue().get(key);
        if (val == null) {
            long windowStart = System.currentTimeMillis() / 1000;
            long windowSec = properties.getLimit().getWindow().getSeconds();
            if (windowSec <= 0) windowSec = 60;
            return new RateLimitStatus(maxRequests, maxRequests, (windowStart / windowSec + 1) * windowSec);
        }
        int current = Integer.parseInt(val);
        int remaining = Math.max(0, maxRequests - current);
        Long ttl = redisTemplate.getExpire(key);
        long reset = ttl != null && ttl > 0
                ? Instant.now().plusSeconds(ttl).getEpochSecond()
                : 0;
        return new RateLimitStatus(maxRequests, remaining, reset);
    }

}
