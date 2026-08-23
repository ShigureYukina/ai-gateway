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
}
