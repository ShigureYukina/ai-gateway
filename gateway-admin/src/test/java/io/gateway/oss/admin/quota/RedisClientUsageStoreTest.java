package io.gateway.oss.admin.quota;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.Backend;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisClientUsageStoreTest {

    @Test
    void shouldShareDailyUsageAcrossStoreInstances() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = (ValueOperations<String, String>) mock(ValueOperations.class);
        Map<String, Long> counters = new ConcurrentHashMap<>();

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenAnswer(invocation -> {
            Long value = counters.get(invocation.getArgument(0));
            return value == null ? null : Long.toString(value);
        });
        when(valueOps.increment(anyString(), anyLong())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            long delta = invocation.getArgument(1);
            long next = counters.getOrDefault(key, 0L) + delta;
            counters.put(key, next);
            return next;
        });
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);

        GatewayProperties properties = new GatewayProperties();
        properties.getSharedState().setBackend(Backend.REDIS);
        properties.getSharedState().setKeyPrefix("quota-test");

        RedisClientUsageStore storeA = new RedisClientUsageStore(redisTemplate, properties);
        RedisClientUsageStore storeB = new RedisClientUsageStore(redisTemplate, properties);

        Instant now = Instant.parse("2026-04-27T10:00:00Z");
        storeA.addDailyUsage("client-1", 50, now);
        storeB.addDailyUsage("client-1", 20, now);

        assertEquals(70L, storeA.currentDailyUsage("client-1", now));
        assertEquals(70L, storeB.currentDailyUsage("client-1", now));
        verify(redisTemplate, times(1)).expire(argThat(key -> ((String) key).contains("quota-test") && ((String) key).contains("client-1")), any(Duration.class));
    }

    @Test
    void shouldBucketUsageByUtcDay() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = (ValueOperations<String, String>) mock(ValueOperations.class);
        Map<String, Long> counters = new ConcurrentHashMap<>();

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenAnswer(invocation -> {
            Long value = counters.get(invocation.getArgument(0));
            return value == null ? null : Long.toString(value);
        });
        when(valueOps.increment(anyString(), anyLong())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            long delta = invocation.getArgument(1);
            long next = counters.getOrDefault(key, 0L) + delta;
            counters.put(key, next);
            return next;
        });
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);

        GatewayProperties properties = new GatewayProperties();
        properties.getSharedState().setBackend(Backend.REDIS);
        properties.getSharedState().setKeyPrefix("quota-test");

        RedisClientUsageStore store = new RedisClientUsageStore(redisTemplate, properties);

        Instant day1 = Instant.parse("2026-04-27T23:59:00Z");
        Instant day2 = Instant.parse("2026-04-28T00:01:00Z");
        store.addDailyUsage("client-1", 40, day1);
        store.addDailyUsage("client-1", 10, day2);

        assertEquals(40L, store.currentDailyUsage("client-1", day1));
        assertEquals(10L, store.currentDailyUsage("client-1", day2));
    }

    @Test
    void shouldNotFailWhenExpireReturnsFalse() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = (ValueOperations<String, String>) mock(ValueOperations.class);
        Map<String, Long> counters = new ConcurrentHashMap<>();

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenAnswer(invocation -> {
            Long value = counters.get(invocation.getArgument(0));
            return value == null ? null : Long.toString(value);
        });
        when(valueOps.increment(anyString(), anyLong())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            long delta = invocation.getArgument(1);
            long next = counters.getOrDefault(key, 0L) + delta;
            counters.put(key, next);
            return next;
        });
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(false);

        GatewayProperties properties = new GatewayProperties();
        properties.getSharedState().setBackend(Backend.REDIS);
        properties.getSharedState().setKeyPrefix("quota-test");

        RedisClientUsageStore store = new RedisClientUsageStore(redisTemplate, properties);
        Instant now = Instant.parse("2026-04-27T10:00:00Z");

        store.addDailyUsage("client-1", 50, now);
        assertEquals(50L, store.currentDailyUsage("client-1", now));
    }

    @Test
    void shouldReturnZeroForNonExistentClient() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = (ValueOperations<String, String>) mock(ValueOperations.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);

        GatewayProperties properties = new GatewayProperties();
        properties.getSharedState().setBackend(Backend.REDIS);
        properties.getSharedState().setKeyPrefix("quota-test");

        RedisClientUsageStore store = new RedisClientUsageStore(redisTemplate, properties);
        assertEquals(0L, store.currentDailyUsage("unknown-client", Instant.now()));
    }

    @Test
    void shouldShareMonthlyUsageAcrossStoreInstances() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = (ValueOperations<String, String>) mock(ValueOperations.class);
        Map<String, Long> counters = new ConcurrentHashMap<>();

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenAnswer(invocation -> {
            Long value = counters.get(invocation.getArgument(0));
            return value == null ? null : Long.toString(value);
        });
        when(valueOps.increment(anyString(), anyLong())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            long delta = invocation.getArgument(1);
            long next = counters.getOrDefault(key, 0L) + delta;
            counters.put(key, next);
            return next;
        });
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);

        GatewayProperties properties = new GatewayProperties();
        properties.getSharedState().setBackend(Backend.REDIS);
        properties.getSharedState().setKeyPrefix("quota-test");

        RedisClientUsageStore storeA = new RedisClientUsageStore(redisTemplate, properties);
        RedisClientUsageStore storeB = new RedisClientUsageStore(redisTemplate, properties);

        Instant now = Instant.parse("2026-05-01T10:00:00Z");
        storeA.addMonthlyUsage("client-1", 100, now);
        storeB.addMonthlyUsage("client-1", 50, now);

        assertEquals(150L, storeA.currentMonthlyUsage("client-1", now));
        assertEquals(150L, storeB.currentMonthlyUsage("client-1", now));
    }

    @Test
    void shouldReturnZeroForNonExistentClientMonthly() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = (ValueOperations<String, String>) mock(ValueOperations.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        GatewayProperties properties = new GatewayProperties();
        properties.getSharedState().setBackend(Backend.REDIS);
        properties.getSharedState().setKeyPrefix("quota-test");

        RedisClientUsageStore store = new RedisClientUsageStore(redisTemplate, properties);
        assertEquals(0L, store.currentMonthlyUsage("unknown-client", Instant.now()));
    }

    @Test
    void shouldTrackDailyRequestCount() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = (ValueOperations<String, String>) mock(ValueOperations.class);
        Map<String, Long> counters = new ConcurrentHashMap<>();

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenAnswer(invocation -> {
            Long value = counters.get(invocation.getArgument(0));
            return value == null ? null : Long.toString(value);
        });
        when(valueOps.increment(anyString(), anyLong())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            long delta = invocation.getArgument(1);
            long next = counters.getOrDefault(key, 0L) + delta;
            counters.put(key, next);
            return next;
        });
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);

        GatewayProperties properties = new GatewayProperties();
        properties.getSharedState().setBackend(Backend.REDIS);
        properties.getSharedState().setKeyPrefix("quota-test");

        RedisClientUsageStore store = new RedisClientUsageStore(redisTemplate, properties);
        Instant now = Instant.parse("2026-04-27T10:00:00Z");

        store.addDailyRequestCount("client-1", now);
        store.addDailyRequestCount("client-1", now);
        store.addDailyRequestCount("client-1", now);

        assertEquals(3L, store.currentDailyRequestCount("client-1", now));
    }

    @Test
    void checkAndRecord_daily_underQuota_recordsAndReturns() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = (ValueOperations<String, String>) mock(ValueOperations.class);
        Map<String, Long> counters = new ConcurrentHashMap<>();

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenAnswer(invocation -> {
            Long value = counters.get(invocation.getArgument(0));
            return value == null ? null : Long.toString(value);
        });
        when(valueOps.increment(anyString(), anyLong())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            long delta = invocation.getArgument(1);
            long next = counters.getOrDefault(key, 0L) + delta;
            counters.put(key, next);
            return next;
        });
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    List<String> keys = invocation.getArgument(1);
                    long tokens = Long.parseLong((String) invocation.getArgument(2));
                    long quota = Long.parseLong((String) invocation.getArgument(3));
                    String usageKey = keys.get(0);
                    long current = counters.getOrDefault(usageKey, 0L);
                    if (current + tokens > quota) return -1L;
                    long next = counters.getOrDefault(usageKey, 0L) + tokens;
                    counters.put(usageKey, next);
                    return next;
                });

        GatewayProperties properties = new GatewayProperties();
        properties.getSharedState().setBackend(Backend.REDIS);
        properties.getSharedState().setKeyPrefix("quota-test");

        RedisClientUsageStore store = new RedisClientUsageStore(redisTemplate, properties);
        Instant now = Instant.parse("2026-04-27T10:00:00Z");

        long result1 = store.checkAndRecord("client-1", 30, 100, now);
        assertEquals(30L, result1);

        long result2 = store.checkAndRecord("client-1", 20, 100, now);
        assertEquals(50L, result2);
    }

    @Test
    void checkAndRecord_daily_overQuota_returnsMinusOne() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = (ValueOperations<String, String>) mock(ValueOperations.class);
        Map<String, Long> counters = new ConcurrentHashMap<>();

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenAnswer(invocation -> {
            Long value = counters.get(invocation.getArgument(0));
            return value == null ? null : Long.toString(value);
        });
        when(valueOps.increment(anyString(), anyLong())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            long delta = invocation.getArgument(1);
            long next = counters.getOrDefault(key, 0L) + delta;
            counters.put(key, next);
            return next;
        });
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    List<String> keys = invocation.getArgument(1);
                    long tokens = Long.parseLong((String) invocation.getArgument(2));
                    long quota = Long.parseLong((String) invocation.getArgument(3));
                    String usageKey = keys.get(0);
                    long current = counters.getOrDefault(usageKey, 0L);
                    if (current + tokens > quota) return -1L;
                    long next = counters.getOrDefault(usageKey, 0L) + tokens;
                    counters.put(usageKey, next);
                    return next;
                });

        GatewayProperties properties = new GatewayProperties();
        properties.getSharedState().setBackend(Backend.REDIS);
        properties.getSharedState().setKeyPrefix("quota-test");

        RedisClientUsageStore store = new RedisClientUsageStore(redisTemplate, properties);
        Instant now = Instant.parse("2026-04-27T10:00:00Z");

        store.checkAndRecord("client-1", 60, 100, now);
        long result = store.checkAndRecord("client-1", 50, 100, now);
        assertEquals(-1L, result);
    }

    @Test
    void checkAndRecordMonthly_underQuota_recordsAndReturns() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = (ValueOperations<String, String>) mock(ValueOperations.class);
        Map<String, Long> counters = new ConcurrentHashMap<>();

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenAnswer(invocation -> {
            Long value = counters.get(invocation.getArgument(0));
            return value == null ? null : Long.toString(value);
        });
        when(valueOps.increment(anyString(), anyLong())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            long delta = invocation.getArgument(1);
            long next = counters.getOrDefault(key, 0L) + delta;
            counters.put(key, next);
            return next;
        });
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    List<String> keys = invocation.getArgument(1);
                    long tokens = Long.parseLong((String) invocation.getArgument(2));
                    long quota = Long.parseLong((String) invocation.getArgument(3));
                    String usageKey = keys.get(0);
                    long current = counters.getOrDefault(usageKey, 0L);
                    if (current + tokens > quota) return -1L;
                    long next = counters.getOrDefault(usageKey, 0L) + tokens;
                    counters.put(usageKey, next);
                    return next;
                });

        GatewayProperties properties = new GatewayProperties();
        properties.getSharedState().setBackend(Backend.REDIS);
        properties.getSharedState().setKeyPrefix("quota-test");

        RedisClientUsageStore store = new RedisClientUsageStore(redisTemplate, properties);
        Instant now = Instant.parse("2026-05-01T10:00:00Z");

        long result = store.checkAndRecordMonthly("client-1", 200, 1000, now);
        assertEquals(200L, result);
    }

    @Test
    void checkAndRecordMonthly_overQuota_returnsMinusOne() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = (ValueOperations<String, String>) mock(ValueOperations.class);
        Map<String, Long> counters = new ConcurrentHashMap<>();

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenAnswer(invocation -> {
            Long value = counters.get(invocation.getArgument(0));
            return value == null ? null : Long.toString(value);
        });
        when(valueOps.increment(anyString(), anyLong())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            long delta = invocation.getArgument(1);
            long next = counters.getOrDefault(key, 0L) + delta;
            counters.put(key, next);
            return next;
        });
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    List<String> keys = invocation.getArgument(1);
                    long tokens = Long.parseLong((String) invocation.getArgument(2));
                    long quota = Long.parseLong((String) invocation.getArgument(3));
                    String usageKey = keys.get(0);
                    long current = counters.getOrDefault(usageKey, 0L);
                    if (current + tokens > quota) return -1L;
                    long next = counters.getOrDefault(usageKey, 0L) + tokens;
                    counters.put(usageKey, next);
                    return next;
                });

        GatewayProperties properties = new GatewayProperties();
        properties.getSharedState().setBackend(Backend.REDIS);
        properties.getSharedState().setKeyPrefix("quota-test");

        RedisClientUsageStore store = new RedisClientUsageStore(redisTemplate, properties);
        Instant now = Instant.parse("2026-05-01T10:00:00Z");

        store.checkAndRecordMonthly("client-1", 600, 1000, now);
        long result = store.checkAndRecordMonthly("client-1", 500, 1000, now);
        assertEquals(-1L, result);
    }
}
