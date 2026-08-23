package io.gateway.oss.admin.observability;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.SharedStateConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisAggregateMetricStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, String, String> hashOps;

    @Mock
    private RedisConnection redisConnection;

    private RedisAggregateMetricStore store;

    private final Instant now = Instant.parse("2026-05-22T10:00:00Z");
    private final String prefix = "test-prefix:reporting:";

    @BeforeEach
    void setUp() {
        GatewayProperties properties = new GatewayProperties();
        SharedStateConfig sharedStateConfig = new SharedStateConfig();
        sharedStateConfig.setKeyPrefix("test-prefix");
        properties.setSharedState(sharedStateConfig);

        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> rawHashOps = (HashOperations<String, Object, Object>) (HashOperations<?, ?, ?>) hashOps;
        when(redisTemplate.opsForHash()).thenReturn(rawHashOps);
        lenient().when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RedisCallback<Object> callback = invocation.getArgument(0);
            return callback.doInRedis(redisConnection);
        });
        store = new RedisAggregateMetricStore(redisTemplate, properties);
    }

    @Test
    void record_executesLuaScriptViaRedisTemplate() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("OK");

        store.record("provider", "openai", "OpenAI", 5, 100, new BigDecimal("0.500000"), now);

        verify(redisTemplate, times(2)).execute(
                any(DefaultRedisScript.class),
                anyList(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString());
    }

    @Test
    void getDaily_queriesRedisKeysAndParsesHashEntries() {
        String key = prefix + "provider:2026-05-22:openai";
        stubScan(prefix + "provider:2026-05-22:*", key);
        when(hashOps.entries(key)).thenReturn(Map.of(
                "displayName", "OpenAI",
                "requests", "10",
                "tokens", "200",
                "costMicros", "500000"
        ));

        List<AggregateMetricStore.AggregateMetric> result = store.getDaily("provider", LocalDate.of(2026, 5, 22));

        assertEquals(1, result.size());
        AggregateMetricStore.AggregateMetric metric = result.get(0);
        assertEquals("provider", metric.dimensionType());
        assertEquals("openai", metric.dimensionKey());
        assertEquals("OpenAI", metric.displayName());
        assertEquals(10L, metric.requests());
        assertEquals(200L, metric.tokens());
        assertEquals(new BigDecimal("0.500000"), metric.costUsd());
        assertEquals("2026-05-22", metric.bucket());
    }

    @Test
    void getMonthly_queriesRedisKeysAndParsesHashEntries() {
        String key = prefix + "provider:2026-05:anthropic";
        stubScan(prefix + "provider:2026-05:*", key);
        when(hashOps.entries(key)).thenReturn(Map.of(
                "displayName", "Anthropic",
                "requests", "50",
                "tokens", "1000",
                "costMicros", "2000000"
        ));

        List<AggregateMetricStore.AggregateMetric> result = store.getMonthly("provider", YearMonth.of(2026, 5));

        assertEquals(1, result.size());
        AggregateMetricStore.AggregateMetric metric = result.get(0);
        assertEquals("anthropic", metric.dimensionKey());
        assertEquals(new BigDecimal("2.000000"), metric.costUsd());
        assertEquals("2026-05", metric.bucket());
    }

    @Test
    void getDaily_withNoKeysReturnsEmptyList() {
        stubScan(prefix + "provider:2026-05-22:*");

        List<AggregateMetricStore.AggregateMetric> result = store.getDaily("provider", LocalDate.of(2026, 5, 22));

        assertTrue(result.isEmpty());
    }

    @Test
    void record_convertsCostToMicrosWithSixDecimalPlaces() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("OK");

        store.record("provider", "openai", "OpenAI", 1, 2, new BigDecimal("0.1234567"), now);

        ArgumentCaptor<List> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> displayNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> requestsCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> tokensCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> costMicrosCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> ttlCaptor = ArgumentCaptor.forClass(String.class);

        verify(redisTemplate, times(2)).execute(
                any(DefaultRedisScript.class),
                keysCaptor.capture(),
                displayNameCaptor.capture(),
                requestsCaptor.capture(),
                tokensCaptor.capture(),
                costMicrosCaptor.capture(),
                ttlCaptor.capture());

        assertEquals("OpenAI", displayNameCaptor.getAllValues().get(0));
        assertEquals("1", requestsCaptor.getAllValues().get(0));
        assertEquals("2", tokensCaptor.getAllValues().get(0));
        assertEquals("123457", costMicrosCaptor.getAllValues().get(0));
        assertNotNull(keysCaptor.getAllValues().get(0));
        assertTrue(Long.parseLong(ttlCaptor.getAllValues().get(0)) > 0);
    }

    @Test
    void getDaily_parsesEdgeCasesForNullOrMissingHashValues() {
        String key = prefix + "provider:2026-05-22:openai";
        Map<String, String> values = new HashMap<>();
        values.put("displayName", null);
        values.put("tokens", "invalid");
        values.put("costMicros", null);
        stubScan(prefix + "provider:2026-05-22:*", key);
        when(hashOps.entries(key)).thenReturn(values);

        List<AggregateMetricStore.AggregateMetric> result = store.getDaily("provider", LocalDate.of(2026, 5, 22));

        assertEquals(1, result.size());
        AggregateMetricStore.AggregateMetric metric = result.get(0);
        assertEquals("openai", metric.dimensionKey());
        assertEquals(0L, metric.requests());
        assertEquals(0L, metric.tokens());
        assertEquals(BigDecimal.ZERO, metric.costUsd());
        assertNull(metric.displayName());
    }

    @Test
    void getDaily_scansRedisKeysInsteadOfUsingKeysCommand() {
        String key = prefix + "provider:2026-05-22:openai";
        stubScan(prefix + "provider:2026-05-22:*", key);
        when(hashOps.entries(key)).thenReturn(Map.of(
                "displayName", "OpenAI",
                "requests", "1",
                "tokens", "2",
                "costMicros", "3"
        ));

        List<AggregateMetricStore.AggregateMetric> result = store.getDaily("provider", LocalDate.of(2026, 5, 22));

        assertEquals(1, result.size());
        verify(redisTemplate).execute(any(RedisCallback.class));
    }

    private void stubScan(String expectedPattern, String... keys) {
        when(redisConnection.scan(any(ScanOptions.class))).thenAnswer(invocation -> {
            ScanOptions options = invocation.getArgument(0);
            assertEquals(expectedPattern, options.getPattern());
            return new TestCursor(keys);
        });
    }

    private static final class TestCursor implements Cursor<byte[]> {

        private final Iterator<byte[]> iterator;

        private TestCursor(String... keys) {
            List<byte[]> bytes = new java.util.ArrayList<>();
            for (String key : keys) {
                bytes.add(key.getBytes(StandardCharsets.UTF_8));
            }
            this.iterator = bytes.iterator();
        }

        @Override
        public byte[] next() {
            return iterator.next();
        }

        @Override
        public boolean hasNext() {
            return iterator.hasNext();
        }

        @Override
        public void close() {
        }

        @Override
        public long getPosition() {
            return 0;
        }

        @Override
        public CursorId getId() {
            return CursorId.of(0);
        }

        @Override
        public long getCursorId() {
            return 0;
        }

        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }
}
