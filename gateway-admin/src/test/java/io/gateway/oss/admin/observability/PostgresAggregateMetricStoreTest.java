package io.gateway.oss.admin.observability;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostgresAggregateMetricStoreTest {

    @Mock
    JdbcTemplate jdbc;

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUpMetrics() {
        meterRegistry = new SimpleMeterRegistry();
        Metrics.globalRegistry.add(meterRegistry);
    }

    @AfterEach
    void tearDownMetrics() {
        Metrics.globalRegistry.remove(meterRegistry);
        meterRegistry.close();
    }

    private PostgresAggregateMetricStore createStore() {
        return new PostgresAggregateMetricStore(jdbc, "gw");
    }

    private final Instant now = Instant.parse("2026-05-22T10:00:00Z");

    @Test
    void record_executesUpsert() {
        PostgresAggregateMetricStore store = createStore();
        store.record("provider", "openai", "OpenAI", 5, 100, new BigDecimal("0.50"), now);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sqlCaptor.capture(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any());

        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("INSERT INTO aggregate_metric (namespace, dimension_type, dimension_key, bucket"));
        assertTrue(sql.contains("VALUES (?, ?, ?, ?, ?, ?, ?, ?), (?, ?, ?, ?, ?, ?, ?, ?)"));
        assertTrue(sql.contains("ON CONFLICT (namespace, dimension_type, dimension_key, bucket) DO UPDATE SET"));
        assertEquals(1L, meterRegistry.get("gateway.write.latency").tag("writePoint", "aggregateMetricBatch").timer().count());
    }

    @Test
    void getDaily_returnsMetrics() {
        PostgresAggregateMetricStore store = createStore();
        when(jdbc.queryForList(argThat(sql -> ((String) sql).contains("FROM aggregate_metric WHERE namespace = ? AND dimension_type = ?")), anyString(), anyString(), anyString()))
                .thenReturn(List.of(
                        Map.of(
                                "dimension_type", "provider",
                                "dimension_key", "openai",
                                "display_name", "OpenAI",
                                "requests", 10L,
                                "tokens", 200L,
                                "cost_micros", 500000L,
                                "bucket", "2026-05-22"
                        )
                ));

        List<AggregateMetricStore.AggregateMetric> result = store.getDaily("provider", LocalDate.of(2026, 5, 22));
        assertEquals(1, result.size());
        AggregateMetricStore.AggregateMetric m = result.get(0);
        assertEquals("openai", m.dimensionKey());
        assertEquals(10, m.requests());
        assertEquals(200, m.tokens());
        assertEquals(new BigDecimal("0.500000"), m.costUsd());
    }

    @Test
    void getMonthly_returnsMetrics() {
        PostgresAggregateMetricStore store = createStore();
        when(jdbc.queryForList(argThat(sql -> ((String) sql).contains("FROM aggregate_metric WHERE namespace = ? AND dimension_type = ?")), anyString(), anyString(), anyString()))
                .thenReturn(List.of(
                        Map.of(
                                "dimension_type", "provider",
                                "dimension_key", "anthropic",
                                "display_name", "Anthropic",
                                "requests", 50L,
                                "tokens", 1000L,
                                "cost_micros", 2000000L,
                                "bucket", "2026-05"
                        )
                ));

        List<AggregateMetricStore.AggregateMetric> result = store.getMonthly("provider", YearMonth.of(2026, 5));
        assertEquals(1, result.size());
        assertEquals("anthropic", result.get(0).dimensionKey());
        assertEquals(new BigDecimal("2.000000"), result.get(0).costUsd());
    }

    @Test
    void getDaily_returnsEmptyWhenNoData() {
        PostgresAggregateMetricStore store = createStore();
        when(jdbc.queryForList(argThat(sql -> ((String) sql).contains("FROM aggregate_metric WHERE namespace = ? AND dimension_type = ?")), anyString(), anyString(), anyString()))
                .thenReturn(Collections.emptyList());

        List<AggregateMetricStore.AggregateMetric> result = store.getDaily("provider", LocalDate.of(2026, 5, 22));
        assertTrue(result.isEmpty());
    }

    @Test
    void recordAll_aggregatesDuplicateDimensionKeysBeforeBatchUpsert() {
        // 同一批次内多条请求共享维度值（如同 client）时，必须先按 (type,key,bucket)
        // 聚合：否则多行 INSERT 内同一冲突行出现两次，PG 报 21000 整批失败。
        PostgresAggregateMetricStore store = createStore();
        AggregateMetricStore.DimensionRecord same1 =
                new AggregateMetricStore.DimensionRecord("client", "client-1", "Client One", 1, 100, new BigDecimal("0.010000"));
        AggregateMetricStore.DimensionRecord same2 =
                new AggregateMetricStore.DimensionRecord("client", "client-1", "Client One", 2, 50, new BigDecimal("0.005000"));
        AggregateMetricStore.DimensionRecord other =
                new AggregateMetricStore.DimensionRecord("client", "client-2", "Client Two", 1, 10, null);

        store.recordAll(List.of(same1, same2, other), Instant.parse("2026-09-01T00:30:00Z"));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(1)).update(sqlCaptor.capture(), paramsCaptor.capture());

        String sql = sqlCaptor.getValue();
        // 去重后 client-1 + client-2 × day/month 共 4 个冲突目标（修复前为 6 行且含同键重复）
        assertEquals(4, countValueTuples(sql));

        Object[] params = paramsCaptor.getValue();
        assertEquals(32, params.length);
        assertAggregatedRow(params, "client-1", "2026-09-01", 3L, 150L, 15000L);
        assertAggregatedRow(params, "client-1", "2026-09", 3L, 150L, 15000L);
        assertAggregatedRow(params, "client-2", "2026-09-01", 1L, 10L, 0L);
        assertAggregatedRow(params, "client-2", "2026-09", 1L, 10L, 0L);
    }

    private int countValueTuples(String sql) {
        return sql.split("\\(\\?, \\?, \\?, \\?, \\?, \\?, \\?, \\?\\)", -1).length - 1;
    }

    private void assertAggregatedRow(Object[] params, String key, String bucket,
                                     long requests, long tokens, long costMicros) {
        for (int i = 0; i + 7 < params.length; i += 8) {
            if (key.equals(params[i + 2]) && bucket.equals(params[i + 3])) {
                assertEquals(requests, ((Number) params[i + 4]).longValue(), "requests: " + key + "/" + bucket);
                assertEquals(tokens, ((Number) params[i + 5]).longValue(), "tokens: " + key + "/" + bucket);
                assertEquals(costMicros, ((Number) params[i + 6]).longValue(), "costMicros: " + key + "/" + bucket);
                return;
            }
        }
        fail("row not found: " + key + "/" + bucket);
    }
}
