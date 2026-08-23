package io.gateway.oss.admin.limit;

import io.gateway.oss.core.config.GatewayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisClientTpmStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisClientTpmStore store;

    private final Instant now = Instant.parse("2026-06-04T10:15:30Z");

    @BeforeEach
    void setUp() {
        GatewayProperties properties = new GatewayProperties();
        properties.getSharedState().setKeyPrefix("test-prefix");
        store = new RedisClientTpmStore(redisTemplate, properties);
    }

    @Test
    void currentMinuteUsage_queriesRedisAndParsesLong() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("100");

        long result = store.currentMinuteUsage("client-1", now);

        assertEquals(100L, result);
        verify(valueOperations).get(anyString());
    }

    @Test
    void currentMinuteUsage_returnsZeroForMissingKey() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        long result = store.currentMinuteUsage("client-1", now);

        assertEquals(0L, result);
    }

    @Test
    void currentMinuteUsage_returnsZeroForNonNumericValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("not-a-number");

        long result = store.currentMinuteUsage("client-1", now);

        assertEquals(0L, result);
    }

    @Test
    void reserve_executesLuaScript() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(100L);

        store.reserve("client-1", 20, 200, now);

        verify(redisTemplate).execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString());
    }

    @Test
    void reserve_returnsValueFromScriptResult() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(100L);

        long result = store.reserve("client-1", 20, 200, now);

        assertEquals(100L, result);
    }

    @Test
    void reserve_returnsNegativeOneWhenScriptReturnsNegativeOne() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(-1L);

        long result = store.reserve("client-1", 20, 50, now);

        assertEquals(-1L, result);
    }

    @Test
    void reserve_withZeroTokensCallsCurrentMinuteUsage() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("55");

        long result = store.reserve("client-1", 0, 200, now);

        assertEquals(55L, result);
        verify(valueOperations).get(anyString());
        verify(redisTemplate, never()).execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString());
    }

    @Test
    void adjust_incrementsRedisKey() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString(), anyLong())).thenReturn(150L);

        store.adjust("client-1", 25, now);

        verify(valueOperations).increment(anyString(), anyLong());
    }

    @Test
    void adjust_supportsNegativeDelta() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString(), anyLong())).thenReturn(70L);

        store.adjust("client-1", -30, now);

        verify(valueOperations).increment(anyString(), eq(-30L));
    }

    @Test
    void adjust_setsToZeroIfResultGoesNegative() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString(), anyLong())).thenReturn(-5L);

        store.adjust("client-1", -5, now);

        verify(valueOperations).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void adjust_setsExpireWhenKeyIsCreated() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString(), anyLong())).thenReturn(25L);

        store.adjust("client-1", 25, now);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(redisTemplate).expire(anyString(), ttlCaptor.capture());
        assertTrue(ttlCaptor.getValue().getSeconds() > 0);
    }

}
