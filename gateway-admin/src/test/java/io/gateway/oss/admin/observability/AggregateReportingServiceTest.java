package io.gateway.oss.admin.observability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AggregateReportingServiceTest {

    @Mock
    private AggregateMetricStore store;

    private AggregateReportingService service;

    @BeforeEach
    void setUp() {
        service = new AggregateReportingService(store);
    }

    @Test
    void recordSuccessShouldRecordAllSixDimensionsViaBatch() {
        Instant now = Instant.parse("2026-06-04T10:15:30Z");

        service.recordSuccess("req-1", "openai", "alice", "key-1", "Key One", "client-1", "gpt-4o", 321L, 1.25d, now);
        service.flushPending();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AggregateMetricStore.DimensionRecord>> recordsCaptor = ArgumentCaptor.forClass(List.class);
        verify(store).recordAll(recordsCaptor.capture(), any(Instant.class));

        List<AggregateMetricStore.DimensionRecord> records = recordsCaptor.getValue();
        assertEquals(6, records.size());

        assertEquals(AggregateReportingService.DIM_PROVIDER, records.get(0).dimensionType());
        assertEquals("openai", records.get(0).dimensionKey());
        assertEquals("openai", records.get(0).displayName());
        assertEquals(1L, records.get(0).requests());
        assertEquals(321L, records.get(0).tokens());
        assertEquals(BigDecimal.valueOf(1.25d), records.get(0).costUsd());

        assertEquals(AggregateReportingService.DIM_USER, records.get(1).dimensionType());
        assertEquals("alice", records.get(1).dimensionKey());

        assertEquals(AggregateReportingService.DIM_KEY, records.get(2).dimensionType());
        assertEquals("key-1", records.get(2).dimensionKey());
        assertEquals("Key One", records.get(2).displayName());

        assertEquals(AggregateReportingService.DIM_CLIENT, records.get(3).dimensionType());
        assertEquals("client-1", records.get(3).dimensionKey());

        assertEquals(AggregateReportingService.DIM_MODEL, records.get(4).dimensionType());
        assertEquals("gpt-4o", records.get(4).dimensionKey());

        assertEquals(AggregateReportingService.DIM_STATUS, records.get(5).dimensionType());
        assertEquals("2xx", records.get(5).dimensionKey());
        assertEquals("2xx", records.get(5).displayName());
    }

    @Test
    void recordSuccessShouldDeduplicateByRequestId() {
        Instant now = Instant.parse("2026-06-04T10:15:30Z");

        service.recordSuccess("req-dup", "openai", "alice", "key-1", "Key One", "client-1", "gpt-4o", 321L, 1.25d, now);
        service.recordSuccess("req-dup", "anthropic", "bob", "key-2", "Key Two", "client-2", "claude", 999L, 3.50d, now.plusSeconds(5));
        service.flushPending();

        verify(store, times(1)).recordAll(
                org.mockito.ArgumentMatchers.anyList(),
                any(Instant.class)
        );
        verifyNoMoreInteractions(store);
    }

    @Test
    void recordFailureStatusShouldUseCorrectStatusBuckets() {
        Instant now = Instant.parse("2026-06-04T10:15:30Z");

        service.recordFailureStatus("req-2xx", 204, now);
        service.recordFailureStatus("req-4xx", 404, now);
        service.recordFailureStatus("req-5xx", 503, now);
        service.recordFailureStatus("req-other", 301, now);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
        verify(store, times(4)).record(
                eq(AggregateReportingService.DIM_STATUS),
                keyCaptor.capture(),
                nameCaptor.capture(),
                eq(1L),
                eq(0L),
                eq(BigDecimal.ZERO),
                eq(now)
        );

        assertEquals(List.of("2xx", "4xx", "5xx", "other"), keyCaptor.getAllValues());
        assertEquals(List.of("2xx", "4xx", "5xx", "other"), nameCaptor.getAllValues());
    }

    @Test
    void recordFailureStatusShouldDeduplicateByRequestId() {
        Instant now = Instant.parse("2026-06-04T10:15:30Z");

        service.recordFailureStatus("req-fail", 500, now);
        service.recordFailureStatus("req-fail", 404, now.plusSeconds(3));

        verify(store).record(AggregateReportingService.DIM_STATUS, "5xx", "5xx", 1L, 0L, BigDecimal.ZERO, now);
        verifyNoMoreInteractions(store);
    }

    @Test
    void bucketAccessorsShouldDelegateToStoreAndReturnExpectedBucket() {
        LocalDate day = LocalDate.parse("2026-06-04");
        YearMonth month = YearMonth.parse("2026-06");
        List<AggregateMetricStore.AggregateMetric> dailyItems = List.of(
                new AggregateMetricStore.AggregateMetric("provider", "openai", "OpenAI", 3L, 50L, BigDecimal.ONE, "2026-06-04")
        );
        List<AggregateMetricStore.AggregateMetric> monthlyItems = List.of(
                new AggregateMetricStore.AggregateMetric("status", "2xx", "2xx", 9L, 100L, BigDecimal.TEN, "2026-06")
        );

        when(store.getDaily(AggregateReportingService.DIM_PROVIDER, day)).thenReturn(dailyItems);
        when(store.getDaily(AggregateReportingService.DIM_USER, day)).thenReturn(dailyItems);
        when(store.getDaily(AggregateReportingService.DIM_KEY, day)).thenReturn(dailyItems);
        when(store.getDaily(AggregateReportingService.DIM_CLIENT, day)).thenReturn(dailyItems);
        when(store.getDaily(AggregateReportingService.DIM_MODEL, day)).thenReturn(dailyItems);
        when(store.getMonthly(AggregateReportingService.DIM_STATUS, month)).thenReturn(monthlyItems);

        AggregateReportingService.ReportingBucket providers = service.providers("day", "2026-06-04");
        AggregateReportingService.ReportingBucket users = service.users("day", "2026-06-04");
        AggregateReportingService.ReportingBucket keys = service.keys("day", "2026-06-04");
        AggregateReportingService.ReportingBucket clients = service.clients("day", "2026-06-04");
        AggregateReportingService.ReportingBucket models = service.models("day", "2026-06-04");
        AggregateReportingService.ReportingBucket statuses = service.statuses("month", "2026-06");

        assertEquals("day", providers.period());
        assertEquals("2026-06-04", providers.bucket());
        assertSame(dailyItems, providers.items());
        assertSame(dailyItems, users.items());
        assertSame(dailyItems, keys.items());
        assertSame(dailyItems, clients.items());
        assertSame(dailyItems, models.items());
        assertEquals("month", statuses.period());
        assertEquals("2026-06", statuses.bucket());
        assertSame(monthlyItems, statuses.items());

        verify(store).getDaily(AggregateReportingService.DIM_PROVIDER, day);
        verify(store).getDaily(AggregateReportingService.DIM_USER, day);
        verify(store).getDaily(AggregateReportingService.DIM_KEY, day);
        verify(store).getDaily(AggregateReportingService.DIM_CLIENT, day);
        verify(store).getDaily(AggregateReportingService.DIM_MODEL, day);
        verify(store).getMonthly(AggregateReportingService.DIM_STATUS, month);
    }

    @Test
    void resetForTestsShouldClearProcessedRequestIds() {
        Instant now = Instant.parse("2026-06-04T10:15:30Z");

        service.recordSuccess("req-reset", "openai", "alice", "key-1", "Key One", "client-1", "gpt-4o", 10L, 0.5d, now);
        service.recordSuccess("req-reset", "openai", "alice", "key-1", "Key One", "client-1", "gpt-4o", 10L, 0.5d, now.plusSeconds(1));
        service.resetForTests();
        service.recordSuccess("req-reset", "openai", "alice", "key-1", "Key One", "client-1", "gpt-4o", 10L, 0.5d, now.plusSeconds(2));
        service.flushPending();

        verify(store, times(1)).recordAll(
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any(Instant.class)
        );
    }

    @Test
    void recordSuccessShouldReplaceNullAndBlankValuesWithUnknown() {
        Instant now = Instant.parse("2026-06-04T10:15:30Z");

        service.recordSuccess("req-unknown", null, "   ", "", "Key Name", null, "\t", 5L, 0.1d, now);
        service.flushPending();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AggregateMetricStore.DimensionRecord>> recordsCaptor = ArgumentCaptor.forClass(List.class);
        verify(store).recordAll(recordsCaptor.capture(), any(Instant.class));

        List<AggregateMetricStore.DimensionRecord> records = recordsCaptor.getValue();
        java.util.Map<String, String> keysByDimension = new java.util.HashMap<>();
        for (var r : records) {
            keysByDimension.put(r.dimensionType(), r.dimensionKey());
        }

        assertEquals("unknown", keysByDimension.get(AggregateReportingService.DIM_PROVIDER));
        assertEquals("unknown", keysByDimension.get(AggregateReportingService.DIM_USER));
        assertEquals("unknown", keysByDimension.get(AggregateReportingService.DIM_KEY));
        assertEquals("unknown", keysByDimension.get(AggregateReportingService.DIM_CLIENT));
        assertEquals("unknown", keysByDimension.get(AggregateReportingService.DIM_MODEL));
        assertEquals("2xx", keysByDimension.get(AggregateReportingService.DIM_STATUS));
    }

    @Test
    void recordSuccessShouldConvertNullCostToZero() {
        Instant now = Instant.parse("2026-06-04T10:15:30Z");

        service.recordSuccess("req-zero-cost", "openai", "alice", "key-1", "Key One", "client-1", "gpt-4o", 12L, null, now);
        service.flushPending();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AggregateMetricStore.DimensionRecord>> recordsCaptor = ArgumentCaptor.forClass(List.class);
        verify(store).recordAll(recordsCaptor.capture(), any(Instant.class));

        List<AggregateMetricStore.DimensionRecord> records = recordsCaptor.getValue();
        assertEquals(6, records.size());
        for (var r : records) {
            assertEquals(BigDecimal.ZERO, r.costUsd());
        }
    }

    @Test
    void safeHelperShouldReturnUnknownForNullOrBlankOtherwiseAsIs() throws Exception {
        Method safe = AggregateReportingService.class.getDeclaredMethod("safe", String.class, String.class);
        safe.setAccessible(true);

        assertEquals("unknown", safe.invoke(service, null, "unknown"));
        assertEquals("unknown", safe.invoke(service, "   ", "unknown"));
        assertEquals("value", safe.invoke(service, "value", "unknown"));
    }
}
