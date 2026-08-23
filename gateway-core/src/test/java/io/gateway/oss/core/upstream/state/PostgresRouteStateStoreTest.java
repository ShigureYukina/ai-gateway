package io.gateway.oss.core.upstream.state;

import io.gateway.oss.core.config.ResilienceConfig;
import io.gateway.oss.core.upstream.PostgresRouteStateStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostgresRouteStateStoreTest {

    @Mock
    JdbcTemplate jdbc;

    @Mock
    ObjectMapper objectMapper;

    private final Instant now = Instant.parse("2026-05-22T10:00:00Z");
    private static final String NAMESPACE = "gateway";

    private ResilienceConfig resilienceConfig() {
        ResilienceConfig config = new ResilienceConfig();
        config.setRetryableFailureThreshold(2);
        config.setFailureWindow(Duration.ofSeconds(30));
        config.setOpenDuration(Duration.ofSeconds(30));
        return config;
    }

    @Test
    void isAvailable_returnsTrueWhenNoFailures() {
        PostgresRouteStateStore store = new PostgresRouteStateStore(jdbc, objectMapper, NAMESPACE);
        when(jdbc.queryForList(argThat(sql -> ((String) sql).contains("SELECT open_until_ms FROM route_state WHERE")), eq(Long.class), anyString(), anyString()))
                .thenReturn(Collections.emptyList());

        assertTrue(store.isAvailable("route-1", now));
    }

    @Test
    void isAvailable_returnsFalseWhenCircuitOpen() {
        PostgresRouteStateStore store = new PostgresRouteStateStore(jdbc, objectMapper, NAMESPACE);
        // open_until_ms in the future
        long futureMs = now.plusSeconds(30).toEpochMilli();
        when(jdbc.queryForList(argThat(sql -> ((String) sql).contains("SELECT open_until_ms FROM route_state WHERE")), eq(Long.class), anyString(), anyString()))
                .thenReturn(List.of(futureMs));

        assertFalse(store.isAvailable("route-1", now));
    }

    @Test
    void recordSuccess_executesUpsert() {
        PostgresRouteStateStore store = new PostgresRouteStateStore(jdbc, objectMapper, NAMESPACE);
        when(jdbc.update(argThat(sql -> ((String) sql).contains("INSERT INTO route_state (namespace, route_id, open_until_ms, failure_ts_json)")), anyString(), anyString())).thenReturn(1);

        store.recordSuccess("route-1");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sqlCaptor.capture(), eq(NAMESPACE), eq("route-1"));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("INSERT INTO route_state (namespace, route_id, open_until_ms, failure_ts_json)"));
        assertTrue(sql.contains("ON CONFLICT (namespace, route_id) DO UPDATE SET open_until_ms = NULL, failure_ts_json = '[]'"));
    }

    @Test
    void recordRetryableFailure_executesUpsert() throws Exception {
        PostgresRouteStateStore store = new PostgresRouteStateStore(jdbc, objectMapper, NAMESPACE);
        when(jdbc.queryForList(argThat(sql -> ((String) sql).contains("SELECT failure_ts_json FROM route_state WHERE")), eq(String.class), anyString(), anyString()))
                .thenReturn(List.of("[]"));
        when(objectMapper.readValue(eq("[]"), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenReturn(new java.util.ArrayList<>());
        when(objectMapper.writeValueAsString(any())).thenReturn("[123456]");
        when(jdbc.update(argThat(sql -> ((String) sql).contains("INSERT INTO route_state (namespace, route_id, open_until_ms, failure_ts_json)")), any(), any(), any(), any(), any(), any())).thenReturn(1);

        store.recordRetryableFailure("route-1", now, resilienceConfig());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sqlCaptor.capture(),
                eq(NAMESPACE), eq("route-1"), any(), eq("[123456]"), any(), eq("[123456]"));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("INSERT INTO route_state (namespace, route_id, open_until_ms, failure_ts_json)"));
        assertTrue(sql.contains("ON CONFLICT (namespace, route_id) DO UPDATE SET"));
    }
}
