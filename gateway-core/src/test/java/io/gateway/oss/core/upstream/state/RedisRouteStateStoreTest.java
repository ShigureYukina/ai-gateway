package io.gateway.oss.core.upstream.state;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.upstream.RedisRouteStateStore;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisRouteStateStoreTest {

    @Test
    void shouldShareRouteOpenStateAcrossStoreInstancesAndRecoverAfterDuration() {
        RedisMocks mocks = new RedisMocks();
        GatewayProperties properties = properties();

        RedisRouteStateStore storeA = new RedisRouteStateStore(mocks.template, properties);
        RedisRouteStateStore storeB = new RedisRouteStateStore(mocks.template, properties);

        Instant t0 = Instant.parse("2026-04-27T00:00:00Z");
        storeA.recordRetryableFailure("route-a", t0, properties.getResilience());
        assertTrue(storeB.isAvailable("route-a", t0));

        storeA.recordRetryableFailure("route-a", t0.plusSeconds(1), properties.getResilience());
        assertFalse(storeB.isAvailable("route-a", t0.plusSeconds(2)));

        assertTrue(storeB.isAvailable("route-a", t0.plusSeconds(35)));
    }

    @Test
    void shouldClearStateOnSuccess() {
        RedisMocks mocks = new RedisMocks();
        GatewayProperties properties = properties();
        RedisRouteStateStore store = new RedisRouteStateStore(mocks.template, properties);
        Instant t0 = Instant.parse("2026-04-27T00:00:00Z");

        store.recordRetryableFailure("route-a", t0, properties.getResilience());
        store.recordRetryableFailure("route-a", t0.plusSeconds(1), properties.getResilience());
        assertFalse(store.isAvailable("route-a", t0.plusSeconds(2)));

        store.recordSuccess("route-a");
        assertTrue(store.isAvailable("route-a", t0.plusSeconds(2)));
    }

    @Test
    void shouldNotFailWhenRecordingSuccessOnHealthyRoute() {
        RedisMocks mocks = new RedisMocks();
        GatewayProperties properties = properties();
        RedisRouteStateStore store = new RedisRouteStateStore(mocks.template, properties);

        store.recordSuccess("healthy-route");
        assertTrue(store.isAvailable("healthy-route", Instant.now()));
    }

    @Test
    void shouldOpenRouteWhenFailuresShareSameTimestamp() {
        RedisMocks mocks = new RedisMocks();
        GatewayProperties properties = properties();
        RedisRouteStateStore store = new RedisRouteStateStore(mocks.template, properties);
        Instant t0 = Instant.parse("2026-04-27T00:00:00Z");

        store.recordRetryableFailure("route-a", t0, properties.getResilience());
        store.recordRetryableFailure("route-a", t0, properties.getResilience());

        assertFalse(store.isAvailable("route-a", t0.plusSeconds(1)));
    }

    private GatewayProperties properties() {
        GatewayProperties properties = new GatewayProperties();
        properties.getResilience().setRetryableFailureThreshold(2);
        properties.getResilience().setFailureWindow(Duration.ofSeconds(30));
        properties.getResilience().setOpenDuration(Duration.ofSeconds(30));
        return properties;
    }

    private static final class RedisMocks {
        private final StringRedisTemplate template = mock(StringRedisTemplate.class);
        private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        private final ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);
        private final Map<String, String> valueStore = new HashMap<>();
        private final Map<String, Map<String, Double>> zsets = new HashMap<>();

        @SuppressWarnings("unchecked")
        private RedisMocks() {
            when(template.opsForValue()).thenReturn(valueOps);
            when(template.opsForZSet()).thenReturn(zSetOps);
            when(template.expire(anyString(), any(Duration.class))).thenAnswer(invocation -> {
                String key = invocation.getArgument(0);
                assert key != null && key.contains(":resilience:") && !key.endsWith(":resilience:")
                        : "expire key must contain resilience route identifier";
                return true;
            });
            when(template.delete(anyString())).thenAnswer(invocation -> {
                String key = invocation.getArgument(0);
                assert key != null && !key.isEmpty() : "delete key must not be null or empty";
                valueStore.remove(key);
                zsets.remove(key);
                return true;
            });

            when(valueOps.get(anyString())).thenAnswer(invocation -> valueStore.get(invocation.getArgument(0)));
            doAnswer(invocation -> {
                valueStore.put(invocation.getArgument(0), invocation.getArgument(1));
                return null;
            }).when(valueOps).set(anyString(), anyString(), any(Duration.class));

            when(zSetOps.removeRangeByScore(anyString(), anyDouble(), anyDouble())).thenAnswer(invocation -> {
                String key = invocation.getArgument(0);
                double max = invocation.getArgument(2);
                Map<String, Double> set = zsets.computeIfAbsent(key, ignored -> new HashMap<>());
                int before = set.size();
                set.entrySet().removeIf(entry -> entry.getValue() <= max);
                return (long) (before - set.size());
            });
            when(zSetOps.add(anyString(), anyString(), anyDouble())).thenAnswer(invocation -> {
                String key = invocation.getArgument(0);
                String value = invocation.getArgument(1);
                Double score = invocation.getArgument(2);
                zsets.computeIfAbsent(key, ignored -> new HashMap<>()).put(value, score);
                return true;
            });
            when(zSetOps.zCard(anyString())).thenAnswer(invocation -> {
                String key = invocation.getArgument(0);
                return (long) zsets.computeIfAbsent(key, ignored -> new HashMap<>()).size();
            });
        }
    }
}
