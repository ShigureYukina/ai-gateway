package io.gateway.oss.admin.observability;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryAggregateMetricStoreTest {

    private final InMemoryAggregateMetricStore store = new InMemoryAggregateMetricStore();
    private final Instant now = Instant.parse("2026-05-22T10:00:00Z");

    @Test
    void record_createsEntriesInDailyAndMonthlyBuckets() {
        store.record("provider", "openai", "OpenAI", 5, 100, new BigDecimal("0.500000"), now);

        List<AggregateMetricStore.AggregateMetric> daily = store.getDaily("provider", LocalDate.of(2026, 5, 22));
        List<AggregateMetricStore.AggregateMetric> monthly = store.getMonthly("provider", YearMonth.of(2026, 5));

        assertEquals(1, daily.size());
        assertEquals(1, monthly.size());
        assertEquals("2026-05-22", daily.get(0).bucket());
        assertEquals("2026-05", monthly.get(0).bucket());
        assertEquals(5L, daily.get(0).requests());
        assertEquals(100L, monthly.get(0).tokens());
    }

    @Test
    void record_multipleCallsAccumulatesValues() {
        store.record("provider", "openai", "OpenAI", 5, 100, new BigDecimal("0.500000"), now);
        store.record("provider", "openai", "OpenAI Latest", 2, 50, new BigDecimal("1.250000"), now);

        AggregateMetricStore.AggregateMetric metric = store.getDaily("provider", LocalDate.of(2026, 5, 22)).get(0);

        assertEquals(7L, metric.requests());
        assertEquals(150L, metric.tokens());
        assertEquals(new BigDecimal("1.750000"), metric.costUsd());
        assertEquals("OpenAI Latest", metric.displayName());
    }

    @Test
    void getDaily_returnsCorrectDimensionType() {
        store.record("provider", "openai", "OpenAI", 1, 10, new BigDecimal("0.100000"), now);

        AggregateMetricStore.AggregateMetric metric = store.getDaily("provider", LocalDate.of(2026, 5, 22)).get(0);

        assertEquals("provider", metric.dimensionType());
    }

    @Test
    void getMonthly_returnsCorrectDimensionType() {
        store.record("client", "tenant-a", "Tenant A", 1, 10, new BigDecimal("0.100000"), now);

        AggregateMetricStore.AggregateMetric metric = store.getMonthly("client", YearMonth.of(2026, 5)).get(0);

        assertEquals("client", metric.dimensionType());
    }

    @Test
    void getDaily_withNoDataReturnsEmptyList() {
        List<AggregateMetricStore.AggregateMetric> result = store.getDaily("provider", LocalDate.of(2026, 5, 22));

        assertTrue(result.isEmpty());
    }

    @Test
    void recordsForDifferentDimensionTypesAreIsolated() {
        store.record("provider", "openai", "OpenAI", 5, 100, new BigDecimal("0.500000"), now);
        store.record("client", "tenant-a", "Tenant A", 3, 60, new BigDecimal("0.300000"), now);

        List<AggregateMetricStore.AggregateMetric> providerMetrics = store.getDaily("provider", LocalDate.of(2026, 5, 22));
        List<AggregateMetricStore.AggregateMetric> clientMetrics = store.getDaily("client", LocalDate.of(2026, 5, 22));

        assertEquals(1, providerMetrics.size());
        assertEquals(1, clientMetrics.size());
        assertEquals("openai", providerMetrics.get(0).dimensionKey());
        assertEquals("tenant-a", clientMetrics.get(0).dimensionKey());
    }

    @Test
    void resetForTests_clearsAllData() {
        store.record("provider", "openai", "OpenAI", 5, 100, new BigDecimal("0.500000"), now);

        store.resetForTests();

        assertTrue(store.getDaily("provider", LocalDate.of(2026, 5, 22)).isEmpty());
        assertTrue(store.getMonthly("provider", YearMonth.of(2026, 5)).isEmpty());
    }

    @Test
    void displayName_isStoredCorrectly() {
        store.record("provider", "openai", "OpenAI Display", 5, 100, new BigDecimal("0.500000"), now);

        AggregateMetricStore.AggregateMetric metric = store.getDaily("provider", LocalDate.of(2026, 5, 22)).get(0);

        assertEquals("OpenAI Display", metric.displayName());
    }
}
