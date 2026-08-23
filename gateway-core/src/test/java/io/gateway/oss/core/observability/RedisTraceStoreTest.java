package io.gateway.oss.core.observability;

import io.gateway.oss.core.config.GatewayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RedisTraceStoreTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void shouldSaveAndRetrieveByRequestId() {
        RedisMocks mocks = new RedisMocks();
        RedisTraceStore store = new RedisTraceStore(mocks.template, properties(), OBJECT_MAPPER);

        TraceRecord record = new TraceRecord(
                "req-1", "client-a", "gpt-4o", "openai",
                "route-1", "chat", 200, null,
                150L, null, "{\"messages\":[]}", "{\"choices\":[]}",
                Instant.parse("2026-05-26T00:00:00Z")
        );
        store.save(record);

        assertEquals("req-1", mocks.lastListLeftPushed);
        assertTrue(mocks.lastExpiredKey.endsWith(":trace:recent"));

        TraceRecord loaded = store.getByRequestId("req-1");
        assertNotNull(loaded);
        assertEquals("req-1", loaded.requestId());
        assertEquals("client-a", loaded.clientId());
        assertEquals("gpt-4o", loaded.model());
        assertEquals(200, loaded.status());
        assertEquals(150L, loaded.latencyMs());
        assertNotNull(loaded.timestamp());

        // Verify key patterns used in save
        verify(mocks.valueOps).set(argThat(key -> key.contains("gateway:trace:req-1")), anyString(), any(Duration.class));
        verify(mocks.listOps).leftPush(argThat(key -> key.contains("gateway:trace:recent")), eq("req-1"));
    }

    @Test
    void shouldNotFailWhenExpireReturnsFalse() {
        RedisMocks mocks = new RedisMocks();
        // Override expire to return false
        when(mocks.template.expire(anyString(), any(Duration.class))).thenReturn(false);
        RedisTraceStore store = new RedisTraceStore(mocks.template, properties(), OBJECT_MAPPER);

        TraceRecord record = new TraceRecord(
                "req-expire-fail", "client-a", "gpt-4o", "openai",
                "route-1", "chat", 200, null,
                150L, null, "{}", "{}",
                Instant.parse("2026-05-26T00:00:00Z")
        );
        // Should not throw even though expire returns false
        store.save(record);

        TraceRecord loaded = store.getByRequestId("req-expire-fail");
        assertNotNull(loaded);
        assertEquals("req-expire-fail", loaded.requestId());
    }

    @Test
    void shouldReturnNullForMissingRequestId() {
        RedisMocks mocks = new RedisMocks();
        RedisTraceStore store = new RedisTraceStore(mocks.template, properties(), OBJECT_MAPPER);

        assertNull(store.getByRequestId("nonexistent"));
    }

    @Test
    void shouldReturnNullForNullRequestId() {
        RedisMocks mocks = new RedisMocks();
        RedisTraceStore store = new RedisTraceStore(mocks.template, properties(), OBJECT_MAPPER);

        assertNull(store.getByRequestId(null));
    }

    @Test
    void shouldReturnMostRecentTraces() {
        RedisMocks mocks = new RedisMocks();
        RedisTraceStore store = new RedisTraceStore(mocks.template, properties(), OBJECT_MAPPER);

        store.save(trace("r1", "client-a"));
        store.save(trace("r2", "client-b"));
        store.save(trace("r3", "client-c"));

        List<TraceRecord> recent = store.getRecent(2);
        assertEquals(2, recent.size());
        assertEquals("r3", recent.get(0).requestId());
        assertEquals("r2", recent.get(1).requestId());
    }

    @Test
    void shouldSkipCorruptData() {
        RedisMocks mocks = new RedisMocks();
        mocks.valueStore.put("gateway:trace:corrupt", "not-json");
        RedisTraceStore store = new RedisTraceStore(mocks.template, properties(), OBJECT_MAPPER);

        assertNull(store.getByRequestId("corrupt"));
    }

    @Test
    void shouldIgnoreSaveWithNullRequestId() {
        RedisMocks mocks = new RedisMocks();
        RedisTraceStore store = new RedisTraceStore(mocks.template, properties(), OBJECT_MAPPER);

        TraceRecord record = new TraceRecord(
                null, "client-a", "gpt-4o", "openai",
                null, null, null, null,
                null, null, null, null, null
        );
        store.save(record);
        verify(mocks.template, never()).opsForValue();
    }

    @Test
    void shouldLimitListSize() {
        RedisMocks mocks = new RedisMocks();
        RedisTraceStore store = new RedisTraceStore(mocks.template, properties(), OBJECT_MAPPER);

        for (int i = 0; i < 600; i++) {
            store.save(trace("r" + i, "client-a"));
        }

        assertTrue(mocks.recentListMaxSize <= 500);
    }

    @Test
    void shouldResetForTests() {
        RedisMocks mocks = new RedisMocks();
        RedisTraceStore store = new RedisTraceStore(mocks.template, properties(), OBJECT_MAPPER);

        store.save(trace("r1", "client-a"));
        store.save(trace("r2", "client-b"));
        store.resetForTests();

        assertTrue(mocks.valueStore.isEmpty() || mocks.valueStore.keySet().stream().noneMatch(k -> k.startsWith("gateway:trace:")));
    }

    private static TraceRecord trace(String requestId, String clientId) {
        return new TraceRecord(
                requestId, clientId, "gpt-4o", "openai",
                "route-1", "chat", 200, null,
                100L, null, null, null,
                Instant.parse("2026-05-26T00:00:00Z")
        );
    }

    private GatewayProperties properties() {
        GatewayProperties p = new GatewayProperties();
        p.getSharedState().setKeyPrefix("gateway");
        return p;
    }

    private static final class RedisMocks {
        final StringRedisTemplate template = mock(StringRedisTemplate.class);
        final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        final ListOperations<String, String> listOps = mock(ListOperations.class);
        final Map<String, String> valueStore = new HashMap<>();
        final Map<String, List<String>> listStore = new HashMap<>();
        String lastListLeftPushed;
        String lastExpiredKey;
        int recentListMaxSize = 0;

        @SuppressWarnings("unchecked")
        RedisMocks() {
            when(template.opsForValue()).thenReturn(valueOps);
            when(template.opsForList()).thenReturn(listOps);

            when(template.expire(anyString(), any(Duration.class))).thenAnswer(invocation -> {
                lastExpiredKey = invocation.getArgument(0);
                return true;
            });

            doAnswer(invocation -> {
                String key = invocation.getArgument(0);
                lastListLeftPushed = invocation.getArgument(1);
                listStore.computeIfAbsent(key, k -> new ArrayList<>()).add(0, lastListLeftPushed);
                return 1L;
            }).when(listOps).leftPush(anyString(), anyString());

            doAnswer(invocation -> {
                String key = invocation.getArgument(0);
                long keep = invocation.getArgument(2);
                if (keep > 0) {
                    List<String> list = listStore.get(key);
                    if (list != null && list.size() > keep) {
                        int before = list.size();
                        listStore.put(key, new ArrayList<>(list.subList(0, (int) keep)));
                        recentListMaxSize = Math.max(recentListMaxSize, before);
                    }
                }
                return null;
            }).when(listOps).trim(anyString(), anyLong(), anyLong());

            when(listOps.range(anyString(), anyLong(), anyLong())).thenAnswer(invocation -> {
                String key = invocation.getArgument(0);
                long start = invocation.getArgument(1);
                long end = invocation.getArgument(2);
                List<String> list = listStore.get(key);
                if (list == null || list.isEmpty()) return List.of();
                int to = (int) Math.min(end + 1, list.size());
                if (start >= list.size()) return List.of();
                return new ArrayList<>(list.subList((int) start, to));
            });

            when(valueOps.get(anyString())).thenAnswer(invocation -> {
                String key = invocation.getArgument(0);
                return valueStore.get(key);
            });

            doAnswer(invocation -> {
                String key = invocation.getArgument(0);
                String value = invocation.getArgument(1);
                valueStore.put(key, value);
                return null;
            }).when(valueOps).set(anyString(), anyString(), any(Duration.class));

            when(template.keys(anyString())).thenAnswer(invocation -> {
                String pattern = invocation.getArgument(0);
                String prefix = pattern.substring(0, pattern.length() - 1);
                return valueStore.keySet().stream()
                        .filter(k -> k.startsWith(prefix))
                        .collect(Collectors.toSet());
            });

            doAnswer(invocation -> {
                String key = invocation.getArgument(0);
                valueStore.remove(key);
                return true;
            }).when(template).delete(anyString());
        }
    }
}
