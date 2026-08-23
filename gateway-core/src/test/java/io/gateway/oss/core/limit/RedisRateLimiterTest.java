package io.gateway.oss.core.limit;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.error.GatewayException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisRateLimiterTest {

    @Test
    void shouldShareFixedWindowCounterAcrossLimiterInstances() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

        Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    List<String> keys = invocation.getArgument(1);
                    String key = keys.get(0);
                    Long count = counters.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
                    return count;
                });

        GatewayProperties properties = new GatewayProperties();
        properties.getLimit().setRequestsPerWindow(2);
        properties.getLimit().setWindow(Duration.ofMinutes(1));

        RedisRateLimiter limiterA = new RedisRateLimiter(redisTemplate, properties);
        RedisRateLimiter limiterB = new RedisRateLimiter(redisTemplate, properties);

        limiterA.check("demo-client");
        limiterB.check("demo-client");

        assertThrows(GatewayException.class, () -> limiterA.check("demo-client"));
    }

    @Test
    void check_returnsRateLimitStatus() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("3");
        when(redisTemplate.getExpire(anyString())).thenReturn(45L);

        GatewayProperties properties = new GatewayProperties();
        properties.getLimit().setRequestsPerWindow(10);
        properties.getLimit().setWindow(Duration.ofMinutes(1));

        RedisRateLimiter limiter = new RedisRateLimiter(redisTemplate, properties);
        RateLimitStatus status = limiter.getCurrentStatus("test-client");

        assertEquals(10, status.limit());
        assertEquals(7, status.remaining());
        assertTrue(status.resetEpochSeconds() > 0);
    }

    @Test
    void check_expiredKey_returnsFullRemaining() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("3");
        when(redisTemplate.getExpire(anyString())).thenReturn(null);

        GatewayProperties properties = new GatewayProperties();
        properties.getLimit().setRequestsPerWindow(10);
        properties.getLimit().setWindow(Duration.ofMinutes(1));

        RedisRateLimiter limiter = new RedisRateLimiter(redisTemplate, properties);
        RateLimitStatus status = limiter.getCurrentStatus("test-client");

        assertEquals(10, status.limit());
        assertEquals(7, status.remaining());
        assertEquals(0, status.resetEpochSeconds());
    }

    @Test
    void check_nonExistentKey_returnsFullLimit() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(redisTemplate.getExpire(anyString())).thenReturn(60L);

        GatewayProperties properties = new GatewayProperties();
        properties.getLimit().setRequestsPerWindow(10);
        properties.getLimit().setWindow(Duration.ofMinutes(1));

        RedisRateLimiter limiter = new RedisRateLimiter(redisTemplate, properties);
        RateLimitStatus status = limiter.getCurrentStatus("test-client");

        assertEquals(10, status.limit());
        assertEquals(10, status.remaining());
    }

    @Test
    void check_exceededLimit_returnsNegativeRemaining() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("15");
        when(redisTemplate.getExpire(anyString())).thenReturn(30L);

        GatewayProperties properties = new GatewayProperties();
        properties.getLimit().setRequestsPerWindow(10);
        properties.getLimit().setWindow(Duration.ofMinutes(1));

        RedisRateLimiter limiter = new RedisRateLimiter(redisTemplate, properties);
        RateLimitStatus status = limiter.getCurrentStatus("test-client");

        assertEquals(10, status.limit());
        assertEquals(0, status.remaining());
    }
}
