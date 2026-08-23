package io.gateway.oss.core.upstream.state;

import io.gateway.oss.core.upstream.PostgresProviderRuntimeStateStore;
import io.gateway.oss.core.upstream.ProviderRuntimeStateStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostgresProviderRuntimeStateStoreTest {

    @Mock
    JdbcTemplate jdbc;

    @Mock
    ObjectMapper objectMapper;

    private static final String NAMESPACE = "gateway";

    @Test
    void isAvailable_returnsTrueWhenHealthy() throws Exception {
        PostgresProviderRuntimeStateStore store = new PostgresProviderRuntimeStateStore(jdbc, objectMapper, NAMESPACE);
        ProviderRuntimeStateStore.ProviderRuntimeState healthyState =
                new ProviderRuntimeStateStore.ProviderRuntimeState(true, Instant.now(), Instant.now(), 0, 1, null, null, null);

        when(jdbc.queryForList(argThat(sql -> ((String) sql).contains("SELECT state_json FROM provider_runtime WHERE")), eq(String.class), anyString(), anyString()))
                .thenReturn(List.of("{\"runtimeAvailable\":true}"));
        when(objectMapper.readValue(anyString(), eq(ProviderRuntimeStateStore.ProviderRuntimeState.class)))
                .thenReturn(healthyState);

        ProviderRuntimeStateStore.ProviderRuntimeState result = store.get("openai");
        assertTrue(result.runtimeAvailable());
    }

    @Test
    void isAvailable_returnsFalseWhenDisabled() throws Exception {
        PostgresProviderRuntimeStateStore store = new PostgresProviderRuntimeStateStore(jdbc, objectMapper, NAMESPACE);
        ProviderRuntimeStateStore.ProviderRuntimeState disabledState =
                new ProviderRuntimeStateStore.ProviderRuntimeState(false, Instant.now(), null, 3, 0, 503, 1000L, "timeout");

        when(jdbc.queryForList(argThat(sql -> ((String) sql).contains("SELECT state_json FROM provider_runtime WHERE")), eq(String.class), anyString(), anyString()))
                .thenReturn(List.of("{\"runtimeAvailable\":false}"));
        when(objectMapper.readValue(anyString(), eq(ProviderRuntimeStateStore.ProviderRuntimeState.class)))
                .thenReturn(disabledState);

        ProviderRuntimeStateStore.ProviderRuntimeState result = store.get("openai");
        assertFalse(result.runtimeAvailable());
    }

    @Test
    void recordSuccess_executesUpsert() throws Exception {
        PostgresProviderRuntimeStateStore store = new PostgresProviderRuntimeStateStore(jdbc, objectMapper, NAMESPACE);
        ProviderRuntimeStateStore.ProviderRuntimeState state =
                new ProviderRuntimeStateStore.ProviderRuntimeState(true, Instant.now(), Instant.now(), 0, 1, null, null, null);

        when(objectMapper.writeValueAsString(any())).thenReturn("{\"runtimeAvailable\":true}");
        when(jdbc.update(argThat(sql -> ((String) sql).contains("INSERT INTO provider_runtime (namespace, provider, state_json)")), anyString(), anyString(), anyString())).thenReturn(1);

        store.save("openai", state);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sqlCaptor.capture(), eq(NAMESPACE), eq("openai"), eq("{\"runtimeAvailable\":true}"));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("INSERT INTO provider_runtime (namespace, provider, state_json)"));
        assertTrue(sql.contains("ON CONFLICT (namespace, provider) DO UPDATE SET state_json = EXCLUDED.state_json"));
    }

    @Test
    void recordFailure_executesUpsert() throws Exception {
        PostgresProviderRuntimeStateStore store = new PostgresProviderRuntimeStateStore(jdbc, objectMapper, NAMESPACE);
        ProviderRuntimeStateStore.ProviderRuntimeState state =
                new ProviderRuntimeStateStore.ProviderRuntimeState(false, Instant.now(), null, 3, 0, 503, 1000L, "timeout");

        when(objectMapper.writeValueAsString(any())).thenReturn("{\"runtimeAvailable\":false}");
        when(jdbc.update(argThat(sql -> ((String) sql).contains("INSERT INTO provider_runtime (namespace, provider, state_json)")), anyString(), anyString(), anyString())).thenReturn(1);

        store.save("openai", state);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sqlCaptor.capture(), eq(NAMESPACE), eq("openai"), eq("{\"runtimeAvailable\":false}"));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("INSERT INTO provider_runtime (namespace, provider, state_json)"));
        assertTrue(sql.contains("ON CONFLICT (namespace, provider) DO UPDATE SET state_json = EXCLUDED.state_json"));
    }
}
