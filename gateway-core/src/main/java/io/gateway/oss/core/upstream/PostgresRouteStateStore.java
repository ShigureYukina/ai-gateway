package io.gateway.oss.core.upstream;

import io.gateway.oss.core.config.ResilienceConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class PostgresRouteStateStore implements RouteStateStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final String namespace;

    public PostgresRouteStateStore(JdbcTemplate jdbc, ObjectMapper objectMapper, String namespace) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.namespace = io.gateway.oss.core.util.RedisStoreUtils.safePrefix(namespace);
    }

    @Override
    public boolean isAvailable(String routeId, Instant now) {
        List<Long> openUntil = jdbc.queryForList(
            "SELECT open_until_ms FROM route_state WHERE namespace = ? AND route_id = ?",
            Long.class, namespace, routeId
        );
        if (openUntil.isEmpty() || openUntil.get(0) == null) return true;
        long openMs = openUntil.get(0);
        if (now.toEpochMilli() < openMs) return false;
        // Open duration expired — clear it
        jdbc.update("UPDATE route_state SET open_until_ms = NULL WHERE namespace = ? AND route_id = ?", namespace, routeId);
        return true;
    }

    @Override
    public void recordSuccess(String routeId) {
        jdbc.update(
            "INSERT INTO route_state (namespace, route_id, open_until_ms, failure_ts_json) VALUES (?, ?, NULL, '[]') " +
            "ON CONFLICT (namespace, route_id) DO UPDATE SET open_until_ms = NULL, failure_ts_json = '[]'",
            namespace, routeId
        );
    }

    @Override
    public void recordRetryableFailure(String routeId, Instant now, ResilienceConfig config) {
        // Load existing failure timestamps
        List<String> rows = jdbc.queryForList(
            "SELECT failure_ts_json FROM route_state WHERE namespace = ? AND route_id = ?",
            String.class, namespace, routeId
        );

        List<Long> failures;
        if (rows.isEmpty()) {
            failures = new ArrayList<>();
        } else {
            try {
                failures = objectMapper.readValue(rows.get(0), new TypeReference<List<Long>>() {});
            } catch (JsonProcessingException e) {
                failures = new ArrayList<>();
            }
        }

        // Prune expired
        long cutoff = now.minus(config.getFailureWindow()).toEpochMilli();
        failures.removeIf(ts -> ts < cutoff);

        // Add current failure
        failures.add(now.toEpochMilli());

        // Check threshold
        Long openUntil = null;
        if (failures.size() >= config.getRetryableFailureThreshold()) {
            openUntil = now.plus(config.getOpenDuration()).toEpochMilli();
        }

        String failuresJson;
        try {
            failuresJson = objectMapper.writeValueAsString(failures);
        } catch (JsonProcessingException e) {
            failuresJson = "[]";
        }

        jdbc.update(
            "INSERT INTO route_state (namespace, route_id, open_until_ms, failure_ts_json) VALUES (?, ?, ?, ?) " +
            "ON CONFLICT (namespace, route_id) DO UPDATE SET open_until_ms = ?, failure_ts_json = ?",
            namespace, routeId, openUntil, failuresJson, openUntil, failuresJson
        );
    }
}
