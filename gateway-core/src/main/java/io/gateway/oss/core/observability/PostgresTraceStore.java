package io.gateway.oss.core.observability;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.util.RedisStoreUtils;
import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

public class PostgresTraceStore implements TraceStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresTraceStore.class);

    private final JdbcTemplate jdbc;
    private final String namespace;
    private final GatewayMetricsRecorder metricsRecorder;

    public PostgresTraceStore(JdbcTemplate jdbc, GatewayProperties properties) {
        this.jdbc = jdbc;
        this.namespace = RedisStoreUtils.safePrefix(properties.getSharedState().getKeyPrefix());
        this.metricsRecorder = new GatewayMetricsRecorder(Metrics.globalRegistry);
    }

    @Override
    public void save(TraceRecord record) {
        long start = System.nanoTime();
        if (isSuccessfulTrace(record)) {
            saveSuccessfulTrace(record);
        } else {
            saveDetailedTrace(record);
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        metricsRecorder.recordWriteLatency("traceStoreSave", ms);
        if (ms > 2) {
            log.info("pg_write_latency point=traceStoreSave requestId={} durationMs={}", record.requestId(), ms);
        }
    }

    private void saveSuccessfulTrace(TraceRecord record) {
        jdbc.update(
            "INSERT INTO request_trace (namespace, request_id, client_id, model, provider, route_id, scene, status, stream_mode, latency_ms, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (namespace, request_id) DO UPDATE SET " +
            "  client_id = EXCLUDED.client_id, " +
            "  model = EXCLUDED.model, " +
            "  provider = EXCLUDED.provider, " +
            "  route_id = EXCLUDED.route_id, " +
            "  scene = EXCLUDED.scene, " +
            "  status = EXCLUDED.status, " +
            "  stream_mode = EXCLUDED.stream_mode, " +
            "  latency_ms = EXCLUDED.latency_ms",
            namespace,
            record.requestId(),
            record.clientId(),
            record.model(),
            record.provider(),
            record.routeId(),
            record.scene(),
            record.status(),
            record.streamMode(),
            record.latencyMs(),
            Timestamp.from(record.timestamp() != null ? record.timestamp() : Instant.now())
        );
    }

    private void saveDetailedTrace(TraceRecord record) {
        jdbc.update(
            "INSERT INTO request_trace (namespace, request_id, client_id, model, provider, route_id, scene, status, stream_mode, latency_ms, error_message, request_body, response_body, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (namespace, request_id) DO UPDATE SET " +
            "  client_id = EXCLUDED.client_id, " +
            "  model = EXCLUDED.model, " +
            "  provider = EXCLUDED.provider, " +
            "  route_id = EXCLUDED.route_id, " +
            "  scene = EXCLUDED.scene, " +
            "  status = EXCLUDED.status, " +
            "  stream_mode = EXCLUDED.stream_mode, " +
            "  latency_ms = EXCLUDED.latency_ms, " +
            "  error_message = EXCLUDED.error_message, " +
            "  request_body = EXCLUDED.request_body, " +
            "  response_body = EXCLUDED.response_body",
            namespace,
            record.requestId(),
            record.clientId(),
            record.model(),
            record.provider(),
            record.routeId(),
            record.scene(),
            record.status(),
            record.streamMode(),
            record.latencyMs(),
            record.errorMessage(),
            record.requestBody(),
            record.responseBody(),
            Timestamp.from(record.timestamp() != null ? record.timestamp() : Instant.now())
        );
    }

    private static boolean isSuccessfulTrace(TraceRecord record) {
        return record.status() != null && record.status() >= 200 && record.status() < 400;
    }

    @Override
    public TraceRecord getByRequestId(String requestId) {
        return jdbc.query(
            "SELECT request_id, client_id, model, provider, route_id, scene, status, stream_mode, latency_ms, error_message, request_body, response_body, created_at FROM request_trace WHERE namespace = ? AND request_id = ?",
            rs -> {
                if (rs.next()) {
                    return mapRow(rs);
                }
                return null;
            },
            namespace, requestId
        );
    }

    @Override
    public List<TraceRecord> getRecent(int limit) {
        return jdbc.query(
            "SELECT request_id, client_id, model, provider, route_id, scene, status, stream_mode, latency_ms, error_message, request_body, response_body, created_at FROM request_trace WHERE namespace = ? ORDER BY created_at DESC LIMIT ?",
            (rs, rowNum) -> mapRow(rs),
            namespace, limit
        );
    }

    private TraceRecord mapRow(ResultSet rs) throws java.sql.SQLException {
        return new TraceRecord(
            rs.getString("request_id"),
            rs.getString("client_id"),
            rs.getString("model"),
            rs.getString("provider"),
            rs.getString("route_id"),
            rs.getString("scene"),
            (Integer) rs.getObject("status"),
            rs.getString("stream_mode"),
            (Long) rs.getObject("latency_ms"),
            rs.getString("error_message"),
            rs.getString("request_body"),
            rs.getString("response_body"),
            rs.getTimestamp("created_at").toInstant()
        );
    }
}
