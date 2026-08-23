package io.gateway.oss.admin.quota;

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
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostgresClientUsageStoreTest {

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

    private PostgresClientUsageStore createStore() {
        return new PostgresClientUsageStore(jdbc, "gw");
    }

    private final Instant now = Instant.parse("2026-05-22T10:00:00Z");

    @Test
    void checkAndRecord_underQuota_upsertsAndReturns() {
        PostgresClientUsageStore store = createStore();
        when(jdbc.query(argThat(sql -> ((String) sql).contains("INSERT INTO client_usage (namespace, client_id, period_key") && ((String) sql).contains("RETURNING tokens")),
                any(ResultSetExtractor.class), anyString(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyLong()))
                .thenReturn(50L);

        long result = store.checkAndRecord("client-1", 10, 100, now);
        assertEquals(50L, result);

        verify(jdbc).query(
                argThat(sql -> sql.contains("INSERT INTO client_usage (namespace, client_id, period_key, tokens, request_cnt)")
                        && sql.contains("SELECT ?, ?, ?, ?, 1 WHERE ? <= ?")
                        && sql.contains("tokens = client_usage.tokens + EXCLUDED.tokens")
                        && sql.contains("WHERE client_usage.tokens + EXCLUDED.tokens <= ?")
                        && sql.contains("RETURNING tokens")),
                any(ResultSetExtractor.class),
                eq("gw"), eq("client-1"), anyString(), eq(10L), eq(10L), eq(100L), eq(100L));
    }

    @Test
    void checkAndRecord_overQuota_returnsMinusOne() {
        PostgresClientUsageStore store = createStore();
        when(jdbc.query(argThat(sql -> ((String) sql).contains("INSERT INTO client_usage (namespace, client_id, period_key") && ((String) sql).contains("RETURNING tokens")),
                any(ResultSetExtractor.class), anyString(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyLong()))
                .thenReturn(null);

        long result = store.checkAndRecord("client-1", 10, 100, now);
        assertEquals(-1L, result);

        verify(jdbc).query(
                argThat(sql -> sql.contains("SELECT ?, ?, ?, ?, 1 WHERE ? <= ?")
                        && sql.contains("WHERE client_usage.tokens + EXCLUDED.tokens <= ?")
                        && sql.contains("RETURNING tokens")),
                any(ResultSetExtractor.class),
                eq("gw"), eq("client-1"), anyString(), eq(10L), eq(10L), eq(100L), eq(100L));
    }

    @Test
    void currentDailyUsage_selectsTokens() {
        PostgresClientUsageStore store = createStore();
        when(jdbc.query(argThat(sql -> ((String) sql).contains("SELECT tokens FROM client_usage WHERE")), any(ResultSetExtractor.class), eq("gw"), eq("client-1"), anyString()))
                .thenReturn(75L);

        long result = store.currentDailyUsage("client-1", now);
        assertEquals(75L, result);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sqlCaptor.capture(), any(ResultSetExtractor.class), eq("gw"), eq("client-1"), anyString());
        assertTrue(sqlCaptor.getValue().contains("SELECT tokens FROM client_usage WHERE namespace = ? AND client_id = ?"));
    }

    @Test
    void currentMonthlyUsage_selectsTokens() {
        PostgresClientUsageStore store = createStore();
        when(jdbc.query(argThat(sql -> ((String) sql).contains("SELECT tokens FROM client_usage WHERE")), any(ResultSetExtractor.class), eq("gw"), eq("client-1"), anyString()))
                .thenReturn(200L);

        long result = store.currentMonthlyUsage("client-1", now);
        assertEquals(200L, result);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sqlCaptor.capture(), any(ResultSetExtractor.class), eq("gw"), eq("client-1"), anyString());
        assertTrue(sqlCaptor.getValue().contains("SELECT tokens FROM client_usage WHERE namespace = ? AND client_id = ?"));
    }

    @Test
    void addDailyUsage_upserts() {
        PostgresClientUsageStore store = createStore();
        when(jdbc.update(argThat(sql -> ((String) sql).contains("INSERT INTO client_usage")), anyString(), anyString(), anyString(), anyLong(), anyLong()))
                .thenReturn(1);

        store.addDailyUsage("client-1", 25, now);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sqlCaptor.capture(), eq("gw"), eq("client-1"), anyString(), eq(25L), eq(25L));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("INSERT INTO client_usage (namespace, client_id, period_key, tokens)"));
        assertTrue(sql.contains("ON CONFLICT"));
        assertTrue(sql.contains("DO UPDATE SET tokens = client_usage.tokens + ?"));
        assertEquals(1L, meterRegistry.get("gateway.write.latency").tag("writePoint", "usageAddDaily").timer().count());
    }

    @Test
    void addMonthlyUsage_upserts() {
        PostgresClientUsageStore store = createStore();
        when(jdbc.update(argThat(sql -> ((String) sql).contains("INSERT INTO client_usage")), anyString(), anyString(), anyString(), anyLong(), anyLong()))
                .thenReturn(1);

        store.addMonthlyUsage("client-1", 25, now);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sqlCaptor.capture(), eq("gw"), eq("client-1"), anyString(), eq(25L), eq(25L));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("INSERT INTO client_usage (namespace, client_id, period_key, tokens)"));
        assertTrue(sql.contains("ON CONFLICT"));
        assertTrue(sql.contains("DO UPDATE SET tokens = client_usage.tokens + ?"));
        assertEquals(1L, meterRegistry.get("gateway.write.latency").tag("writePoint", "usageAddMonthly").timer().count());
    }

    @Test
    void currentDailyRequestCount_selectsRequestCnt() {
        PostgresClientUsageStore store = createStore();
        when(jdbc.query(argThat(sql -> ((String) sql).contains("SELECT request_cnt FROM client_usage WHERE")), any(ResultSetExtractor.class), eq("gw"), eq("client-1"), anyString()))
                .thenReturn(5L);

        long result = store.currentDailyRequestCount("client-1", now);
        assertEquals(5L, result);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sqlCaptor.capture(), any(ResultSetExtractor.class), eq("gw"), eq("client-1"), anyString());
        assertTrue(sqlCaptor.getValue().contains("SELECT request_cnt FROM client_usage WHERE namespace = ? AND client_id = ?"));
    }

    @Test
    void addDailyRequestCount_upserts() {
        PostgresClientUsageStore store = createStore();
        when(jdbc.update(argThat(sql -> ((String) sql).contains("INSERT INTO client_usage")), anyString(), anyString(), anyString()))
                .thenReturn(1);

        store.addDailyRequestCount("client-1", now);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sqlCaptor.capture(), eq("gw"), eq("client-1"), anyString());
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("INSERT INTO client_usage (namespace, client_id, period_key, request_cnt) VALUES (?, ?, ?, 1)"));
        assertTrue(sql.contains("ON CONFLICT"));
        assertTrue(sql.contains("DO UPDATE SET request_cnt = client_usage.request_cnt + 1"));
        assertEquals(1L, meterRegistry.get("gateway.write.latency").tag("writePoint", "usageAddDailyRequestCount").timer().count());
    }

    @Test
    void checkAndRecordMonthly_underQuota_upsertsAndReturns() {
        PostgresClientUsageStore store = createStore();
        when(jdbc.query(argThat(sql -> ((String) sql).contains("INSERT INTO client_usage (namespace, client_id, period_key") && ((String) sql).contains("RETURNING tokens")),
                any(ResultSetExtractor.class), anyString(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyLong()))
                .thenReturn(30L);

        long result = store.checkAndRecordMonthly("client-1", 10, 200, now);
        assertEquals(30L, result);

        verify(jdbc).query(
                argThat(sql -> sql.contains("INSERT INTO client_usage (namespace, client_id, period_key, tokens)")
                        && sql.contains("SELECT ?, ?, ?, ? WHERE ? <= ?")
                        && sql.contains("tokens = client_usage.tokens + EXCLUDED.tokens")
                        && sql.contains("WHERE client_usage.tokens + EXCLUDED.tokens <= ?")
                        && sql.contains("RETURNING tokens")),
                any(ResultSetExtractor.class),
                eq("gw"), eq("client-1"), anyString(), eq(10L), eq(10L), eq(200L), eq(200L));
        assertEquals(1L, meterRegistry.get("gateway.write.latency").tag("writePoint", "usageCheckAndRecordMonthly").timer().count());
    }

    @Test
    void checkAndRecordMonthly_overQuota_returnsMinusOne() {
        PostgresClientUsageStore store = createStore();
        when(jdbc.query(argThat(sql -> ((String) sql).contains("INSERT INTO client_usage (namespace, client_id, period_key") && ((String) sql).contains("RETURNING tokens")),
                any(ResultSetExtractor.class), anyString(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyLong()))
                .thenReturn(null);

        long result = store.checkAndRecordMonthly("client-1", 10, 200, now);
        assertEquals(-1L, result);

        verify(jdbc).query(
                argThat(sql -> sql.contains("SELECT ?, ?, ?, ? WHERE ? <= ?")
                        && sql.contains("WHERE client_usage.tokens + EXCLUDED.tokens <= ?")
                        && sql.contains("RETURNING tokens")),
                any(ResultSetExtractor.class),
                eq("gw"), eq("client-1"), anyString(), eq(10L), eq(10L), eq(200L), eq(200L));
    }

    @Test
    void checkAndRecordBoth_usesSingleCteSqlForDailyAndMonthly() throws Exception {
        PostgresClientUsageStore store = createStore();
        Connection connection = mock(Connection.class);
        PreparedStatement combinedStatement = mock(PreparedStatement.class);
        ResultSet combinedResultSet = mock(ResultSet.class);

        when(connection.prepareStatement(argThat(sql -> sql != null && sql.contains("WITH daily_ins AS") && sql.contains("monthly_ins AS"))))
                .thenReturn(combinedStatement);
        when(combinedStatement.executeQuery()).thenReturn(combinedResultSet);
        when(combinedResultSet.next()).thenReturn(true, true, false);
        when(combinedResultSet.getString("period")).thenReturn("daily", "monthly");
        when(combinedResultSet.getLong("tokens")).thenReturn(50L, 90L);
        when(jdbc.execute(any(ConnectionCallback.class))).thenAnswer(invocation -> {
            ConnectionCallback<?> callback = invocation.getArgument(0);
            return callback.doInConnection(connection);
        });

        ClientUsageStore.UsageCheckResult result = store.checkAndRecordBoth("client-1", 10, 100, 200, now);

        assertEquals(50L, result.daily());
        assertEquals(90L, result.monthly());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection, times(1)).prepareStatement(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("daily_ins AS"));
        assertTrue(sql.contains("monthly_ins AS"));
        assertTrue(sql.contains("UNION ALL"));
        assertTrue(sql.contains("RETURNING tokens"));
        assertEquals(1L, meterRegistry.get("gateway.write.latency").tag("writePoint", "usageCheckAndRecordDaily").timer().count());
        assertEquals(1L, meterRegistry.get("gateway.write.latency").tag("writePoint", "usageCheckAndRecordMonthly").timer().count());
        assertEquals(1L, meterRegistry.get("gateway.write.latency").tag("writePoint", "usageCheckAndRecordBoth").timer().count());
    }

    @Test
    void checkAndRecordBoth_zeroTokensKeepsDailyRequestCountAndSingleSelectRead() throws Exception {
        PostgresClientUsageStore store = createStore();
        Connection connection = mock(Connection.class);
        PreparedStatement requestCountStatement = mock(PreparedStatement.class);
        PreparedStatement selectStatement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);

        when(connection.prepareStatement(argThat(sql -> sql != null && sql.contains("request_cnt) VALUES (?, ?, ?, 1)"))))
                .thenReturn(requestCountStatement);
        when(connection.prepareStatement(argThat(sql -> sql != null && sql.contains("SELECT period_key, tokens FROM client_usage WHERE"))))
                .thenReturn(selectStatement);
        when(selectStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString("period_key")).thenReturn("client-1:2026-05-22", "client-1:2026-05-01");
        when(resultSet.getLong("tokens")).thenReturn(12L, 34L);
        when(jdbc.execute(any(ConnectionCallback.class))).thenAnswer(invocation -> {
            ConnectionCallback<?> callback = invocation.getArgument(0);
            return callback.doInConnection(connection);
        });

        ClientUsageStore.UsageCheckResult result = store.checkAndRecordBoth("client-1", 0, 100, 200, now);

        assertEquals(12L, result.daily());
        assertEquals(34L, result.monthly());
        verify(requestCountStatement).executeUpdate();
        verify(connection, times(2)).prepareStatement(anyString());
    }

}
