package io.gateway.oss.admin.quota;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
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
    void flushMergesSameConflictKeysIntoSingleRowPerKey() throws Exception {
        // 压测实测缺陷回归：同键重复行在 batch 内触发 PG 21000，
        // 整批 cost 记录被静默丢弃（flush 失败不回灌）。
        when(delegate.currentDailyCost(anyString(), any(Instant.class))).thenReturn(BigDecimal.ZERO);
        when(delegate.currentMonthlyCost(anyString(), any(Instant.class))).thenReturn(BigDecimal.ZERO);
        BufferedClientCostStore store = new BufferedClientCostStore(jdbc, delegate, "gw");
        store.checkAndRecordBoth("client-1", 75_000L, 1_000_000_000L, 10_000_000_000L, now);
        store.checkAndRecordBoth("client-1", 25_000L, 1_000_000_000L, 10_000_000_000L, now);
        store.checkAndRecordBoth("client-2", 10_000L, 1_000_000_000L, 10_000_000_000L, now);

        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(jdbc.execute(any(ConnectionCallback.class))).thenAnswer(inv ->
                ((ConnectionCallback<?>) inv.getArgument(0)).doInConnection(connection));
        when(connection.prepareStatement(anyString())).thenReturn(statement);

        store.flushPending();

        // cost 的 daily/monthly SQL 相同；聚合后每语句 2 行（4 次 executeBatch 绑定 8 行）
        ArgumentCaptor<Object> binds = ArgumentCaptor.forClass(Object.class);
        verify(statement, times(16)).setObject(anyInt(), binds.capture());
        java.util.List<Object> values = binds.getAllValues();
        assertEquals("client-1:2026-09-01", values.get(2));
        assertEquals(100_000L, ((Number) values.get(3)).longValue());
        assertEquals("client-2:2026-09-01", values.get(6));
        assertEquals(10_000L, ((Number) values.get(7)).longValue());
        assertEquals("client-1:2026-09", values.get(10));
        assertEquals("client-2:2026-09", values.get(14));
        verify(statement, times(2)).executeBatch();
        verify(connection).commit();
    }

    @Test
    void flushFailureRequeuesBatchForRetry() throws Exception {
        // 审查 D3：flush 失败不得静默丢账，事务回滚后整批回灌下一轮重试
        when(delegate.currentDailyCost(anyString(), any(Instant.class))).thenReturn(BigDecimal.ZERO);
        when(delegate.currentMonthlyCost(anyString(), any(Instant.class))).thenReturn(BigDecimal.ZERO);
        BufferedClientCostStore store = new BufferedClientCostStore(jdbc, delegate, "gw");
        store.checkAndRecordBoth("client-1", 75_000L, 1_000_000_000L, 10_000_000_000L, now);

        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(jdbc.execute(any(ConnectionCallback.class))).thenAnswer(inv ->
                ((ConnectionCallback<?>) inv.getArgument(0)).doInConnection(connection));
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeBatch()).thenThrow(new SQLException("pg down")).thenReturn(new int[]{1});

        store.flushPending();
        verify(connection).rollback();

        store.flushPending();

        ArgumentCaptor<Object> binds = ArgumentCaptor.forClass(Object.class);
        verify(statement, atLeastOnce()).setObject(anyInt(), binds.capture());
        assertTrue(binds.getAllValues().contains("client-1:2026-09-01"));
        // 失败轮的 daily executeBatch（抛异常）+ 重试轮的 daily/monthly 两次
        verify(statement, times(3)).executeBatch();
        verify(connection).commit();
        assertEquals(new BigDecimal("0.075000"), store.currentDailyCost("client-1", now));
    }
}
