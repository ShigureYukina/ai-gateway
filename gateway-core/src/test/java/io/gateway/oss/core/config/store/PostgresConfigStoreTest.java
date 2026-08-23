package io.gateway.oss.core.config.store;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.PostgresConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostgresConfigStoreTest {

    @Mock
    JdbcTemplate jdbc;

    private PostgresConfigStore createStore() {
        GatewayProperties props = new GatewayProperties();
        props.getSharedState().setKeyPrefix("gw");
        return new PostgresConfigStore(jdbc, props, Schedulers.immediate());
    }

    @Test
    void save_executesUpsert() {
        PostgresConfigStore store = createStore();
        when(jdbc.update(argThat(sql -> ((String) sql).contains("INSERT INTO config_kv (namespace, config_type")), anyString(), anyString(), anyString(), anyString())).thenReturn(1);

        store.save("providers", "openai", "{\"name\":\"openai\"}").block();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sqlCaptor.capture(), eq("gw"), eq("providers"), eq("openai"), eq("{\"name\":\"openai\"}"));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("INSERT INTO config_kv (namespace, config_type, key, value_json)"));
        assertTrue(sql.contains("ON CONFLICT (namespace, config_type, key) DO UPDATE SET value_json = EXCLUDED.value_json"));
    }

    @Test
    void load_returnsValueWhenFound() {
        PostgresConfigStore store = createStore();
        when(jdbc.queryForList(argThat(sql -> ((String) sql).contains("SELECT value_json FROM config_kv WHERE namespace = ?")), eq(String.class), anyString(), anyString(), anyString()))
                .thenReturn(List.of("{\"name\":\"openai\"}"));

        String result = store.load("providers", "openai").block();
        assertEquals("{\"name\":\"openai\"}", result);
    }

    @Test
    void load_returnsNullWhenNotFound() {
        PostgresConfigStore store = createStore();
        when(jdbc.queryForList(argThat(sql -> ((String) sql).contains("SELECT value_json FROM config_kv WHERE namespace = ?")), eq(String.class), anyString(), anyString(), anyString()))
                .thenReturn(Collections.emptyList());

        String result = store.load("providers", "nonexistent").block();
        assertNull(result);
    }

    @Test
    void loadAll_returnsAllForType() {
        PostgresConfigStore store = createStore();
        when(jdbc.queryForList(argThat(sql -> ((String) sql).contains("SELECT key, value_json FROM config_kv WHERE namespace = ?")), anyString(), anyString()))
                .thenReturn(List.of(
                        Map.of("key", "openai", "value_json", "{\"name\":\"openai\"}"),
                        Map.of("key", "anthropic", "value_json", "{\"name\":\"anthropic\"}")
                ));

        Map<String, String> result = store.loadAll("providers").block();
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("{\"name\":\"openai\"}", result.get("openai"));
        assertEquals("{\"name\":\"anthropic\"}", result.get("anthropic"));
    }

    @Test
    void saveIfAbsentOrReplaceExpired_usesAtomicUpsert() {
        PostgresConfigStore store = createStore();
        when(jdbc.query(argThat(sql -> ((String) sql).contains("ON CONFLICT (namespace, config_type, key) DO UPDATE SET value_json = EXCLUDED.value_json")), any(RowMapper.class), any(), any(), any(), any(), any()))
                .thenReturn(List.of(1));

        Boolean inserted = store.saveIfAbsentOrReplaceExpired("refresh-token-blacklist", "abc123", "{\"expiresAt\":123}", Duration.ofSeconds(30)).block();

        assertEquals(Boolean.TRUE, inserted);
        verify(jdbc).query(argThat(sql -> ((String) sql).contains("WHERE CAST(COALESCE(config_kv.value_json::jsonb->>'expiresAt', '0') AS BIGINT) <= ?")), any(RowMapper.class), eq("gw"), eq("refresh-token-blacklist"), eq("abc123"), eq("{\"expiresAt\":123}"), anyLong());
    }
}
