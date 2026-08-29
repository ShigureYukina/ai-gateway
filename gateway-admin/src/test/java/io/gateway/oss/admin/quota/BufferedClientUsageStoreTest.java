package io.gateway.oss.admin.quota;

import io.gateway.oss.core.util.RedisStoreUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BufferedClientUsageStoreTest {

    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private ClientUsageStore delegate;

    private final Instant now = Instant.parse("2026-09-01T00:30:00Z");

    @Test
    void checkAndRecordBoth_buffersAndAppliesInMemoryTotals() {
        BufferedClientUsageStore store = new BufferedClientUsageStore(jdbc, delegate, "gw");

        ClientUsageStore.UsageCheckResult result = store.checkAndRecordBoth("client-1", 10, 1000, 2000, now);

        assertEquals(10L, result.daily());
        assertEquals(10L, result.monthly());
        assertEquals(10L, store.currentDailyUsage("client-1", now));
        verify(delegate, never()).checkAndRecordBoth(anyString(), anyLong(), anyLong(), anyLong(), any(Instant.class));
    }

    @Test
    void flushMergesSameConflictKeysIntoSingleRowPerKey() {
        // 同一 (namespace, client_id, period_key) 在批次内只允许出现一次，
        // 否则 PG 21000 "cannot affect row a second time" 整批失败（基线缺陷，
        // 压测中 10 万次 usage flush 全部失败、client_usage 落库为空即由此而来）。
        BufferedClientUsageStore store = new BufferedClientUsageStore(jdbc, delegate, "gw");
        store.checkAndRecordBoth("client-1", 10, 1000, 2000, now);
        store.checkAndRecordBoth("client-1", 20, 1000, 2000, now);
        store.checkAndRecordBoth("client-2", 5, 1000, 2000, now);

        store.flushPending();

        String ns = RedisStoreUtils.safePrefix("gw");
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<Object[]>> argsCaptor = ArgumentCaptor.forClass(List.class);
        verify(jdbc, times(2)).batchUpdate(sqlCaptor.capture(), argsCaptor.capture());

        List<Object[]> dailyRows = null;
        List<Object[]> monthlyRows = null;
        for (int i = 0; i < sqlCaptor.getAllValues().size(); i++) {
            if (sqlCaptor.getAllValues().get(i).contains("EXCLUDED.request_cnt")) {
                dailyRows = argsCaptor.getAllValues().get(i);
            } else {
                monthlyRows = argsCaptor.getAllValues().get(i);
            }
        }

        assertTrue(sqlCaptor.getAllValues().stream().anyMatch(s -> s.contains("EXCLUDED.request_cnt")),
                "daily flush SQL 应参数化 request_cnt（原 SQL 与 5 参数 builder 不匹配）");
        assertEquals(2, dailyRows.size(), "同键记录必须聚合为单行");
        assertUsageRow(dailyRows, ns, "client-1", "client-1:2026-09-01", 30L, 2);
        assertUsageRow(dailyRows, ns, "client-2", "client-2:2026-09-01", 5L, 1);

        assertEquals(2, monthlyRows.size());
        assertMonthlyRow(monthlyRows, ns, "client-1", "client-1:2026-09", 30L);
        assertMonthlyRow(monthlyRows, ns, "client-2", "client-2:2026-09", 5L);
    }

    private void assertUsageRow(List<Object[]> rows, String ns, String clientId, String periodKey,
                                long tokens, int requestCount) {
        for (Object[] row : rows) {
            if (periodKey.equals(row[2])) {
                assertEquals(ns, row[0]);
                assertEquals(clientId, row[1]);
                assertEquals(tokens, ((Number) row[3]).longValue());
                assertEquals(requestCount, ((Number) row[4]).intValue());
                return;
            }
        }
        fail("usage row not found: " + periodKey);
    }

    private void assertMonthlyRow(List<Object[]> rows, String ns, String clientId, String periodKey, long tokens) {
        for (Object[] row : rows) {
            if (periodKey.equals(row[2])) {
                assertEquals(ns, row[0]);
                assertEquals(clientId, row[1]);
                assertEquals(tokens, ((Number) row[3]).longValue());
                return;
            }
        }
        fail("monthly usage row not found: " + periodKey);
    }
}
