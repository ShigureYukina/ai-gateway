package io.gateway.oss.core.limit;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.error.GatewayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostgresClientRateLimiterTest {

    @Mock
    JdbcTemplate jdbc;

    private GatewayProperties properties() {
        GatewayProperties props = new GatewayProperties();
        props.getLimit().setRequestsPerWindow(10);
        props.getLimit().setWindow(Duration.ofSeconds(60));
        return props;
    }

    private PostgresClientRateLimiter createStore() {
        return new PostgresClientRateLimiter(jdbc, properties());
    }

    @Test
    void check_underLimit_returnsCount() {
        PostgresClientRateLimiter store = createStore();
        when(jdbc.queryForObject(argThat(sql -> ((String) sql).contains("INSERT INTO client_rate_limit (namespace, client_id, window_key") && ((String) sql).contains("RETURNING cnt")), eq(Integer.class), anyString(), anyString(), anyString()))
                .thenReturn(5);

        assertDoesNotThrow(() -> store.check("client-1"));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sqlCaptor.capture(), eq(Integer.class), eq("gateway"), eq("client-1"), anyString());
        assertTrue(sqlCaptor.getValue().contains("INSERT INTO client_rate_limit (namespace, client_id, window_key"));
        assertTrue(sqlCaptor.getValue().contains("RETURNING cnt"));
    }

    @Test
    void check_atLimit_returnsExceededCount() {
        PostgresClientRateLimiter store = createStore();
        when(jdbc.queryForObject(argThat(sql -> ((String) sql).contains("INSERT INTO client_rate_limit (namespace, client_id, window_key")), eq(Integer.class), anyString(), anyString(), anyString()))
                .thenReturn(11);

        GatewayException ex = assertThrows(GatewayException.class, () -> store.check("client-1"));
        assertEquals(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
        assertEquals("rate_limited", ex.getCode());
    }

    @Test
    void getCurrentStatus_returnsCurrentCount() {
        PostgresClientRateLimiter store = createStore();
        when(jdbc.query(argThat(sql -> ((String) sql).contains("SELECT cnt FROM client_rate_limit WHERE")), any(ResultSetExtractor.class), eq("gateway"), eq("client-1"), anyString()))
                .thenReturn(7);

        RateLimitStatus status = store.getCurrentStatus("client-1");
        assertNotNull(status);
        assertEquals(10, status.limit());
        assertEquals(3, status.remaining());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sqlCaptor.capture(), any(ResultSetExtractor.class), eq("gateway"), eq("client-1"), anyString());
        assertTrue(sqlCaptor.getValue().contains("SELECT cnt FROM client_rate_limit WHERE namespace = ? AND client_id = ?"));
    }
}
