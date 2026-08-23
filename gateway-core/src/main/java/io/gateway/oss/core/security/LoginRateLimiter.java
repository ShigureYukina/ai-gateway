package io.gateway.oss.core.security;

import io.gateway.oss.core.config.Backend;
import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.error.GatewayException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Login rate limiter with support for in-memory and Redis backends.
 * <p>
 * When {@code GatewayProperties.sharedState.backend} is {@code REDIS} or {@code HYBRID},
 * delegates to a Redis-based implementation for distributed rate limiting.
 * Otherwise falls back to an in-memory ConcurrentHashMap implementation.
 */
public class LoginRateLimiter {

    private static final int MAX_ATTEMPTS = 10;
    private static final long WINDOW_SECONDS = 300;

    private final LoginRateLimiterBackend backend;

    /**
     * Default constructor for in-memory only (backward compatible).
     */
    public LoginRateLimiter() {
        this.backend = new InMemoryLoginRateLimiterBackend();
    }

    /**
     * Constructor that selects backend based on GatewayProperties.
     * When sharedState.backend is REDIS or HYBRID, uses Redis;
     * otherwise uses in-memory.
     */
    public LoginRateLimiter(GatewayProperties properties, StringRedisTemplate redisTemplate) {
        Objects.requireNonNull(properties, "properties must not be null");
        Backend backendType = properties.getSharedState().getBackend();
        if (backendType == Backend.REDIS || backendType == Backend.HYBRID) {
            Objects.requireNonNull(redisTemplate, "redisTemplate must not be null for REDIS/HYBRID backend");
            this.backend = new RedisLoginRateLimiterBackend(redisTemplate, properties);
        } else {
            this.backend = new InMemoryLoginRateLimiterBackend();
        }
    }

    public void recordFailure(String username) {
        backend.recordFailure(username);
    }

    public void check(String username) {
        backend.check(username);
    }

    public void clear(String username) {
        backend.clear(username);
    }

    // ─── Backend interface ───

    private interface LoginRateLimiterBackend {
        void recordFailure(String username);
        void check(String username);
        void clear(String username);
    }

    // ─── In-memory implementation ───

    private static class InMemoryLoginRateLimiterBackend implements LoginRateLimiterBackend {
        private final ConcurrentHashMap<String, Window> attemptsByUsername = new ConcurrentHashMap<>();

        @Override
        public void recordFailure(String username) {
            Instant now = Instant.now();
            attemptsByUsername.compute(username, (key, w) -> {
                if (w == null || w.start.plusSeconds(WINDOW_SECONDS).isBefore(now)) {
                    return new Window(now, 1);
                }
                return new Window(w.start, w.count + 1);
            });
        }

        @Override
        public void check(String username) {
            Window w = attemptsByUsername.get(username);
            if (w == null) return;
            Instant now = Instant.now();
            if (w.start.plusSeconds(WINDOW_SECONDS).isBefore(now)) {
                attemptsByUsername.remove(username);
                return;
            }
            if (w.count >= MAX_ATTEMPTS) {
                throw new GatewayException(HttpStatus.TOO_MANY_REQUESTS,
                        "login_rate_limited",
                        "Too many login attempts, please try again later");
            }
        }

        @Override
        public void clear(String username) {
            attemptsByUsername.remove(username);
        }

        private record Window(Instant start, int count) {}
    }

    // ─── Redis implementation ───

    private static class RedisLoginRateLimiterBackend implements LoginRateLimiterBackend {

        private static final String INCR_AND_EXPIRE_LUA = """
                local current = redis.call('INCR', KEYS[1])
                if current == 1 then
                    redis.call('EXPIRE', KEYS[1], ARGV[1])
                end
                return current
                """;

        private final DefaultRedisScript<Long> incrScript =
                new DefaultRedisScript<>(INCR_AND_EXPIRE_LUA, Long.class);

        private final StringRedisTemplate redisTemplate;
        private final String keyPrefix;

        RedisLoginRateLimiterBackend(StringRedisTemplate redisTemplate, GatewayProperties properties) {
            this.redisTemplate = redisTemplate;
            String prefix = properties.getSharedState().getKeyPrefix();
            String base = (prefix != null && !prefix.isBlank()) ? prefix : "gateway";
            String resolvedPrefix = base + ":login-limit:";
            this.keyPrefix = resolvedPrefix;
        }

        @Override
        public void recordFailure(String username) {
            String key = keyPrefix + username;
            Long count = redisTemplate.execute(incrScript, List.of(key),
                    String.valueOf(WINDOW_SECONDS));
            // count is returned but we don't need to check here — check() does that
        }

        @Override
        public void check(String username) {
            String key = keyPrefix + username;
            String val = redisTemplate.opsForValue().get(key);
            if (val == null) return;
            int count;
            try {
                count = Integer.parseInt(val);
            } catch (NumberFormatException e) {
                return;
            }
            if (count >= MAX_ATTEMPTS) {
                throw new GatewayException(HttpStatus.TOO_MANY_REQUESTS,
                        "login_rate_limited",
                        "Too many login attempts, please try again later");
            }
        }

        @Override
        public void clear(String username) {
            String key = keyPrefix + username;
            redisTemplate.delete(key);
        }
    }
}
