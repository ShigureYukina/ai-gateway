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
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostgresClientCostStoreTest {

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

    private PostgresClientCostStore createStore() {
        return new PostgresClientCostStore(jdbc, "gw");
    }

    private final Instant now = Instant.parse("2026-05-22T10:00:00Z");

    @Test
    void checkAndRecord_underBudget_upsertsAndReturns() {
        PostgresClientCostStore store = createStore();
        when(jdbc.query(argThat(sql -> ((String) sql).contains("INSERT INTO client_cost (namespace, client_id, period_key") && ((String) sql).contains("RETURNING cost_micros")), any(ResultSetExtractor.class), anyString(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyLong()))
                .thenReturn(5000L);

        long result = store.checkAndRecord("client-1", 1000, 10000, now);
        assertEquals(5000L, result);

        verify(jdbc).query(
                argThat(sql -> sql.contains("INSERT INTO client_cost (namespace, client_id, period_key, cost_micros)")
                        && sql.contains("SELECT ?, ?, ?, ? WHERE ? <= ?")
                        && sql.contains("ON CONFLICT")
                        && sql.contains("EXCLUDED.cost_micros")
                        && sql.contains("RETURNING cost_micros")),
                any(ResultSetExtractor.class),
                eq("gw"), eq("client-1"), anyString(), eq(1000L), eq(1000L), eq(10000L), eq(10000L));
        verify(jdbc, never()).update(anyString(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void checkAndRecord_overBudget_returnsCurrent() {
        PostgresClientCostStore store = createStore();
        when(jdbc.query(argThat(sql -> ((String) sql).contains("INSERT INTO client_cost (namespace, client_id, period_key") && ((String) sql).contains("RETURNING cost_micros")), any(ResultSetExtractor.class), anyString(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyLong()))
                .thenReturn(null);

        long result = store.checkAndRecord("client-1", 1000, 10000, now);
        assertEquals(-1L, result);

        verify(jdbc, never()).update(anyString(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void currentDailyCost_selectsCostMicros() {
        PostgresClientCostStore store = createStore();
        when(jdbc.query(argThat(sql -> ((String) sql).contains("SELECT cost_micros FROM client_cost WHERE")), any(ResultSetExtractor.class), eq("gw"), eq("client-1"), anyString()))
                .thenReturn(5000000L);

        BigDecimal result = store.currentDailyCost("client-1", now);
        assertEquals(new BigDecimal("5.000000"), result);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sqlCaptor.capture(), any(ResultSetExtractor.class), eq("gw"), eq("client-1"), anyString());
        assertTrue(sqlCaptor.getValue().contains("SELECT cost_micros FROM client_cost WHERE namespace = ? AND client_id = ?"));
    }

    @Test
    void addDailyCost_upserts() {
        PostgresClientCostStore store = createStore();
        when(jdbc.update(argThat(sql -> ((String) sql).contains("INSERT INTO client_cost (namespace, client_id, period_key")), anyString(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(1);

        store.addDailyCost("client-1", new BigDecimal("1.50"), now);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sqlCaptor.capture(), eq("gw"), eq("client-1"), anyString(), eq(1500000L), eq(1500000L));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("INSERT INTO client_cost (namespace, client_id, period_key, cost_micros)"));
        assertTrue(sql.contains("ON CONFLICT"));
        assertTrue(sql.contains("DO UPDATE SET cost_micros = client_cost.cost_micros + ?"));
        assertEquals(1L, meterRegistry.get("gateway.write.latency").tag("writePoint", "costAddDaily").timer().count());
    }

    @Test
    void currentMonthlyCost_selectsCostMicros() {
        PostgresClientCostStore store = createStore();
        when(jdbc.query(argThat(sql -> ((String) sql).contains("SELECT cost_micros FROM client_cost WHERE")), any(ResultSetExtractor.class), eq("gw"), eq("client-1"), anyString()))
                .thenReturn(3000000L);

        BigDecimal result = store.currentMonthlyCost("client-1", now);
        assertEquals(new BigDecimal("3.000000"), result);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sqlCaptor.capture(), any(ResultSetExtractor.class), eq("gw"), eq("client-1"), anyString());
        assertTrue(sqlCaptor.getValue().contains("SELECT cost_micros FROM client_cost WHERE namespace = ? AND client_id = ?"));
    }

    @Test
    void addMonthlyCost_upserts() {
        PostgresClientCostStore store = createStore();
        when(jdbc.update(argThat(sql -> ((String) sql).contains("INSERT INTO client_cost (namespace, client_id, period_key")), anyString(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(1);

        store.addMonthlyCost("client-1", new BigDecimal("2.50"), now);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sqlCaptor.capture(), eq("gw"), eq("client-1"), anyString(), eq(2500000L), eq(2500000L));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("INSERT INTO client_cost (namespace, client_id, period_key, cost_micros)"));
        assertTrue(sql.contains("ON CONFLICT"));
        assertTrue(sql.contains("DO UPDATE SET cost_micros = client_cost.cost_micros + ?"));
        assertEquals(1L, meterRegistry.get("gateway.write.latency").tag("writePoint", "costAddMonthly").timer().count());
    }

    @Test
    void checkAndRecordMonthly_underBudget_upsertsAndReturns() {
        PostgresClientCostStore store = createStore();
        when(jdbc.query(argThat(sql -> ((String) sql).contains("INSERT INTO client_cost (namespace, client_id, period_key") && ((String) sql).contains("RETURNING cost_micros")), any(ResultSetExtractor.class), anyString(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyLong()))
                .thenReturn(7000L);

        long result = store.checkAndRecordMonthly("client-1", 2000, 20000, now);
        assertEquals(7000L, result);

        verify(jdbc).query(
                argThat(sql -> sql.contains("INSERT INTO client_cost (namespace, client_id, period_key, cost_micros)")
                        && sql.contains("SELECT ?, ?, ?, ? WHERE ? <= ?")
                        && sql.contains("EXCLUDED.cost_micros")
                        && sql.contains("RETURNING cost_micros")),
                any(ResultSetExtractor.class),
                eq("gw"), eq("client-1"), anyString(), eq(2000L), eq(2000L), eq(20000L), eq(20000L));
        assertEquals(1L, meterRegistry.get("gateway.write.latency").tag("writePoint", "costCheckAndRecordMonthly").timer().count());
    }

    @Test
    void checkAndRecordMonthly_overBudget_returnsMinusOne() {
        PostgresClientCostStore store = createStore();
        when(jdbc.query(argThat(sql -> ((String) sql).contains("INSERT INTO client_cost (namespace, client_id, period_key") && ((String) sql).contains("RETURNING cost_micros")), any(ResultSetExtractor.class), anyString(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyLong()))
                .thenReturn(null);

        long result = store.checkAndRecordMonthly("client-1", 2000, 20000, now);
        assertEquals(-1L, result);

        verify(jdbc, never()).update(anyString(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void checkAndRecordBoth_usesSingleCteSqlForDailyAndMonthly() throws Exception {
        PostgresClientCostStore store = createStore();
        Connection connection = mock(Connection.class);
        PreparedStatement combinedStatement = mock(PreparedStatement.class);
        ResultSet combinedResultSet = mock(ResultSet.class);

        when(connection.prepareStatement(argThat(sql -> sql != null && sql.contains("WITH daily_ins AS") && sql.contains("monthly_ins AS"))))
                .thenReturn(combinedStatement);
        when(combinedStatement.executeQuery()).thenReturn(combinedResultSet);
        when(combinedResultSet.next()).thenReturn(true, true, false);
        when(combinedResultSet.getString("period")).thenReturn("daily", "monthly");
        when(combinedResultSet.getLong("cost_micros")).thenReturn(5000L, 9000L);
        when(jdbc.execute(any(ConnectionCallback.class))).thenAnswer(invocation -> {
            ConnectionCallback<?> callback = invocation.getArgument(0);
            return callback.doInConnection(connection);
        });

        ClientCostStore.CostCheckResult result = store.checkAndRecordBoth("client-1", 1000L, 10000L, 20000L, now);

        assertEquals(5000L, result.dailyMicros());
        assertEquals(9000L, result.monthlyMicros());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection, times(1)).prepareStatement(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("daily_ins AS"));
        assertTrue(sql.contains("monthly_ins AS"));
        assertTrue(sql.contains("UNION ALL"));
        assertTrue(sql.contains("RETURNING cost_micros"));
        assertEquals(1L, meterRegistry.get("gateway.write.latency").tag("writePoint", "costCheckAndRecordDaily").timer().count());
        assertEquals(1L, meterRegistry.get("gateway.write.latency").tag("writePoint", "costCheckAndRecordMonthly").timer().count());
        assertEquals(1L, meterRegistry.get("gateway.write.latency").tag("writePoint", "costCheckAndRecordBoth").timer().count());
    }

    @Test
    void checkAndRecordBoth_zeroCostReadsBothPeriodsWithSingleSelectInsideJdbcExecute() throws Exception {
        PostgresClientCostStore store = createStore();
        Connection connection = mock(Connection.class);
        PreparedStatement selectStatement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> periodCaptor = ArgumentCaptor.forClass(String.class);
        String dailyKey = "client-1:2026-05-22";
        String monthlyKey = "client-1:2026-05-01";

        when(connection.prepareStatement(argThat(sql -> sql != null
                && sql.contains("SELECT period_key, cost_micros FROM client_cost WHERE")
                && sql.contains("period_key IN (?, ?)"))))
                .thenReturn(selectStatement);
        when(selectStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getLong("cost_micros")).thenReturn(1200L, 3400L);
        when(resultSet.getString("period_key")).thenReturn(dailyKey, monthlyKey);
        when(jdbc.execute(any(ConnectionCallback.class))).thenAnswer(invocation -> {
            ConnectionCallback<?> callback = invocation.getArgument(0);
            return callback.doInConnection(connection);
        });

        ClientCostStore.CostCheckResult result = store.checkAndRecordBoth("client-1", 0L, 10000L, 20000L, now);

        assertEquals(1200L, result.dailyMicros());
        assertEquals(3400L, result.monthlyMicros());
        verify(connection).prepareStatement(sqlCaptor.capture());
        assertTrue(sqlCaptor.getValue().contains("SELECT period_key, cost_micros FROM client_cost WHERE namespace = ? AND client_id = ? AND period_key IN (?, ?)"));
        verify(selectStatement).setString(1, "gw");
        verify(selectStatement).setString(2, "client-1");
        verify(selectStatement).setString(3, dailyKey);
        verify(selectStatement).setString(4, monthlyKey);
    }

    @Test
    void checkAndRecordBoth_zeroCostDefaultsMissingPeriodsToZero() throws Exception {
        PostgresClientCostStore store = createStore();
        Connection connection = mock(Connection.class);
        PreparedStatement selectStatement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);

        when(connection.prepareStatement(argThat(sql -> sql != null
                && sql.contains("SELECT period_key, cost_micros FROM client_cost WHERE")
                && sql.contains("period_key IN (?, ?)"))))
                .thenReturn(selectStatement);
        when(selectStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);
        when(jdbc.execute(any(ConnectionCallback.class))).thenAnswer(invocation -> {
            ConnectionCallback<?> callback = invocation.getArgument(0);
            return callback.doInConnection(connection);
        });

        ClientCostStore.CostCheckResult result = store.checkAndRecordBoth("client-1", 0L, 10000L, 20000L, now);

        assertEquals(0L, result.dailyMicros());
        assertEquals(0L, result.monthlyMicros());
        verify(connection, times(1)).prepareStatement(argThat(sql -> sql != null
                && sql.contains("SELECT period_key, cost_micros FROM client_cost WHERE")
                && sql.contains("period_key IN (?, ?)")));
    }

}
