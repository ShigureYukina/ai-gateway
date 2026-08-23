package io.gateway.oss.core.upstream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PostgresProviderRuntimeStateStore implements ProviderRuntimeStateStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final String namespace;

    public PostgresProviderRuntimeStateStore(JdbcTemplate jdbc, ObjectMapper objectMapper, String namespace) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.namespace = io.gateway.oss.core.util.RedisStoreUtils.safePrefix(namespace);
    }

    @Override
    public ProviderRuntimeState get(String provider) {
        List<String> rows = jdbc.queryForList(
            "SELECT state_json FROM provider_runtime WHERE namespace = ? AND provider = ?",
            String.class, namespace, provider
        );
        if (rows.isEmpty()) return ProviderRuntimeState.unknown();
        try {
            return objectMapper.readValue(rows.get(0), ProviderRuntimeState.class);
        } catch (JsonProcessingException e) {
            return ProviderRuntimeState.unknown();
        }
    }

    @Override
    public void save(String provider, ProviderRuntimeState state) {
        String json;
        try {
            json = objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            return;
        }
        jdbc.update(
            "INSERT INTO provider_runtime (namespace, provider, state_json) VALUES (?, ?, ?) " +
            "ON CONFLICT (namespace, provider) DO UPDATE SET state_json = EXCLUDED.state_json",
            namespace, provider, json
        );
    }

    @Override
    public Map<String, ProviderRuntimeState> getAll() {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT provider, state_json FROM provider_runtime WHERE namespace = ?",
            namespace
        );
        Map<String, ProviderRuntimeState> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String provider = (String) row.get("provider");
            String json = (String) row.get("state_json");
            try {
                result.put(provider, objectMapper.readValue(json, ProviderRuntimeState.class));
            } catch (JsonProcessingException e) {
                result.put(provider, ProviderRuntimeState.unknown());
            }
        }
        return result;
    }
}
