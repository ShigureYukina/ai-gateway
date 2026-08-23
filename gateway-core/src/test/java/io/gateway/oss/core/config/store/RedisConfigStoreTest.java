package io.gateway.oss.core.config.store;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.RedisConfigStore;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RedisConfigStoreTest {

    @SuppressWarnings("unchecked")
    @Test
    void shouldSaveAndLoadUsingHashOperations() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        // Simulate Redis hash storage
        Map<String, Map<Object, Object>> redisStore = new HashMap<>();
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            Object field = invocation.getArgument(1);
            Object value = invocation.getArgument(2);
            redisStore.computeIfAbsent(key, k -> new HashMap<>()).put(field, value);
            return null;
        }).when(hashOps).put(anyString(), anyString(), anyString());
        when(hashOps.get(anyString(), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            Object field = invocation.getArgument(1);
            Map<Object, Object> map = redisStore.get(key);
            return map != null ? map.get(field) : null;
        });
        when(hashOps.delete(anyString(), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            Object field = invocation.getArgument(1);
            Map<Object, Object> map = redisStore.get(key);
            if (map != null) {
                return map.remove(field) != null ? 1L : 0L;
            }
            return 0L;
        });
        when(hashOps.entries(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            Map<Object, Object> map = redisStore.get(key);
            return map != null ? Map.copyOf(map) : Map.of();
        });

        GatewayProperties properties = new GatewayProperties();
        RedisConfigStore store = new RedisConfigStore(redisTemplate, properties, Schedulers.immediate());

        // Save
        StepVerifier.create(store.save("providers", "openai", "{\"baseUrl\":\"http://localhost\"}"))
                .verifyComplete();

        // Load
        StepVerifier.create(store.load("providers", "openai"))
                .expectNext("{\"baseUrl\":\"http://localhost\"}")
                .verifyComplete();

        // Load non-existent
        StepVerifier.create(store.load("providers", "missing"))
                .verifyComplete();

        // Delete
        StepVerifier.create(store.delete("providers", "openai"))
                .verifyComplete();
        StepVerifier.create(store.load("providers", "openai"))
                .verifyComplete();

        // Verify hash operations used correct key pattern
        verify(hashOps).put(argThat(key -> key.contains("gateway:config:providers")), eq("openai"), anyString());
        verify(hashOps).delete(argThat(key -> key.contains("gateway:config:providers")), eq("openai"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldHandleDeleteOnNonExistentKey() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.delete(anyString(), anyString())).thenReturn(0L);

        GatewayProperties properties = new GatewayProperties();
        RedisConfigStore store = new RedisConfigStore(redisTemplate, properties, Schedulers.immediate());

        StepVerifier.create(store.delete("unknown-type", "unknown-key"))
                .verifyComplete();
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldLoadAllFromHash() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        Map<String, Map<Object, Object>> redisStore = new HashMap<>();
        when(hashOps.entries(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            Map<Object, Object> map = redisStore.get(key);
            return map != null ? Map.copyOf(map) : Map.of();
        });

        // Pre-populate
        redisStore.put("gateway:config:providers", Map.of(
                "openai", "{\"baseUrl\":\"http://openai\"}",
                "anthropic", "{\"baseUrl\":\"http://anthropic\"}"
        ));

        GatewayProperties properties = new GatewayProperties();
        RedisConfigStore store = new RedisConfigStore(redisTemplate, properties, Schedulers.immediate());

        StepVerifier.create(store.loadAll("providers"))
                .assertNext(all -> {
                    assertEquals(2, all.size());
                    assertEquals("{\"baseUrl\":\"http://openai\"}", all.get("openai"));
                    assertEquals("{\"baseUrl\":\"http://anthropic\"}", all.get("anthropic"));
                })
                .verifyComplete();
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldReturnEmptyMapWhenTypeDoesNotExist() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.entries(anyString())).thenReturn(Map.of());

        GatewayProperties properties = new GatewayProperties();
        RedisConfigStore store = new RedisConfigStore(redisTemplate, properties, Schedulers.immediate());

        StepVerifier.create(store.loadAll("unknown"))
                .assertNext(all -> assertTrue(all.isEmpty()))
                .verifyComplete();
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldUseCustomKeyPrefix() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        Map<String, Map<Object, Object>> redisStore = new HashMap<>();
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            Object field = invocation.getArgument(1);
            Object value = invocation.getArgument(2);
            redisStore.computeIfAbsent(key, k -> new HashMap<>()).put(field, value);
            return null;
        }).when(hashOps).put(anyString(), anyString(), anyString());
        when(hashOps.entries(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            Map<Object, Object> map = redisStore.get(key);
            return map != null ? Map.copyOf(map) : Map.of();
        });

        GatewayProperties properties = new GatewayProperties();
        properties.getSharedState().setKeyPrefix("custom-prefix");
        RedisConfigStore store = new RedisConfigStore(redisTemplate, properties, Schedulers.immediate());

        StepVerifier.create(store.save("system", "limit", "{\"requestsPerWindow\":10}"))
                .verifyComplete();

        assertTrue(redisStore.containsKey("custom-prefix:config:system"));
        assertEquals("{\"requestsPerWindow\":10}",
                redisStore.get("custom-prefix:config:system").get("limit"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldFallbackToDefaultPrefixWhenBlank() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        Map<String, Map<Object, Object>> redisStore = new HashMap<>();
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            Object field = invocation.getArgument(1);
            Object value = invocation.getArgument(2);
            redisStore.computeIfAbsent(key, k -> new HashMap<>()).put(field, value);
            return null;
        }).when(hashOps).put(anyString(), anyString(), anyString());

        GatewayProperties properties = new GatewayProperties();
        properties.getSharedState().setKeyPrefix("");
        RedisConfigStore store = new RedisConfigStore(redisTemplate, properties, Schedulers.immediate());

        StepVerifier.create(store.save("providers", "openai", "{}"))
                .verifyComplete();

        assertTrue(redisStore.containsKey("gateway:config:providers"));
    }

    @Test
    void shouldSupportAtomicStringKeyOperations() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq("gateway:refresh-token-consumed:token1"), eq("payload"), any(Duration.class))).thenReturn(true);
        when(valueOps.get("gateway:refresh-token-consumed:token1")).thenReturn("payload");

        GatewayProperties properties = new GatewayProperties();
        RedisConfigStore store = new RedisConfigStore(redisTemplate, properties, Schedulers.immediate());

        StepVerifier.create(store.setIfAbsent("gateway:refresh-token-consumed:token1", "payload", Duration.ofSeconds(30)))
                .expectNext(true)
                .verifyComplete();
        StepVerifier.create(store.get("gateway:refresh-token-consumed:token1"))
                .expectNext("payload")
                .verifyComplete();
        StepVerifier.create(store.deleteKey("gateway:refresh-token-consumed:token1"))
                .verifyComplete();

        verify(redisTemplate).delete("gateway:refresh-token-consumed:token1");
    }
}
