package io.gateway.oss.admin.quota;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.Backend;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisClientCostStoreTest {

    @Test
    void shouldShareDailyCostAcrossStoreInstances() {
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
        properties.getSharedState().setKeyPrefix("cost-test");

        RedisClientCostStore storeA = new RedisClientCostStore(redisTemplate, properties);
        RedisClientCostStore storeB = new RedisClientCostStore(redisTemplate, properties);

        Instant now = Instant.parse("2026-04-27T10:00:00Z");
        storeA.addDailyCost("client-1", new BigDecimal("0.010000"), now);
        storeB.addDailyCost("client-1", new BigDecimal("0.005000"), now);

        assertEquals(new BigDecimal("0.015000"), storeA.currentDailyCost("client-1", now));
        assertEquals(new BigDecimal("0.015000"), storeB.currentDailyCost("client-1", now));
        verify(redisTemplate, times(1)).expire(argThat(key -> ((String) key).contains("cost-test") && ((String) key).contains("client-1")), any(Duration.class));
    }

    @Test
    void shouldBucketCostByUtcDay() {
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
        properties.getSharedState().setKeyPrefix("cost-test");

        RedisClientCostStore store = new RedisClientCostStore(redisTemplate, properties);
        Instant day1 = Instant.parse("2026-04-27T23:59:00Z");
        Instant day2 = Instant.parse("2026-04-28T00:01:00Z");

        store.addDailyCost("client-1", new BigDecimal("0.004000"), day1);
        store.addDailyCost("client-1", new BigDecimal("0.001000"), day2);

        assertEquals(new BigDecimal("0.004000"), store.currentDailyCost("client-1", day1));
        assertEquals(new BigDecimal("0.001000"), store.currentDailyCost("client-1", day2));
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
        properties.getSharedState().setKeyPrefix("cost-test");

        RedisClientCostStore store = new RedisClientCostStore(redisTemplate, properties);
        Instant now = Instant.parse("2026-04-27T10:00:00Z");

        // Should not throw even though expire returns false
        store.addDailyCost("client-1", new BigDecimal("0.010000"), now);
        assertEquals(new BigDecimal("0.010000"), store.currentDailyCost("client-1", now));
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
        properties.getSharedState().setKeyPrefix("cost-test");

        RedisClientCostStore store = new RedisClientCostStore(redisTemplate, properties);
        BigDecimal cost = store.currentDailyCost("unknown-client", Instant.now());
        assertEquals(BigDecimal.ZERO, cost);
    }

    @Test
    void shouldShareMonthlyCostAcrossStoreInstances() {
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
        properties.getSharedState().setKeyPrefix("cost-test");

        RedisClientCostStore storeA = new RedisClientCostStore(redisTemplate, properties);
        RedisClientCostStore storeB = new RedisClientCostStore(redisTemplate, properties);

        Instant now = Instant.parse("2026-05-01T10:00:00Z");
        storeA.addMonthlyCost("client-1", new BigDecimal("0.020000"), now);
        storeB.addMonthlyCost("client-1", new BigDecimal("0.010000"), now);

        assertEquals(new BigDecimal("0.030000"), storeA.currentMonthlyCost("client-1", now));
        assertEquals(new BigDecimal("0.030000"), storeB.currentMonthlyCost("client-1", now));
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
        properties.getSharedState().setKeyPrefix("cost-test");

        RedisClientCostStore store = new RedisClientCostStore(redisTemplate, properties);
        BigDecimal cost = store.currentMonthlyCost("unknown-client", Instant.now());
        assertEquals(BigDecimal.ZERO, cost);
    }

    @Test
    void checkAndRecord_daily_underBudget_recordsAndReturns() {
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
                    long costMicros = Long.parseLong((String) invocation.getArgument(2));
                    long budget = Long.parseLong((String) invocation.getArgument(3));
                    String costKey = keys.get(0);
                    long current = counters.getOrDefault(costKey, 0L);
                    if (current + costMicros > budget) return -1L;
                    long next = counters.getOrDefault(costKey, 0L) + costMicros;
                    counters.put(costKey, next);
                    return next;
                });

        GatewayProperties properties = new GatewayProperties();
        properties.getSharedState().setBackend(Backend.REDIS);
        properties.getSharedState().setKeyPrefix("cost-test");

        RedisClientCostStore store = new RedisClientCostStore(redisTemplate, properties);
        Instant now = Instant.parse("2026-04-27T10:00:00Z");

        long r1 = store.checkAndRecord("client-1", 3000, 10000, now);
        assertEquals(3000L, r1);

        long r2 = store.checkAndRecord("client-1", 2000, 10000, now);
        assertEquals(5000L, r2);
    }

    @Test
    void checkAndRecord_daily_overBudget_returnsMinusOne() {
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
                    long costMicros = Long.parseLong((String) invocation.getArgument(2));
                    long budget = Long.parseLong((String) invocation.getArgument(3));
                    String costKey = keys.get(0);
                    long current = counters.getOrDefault(costKey, 0L);
                    if (current + costMicros > budget) return -1L;
                    long next = counters.getOrDefault(costKey, 0L) + costMicros;
                    counters.put(costKey, next);
                    return next;
                });

        GatewayProperties properties = new GatewayProperties();
        properties.getSharedState().setBackend(Backend.REDIS);
        properties.getSharedState().setKeyPrefix("cost-test");

        RedisClientCostStore store = new RedisClientCostStore(redisTemplate, properties);
        Instant now = Instant.parse("2026-04-27T10:00:00Z");

        store.checkAndRecord("client-1", 8000, 10000, now);
        long result = store.checkAndRecord("client-1", 3000, 10000, now);
        assertEquals(-1L, result);
    }

    @Test
    void checkAndRecordMonthly_underBudget_recordsAndReturns() {
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
                    long costMicros = Long.parseLong((String) invocation.getArgument(2));
                    long budget = Long.parseLong((String) invocation.getArgument(3));
                    String costKey = keys.get(0);
                    long current = counters.getOrDefault(costKey, 0L);
                    if (current + costMicros > budget) return -1L;
                    long next = counters.getOrDefault(costKey, 0L) + costMicros;
                    counters.put(costKey, next);
                    return next;
                });

        GatewayProperties properties = new GatewayProperties();
        properties.getSharedState().setBackend(Backend.REDIS);
        properties.getSharedState().setKeyPrefix("cost-test");

        RedisClientCostStore store = new RedisClientCostStore(redisTemplate, properties);
        Instant now = Instant.parse("2026-05-01T10:00:00Z");

        long result = store.checkAndRecordMonthly("client-1", 5000, 50000, now);
        assertEquals(5000L, result);
    }

    @Test
    void checkAndRecordMonthly_overBudget_returnsMinusOne() {
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
                    long costMicros = Long.parseLong((String) invocation.getArgument(2));
                    long budget = Long.parseLong((String) invocation.getArgument(3));
                    String costKey = keys.get(0);
                    long current = counters.getOrDefault(costKey, 0L);
                    if (current + costMicros > budget) return -1L;
                    long next = counters.getOrDefault(costKey, 0L) + costMicros;
                    counters.put(costKey, next);
                    return next;
                });

        GatewayProperties properties = new GatewayProperties();
        properties.getSharedState().setBackend(Backend.REDIS);
        properties.getSharedState().setKeyPrefix("cost-test");

        RedisClientCostStore store = new RedisClientCostStore(redisTemplate, properties);
        Instant now = Instant.parse("2026-05-01T10:00:00Z");

        store.checkAndRecordMonthly("client-1", 40000, 50000, now);
        long result = store.checkAndRecordMonthly("client-1", 15000, 50000, now);
        assertEquals(-1L, result);
    }
}
