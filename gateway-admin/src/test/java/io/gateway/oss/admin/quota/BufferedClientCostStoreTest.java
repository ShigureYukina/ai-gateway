package io.gateway.oss.admin.quota;

import io.gateway.oss.core.util.RedisStoreUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BufferedClientCostStoreTest {

    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private ClientCostStore delegate;

    private final Instant now = Instant.parse("2026-09-01T00:30:00Z");

    @Test
    void checkAndRecordBoth_buffersAndAppliesInMemoryTotals() {
        when(delegate.currentDailyCost(anyString(), any(Instant.class))).thenReturn(BigDecimal.ZERO);
        when(delegate.currentMonthlyCost(anyString(), any(Instant.class))).thenReturn(BigDecimal.ZERO);
        BufferedClientCostStore store = new BufferedClientCostStore(jdbc, delegate, "gw");

        ClientCostStore.CostCheckResult result = store.checkAndRecordBoth(
                "client-1", 75_000L, 1_000_000_000L, 10_000_000_000L, now);

        assertEquals(75_000L, result.dailyMicros());
        assertEquals(75_000L, result.monthlyMicros());
        assertEquals(new BigDecimal("0.075000"), store.currentDailyCost("client-1", now));
        verify(delegate, never()).checkAndRecordBoth(anyString(), anyLong(), anyLong(), anyLong(), any(Instant.class));
    }

    @Test
    void flushMergesSameConflictKeysIntoSingleRowPerKey() {
        // 压测实测缺陷回归：同键重复行在 batchUpdate 内触发 PG 21000，
        // 整批 cost 记录被静默丢弃（flush 失败不回灌）。
        when(delegate.currentDailyCost(anyString(), any(Instant.class))).thenReturn(BigDecimal.ZERO);
        when(delegate.currentMonthlyCost(anyString(), any(Instant.class))).thenReturn(BigDecimal.ZERO);
        BufferedClientCostStore store = new BufferedClientCostStore(jdbc, delegate, "gw");
        store.checkAndRecordBoth("client-1", 75_000L, 1_000_000_000L, 10_000_000_000L, now);
        store.checkAndRecordBoth("client-1", 25_000L, 1_000_000_000L, 10_000_000_000L, now);
        store.checkAndRecordBoth("client-2", 10_000L, 1_000_000_000L, 10_000_000_000L, now);

        store.flushPending();

        String ns = RedisStoreUtils.safePrefix("gw");
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<Object[]>> argsCaptor = ArgumentCaptor.forClass(List.class);
        // daily 与 monthly 的 SQL 在 cost 存储中相同，恰好各执行一次；两次 flush 合计 4 行
        verify(jdbc, times(2)).batchUpdate(sqlCaptor.capture(), argsCaptor.capture());

        List<Object[]> allRows = new java.util.ArrayList<>();
        for (List<Object[]> rows : argsCaptor.getAllValues()) {
            assertEquals(2, rows.size(), "同键记录必须聚合为单行");
            allRows.addAll(rows);
        }
        assertEquals(4, allRows.size());
        assertCostRow(allRows, ns, "client-1", "client-1:2026-09-01", 100_000L);
        assertCostRow(allRows, ns, "client-2", "client-2:2026-09-01", 10_000L);
        assertCostRow(allRows, ns, "client-1", "client-1:2026-09", 100_000L);
        assertCostRow(allRows, ns, "client-2", "client-2:2026-09", 10_000L);
    }

    private void assertCostRow(List<Object[]> rows, String ns, String clientId, String periodKey, long costMicros) {
        for (Object[] row : rows) {
            if (periodKey.equals(row[2])) {
                assertEquals(ns, row[0]);
                assertEquals(clientId, row[1]);
                assertEquals(costMicros, ((Number) row[3]).longValue());
                return;
            }
        }
        fail("cost row not found: " + periodKey);
    }
}
