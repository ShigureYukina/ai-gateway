package io.gateway.oss.admin.limit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostgresClientTpmStoreTest {

    @Mock
    JdbcTemplate jdbc;

    private PostgresClientTpmStore createStore() {
        return new PostgresClientTpmStore(jdbc, "gw");
    }

    private final Instant now = Instant.parse("2026-05-22T10:30:00Z");

    @Test
    void reserve_underLimit_returnsNewTotal() {
        // Single SQL with RETURNING — query() returns the new total directly
        PostgresClientTpmStore store = createStore();
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), anyString(), anyString(), anyString(),
                anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong()))
                .thenReturn(50L);

        long result = store.reserve("client-1", 10, 100, now);
        assertEquals(50L, result);

        verify(jdbc).query(
                argThat(sql -> sql.contains("INSERT INTO client_tpm_usage") && sql.contains("RETURNING tokens")),
                any(ResultSetExtractor.class),
                eq("gw"), eq("client-1"), anyString(),
                eq(10L), eq(10L), eq(100L), eq(10L), eq(10L), eq(100L));
        // 审查 P2-3：INSERT 路径带限额守卫（SELECT ... WHERE ? <= ?）
        verify(jdbc).query(
                argThat(sql -> sql.contains("SELECT ?, ?, ?, ? WHERE ? <= ?") && sql.contains("RETURNING tokens")),
                any(ResultSetExtractor.class),
                eq("gw"), eq("client-1"), anyString(),
                eq(10L), eq(10L), eq(100L), eq(10L), eq(10L), eq(100L));
        verify(jdbc, never()).update(anyString(), any(), any(), any());
    }

    @Test
    void reserve_overLimit_returnsNegative() {
        // RETURNING returns null (WHERE clause in DO UPDATE prevents the update)
        PostgresClientTpmStore store = createStore();
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), anyString(), anyString(), anyString(),
                anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong()))
                .thenReturn(null);

        long result = store.reserve("client-1", 10, 100, now);
        assertEquals(-1L, result);
    }

    @Test
    void adjust_upsertsCorrectly() {
        PostgresClientTpmStore store = createStore();
        when(jdbc.update(anyString(), anyString(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(1);

        store.adjust("client-1", 5, now);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sqlCaptor.capture(), eq("gw"), eq("client-1"), anyString(), eq(5L), eq(5L));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("INSERT INTO client_tpm_usage (namespace, client_id, minute_key, tokens"));
        assertTrue(sql.contains("ON CONFLICT"));
        assertTrue(sql.contains("GREATEST(0, client_tpm_usage.tokens + ?)"));
    }

    @Test
    void currentMinuteUsage_returnsTokens() {
        PostgresClientTpmStore store = createStore();
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq("gw"), eq("client-1"), anyString()))
                .thenReturn(42L);

        long result = store.currentMinuteUsage("client-1", now);
        assertEquals(42L, result);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sqlCaptor.capture(), any(ResultSetExtractor.class), eq("gw"), eq("client-1"), anyString());
        assertTrue(sqlCaptor.getValue().contains("SELECT tokens FROM client_tpm_usage WHERE namespace = ? AND client_id = ?"));
    }
}
