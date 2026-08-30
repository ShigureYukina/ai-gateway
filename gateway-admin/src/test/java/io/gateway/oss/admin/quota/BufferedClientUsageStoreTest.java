package io.gateway.oss.admin.quota;

import io.gateway.oss.core.util.RedisStoreUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    void flushMergesSameConflictKeysIntoSingleRowPerKey() throws Exception {
        // 同一 (namespace, client_id, period_key) 在批次内只允许出现一次，
        // 否则 PG 21000 "cannot affect row a second time" 整批失败（基线缺陷，
        // 压测中 10 万次 usage flush 全部失败、client_usage 落库为空即由此而来）。
        BufferedClientUsageStore store = new BufferedClientUsageStore(jdbc, delegate, "gw");
        store.checkAndRecordBoth("client-1", 10, 1000, 2000, now);
        store.checkAndRecordBoth("client-1", 20, 1000, 2000, now);
        store.checkAndRecordBoth("client-2", 5, 1000, 2000, now);

        Connection connection = mock(Connection.class);
        PreparedStatement dailyStatement = mock(PreparedStatement.class);
        PreparedStatement monthlyStatement = mock(PreparedStatement.class);
        when(jdbc.execute(any(ConnectionCallback.class))).thenAnswer(inv ->
                ((ConnectionCallback<?>) inv.getArgument(0)).doInConnection(connection));
        when(connection.prepareStatement(argThat(sql -> sql != null && sql.contains("request_cnt")))).thenReturn(dailyStatement);
        when(connection.prepareStatement(argThat(sql -> sql != null && !sql.contains("request_cnt")))).thenReturn(monthlyStatement);

        store.flushPending();

        String ns = RedisStoreUtils.safePrefix("gw");
        ArgumentCaptor<Object> dailyBinds = ArgumentCaptor.forClass(Object.class);
        verify(dailyStatement, times(10)).setObject(anyInt(), dailyBinds.capture());
        java.util.List<Object> dailyValues = dailyBinds.getAllValues();
        // 聚合后仅 2 行（client-1 两记录合并为 tokens=30/request_cnt=2）
        assertEquals(ns, dailyValues.get(0));
        assertEquals("client-1", dailyValues.get(1));
        assertEquals("client-1:2026-09-01", dailyValues.get(2));
        assertEquals(30L, ((Number) dailyValues.get(3)).longValue());
        assertEquals(2, ((Number) dailyValues.get(4)).intValue());
        assertEquals("client-2:2026-09-01", dailyValues.get(7));
        assertEquals(5L, ((Number) dailyValues.get(8)).longValue());

        ArgumentCaptor<Object> monthlyBinds = ArgumentCaptor.forClass(Object.class);
        verify(monthlyStatement, times(8)).setObject(anyInt(), monthlyBinds.capture());
        assertEquals("client-1:2026-09", monthlyBinds.getAllValues().get(2));
        assertEquals(30L, ((Number) monthlyBinds.getAllValues().get(3)).longValue());
        assertEquals("client-2:2026-09", monthlyBinds.getAllValues().get(6));
        verify(dailyStatement).executeBatch();
        verify(monthlyStatement).executeBatch();
        verify(connection).commit();
        verify(connection, never()).rollback();
    }

    @Test
    void flushFailureRequeuesBatchForRetry() throws Exception {
        // 审查 D3：flush 失败不得静默丢账，事务回滚后整批回灌下一轮重试
        BufferedClientUsageStore store = new BufferedClientUsageStore(jdbc, delegate, "gw");
        store.checkAndRecordBoth("client-1", 10, 1000, 2000, now);

        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(jdbc.execute(any(ConnectionCallback.class))).thenAnswer(inv ->
                ((ConnectionCallback<?>) inv.getArgument(0)).doInConnection(connection));
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeBatch()).thenThrow(new SQLException("pg down")).thenReturn(new int[]{1});

        store.flushPending();
        verify(connection).rollback();

        store.flushPending();

        // 第二轮重试成功：同一批记录再次绑定（未被丢弃）
        ArgumentCaptor<Object> binds = ArgumentCaptor.forClass(Object.class);
        verify(statement, atLeastOnce()).setObject(anyInt(), binds.capture());
        assertTrue(binds.getAllValues().contains("client-1:2026-09-01"));
        // 失败轮的 daily executeBatch（抛异常）+ 重试轮的 daily/monthly 两次
        verify(statement, times(3)).executeBatch();
        verify(connection).commit();
        assertEquals(10L, store.currentDailyUsage("client-1", now));
    }
}
