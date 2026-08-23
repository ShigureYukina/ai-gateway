package io.gateway.oss.core.limit;

import io.gateway.oss.core.config.ClientConfig;
import io.gateway.oss.core.config.ClientLimits;
import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.util.RedisStoreUtils;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

public class PostgresClientRateLimiter implements ClientRateLimiter {

    private final JdbcTemplate jdbc;
    private final GatewayProperties properties;
    private final String namespace;

    public PostgresClientRateLimiter(JdbcTemplate jdbc, GatewayProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.namespace = RedisStoreUtils.safePrefix(properties.getSharedState().getKeyPrefix());
    }

    @Override
    public void check(String clientId) {
        long windowSeconds = resolveWindowSeconds(clientId);
        int maxRequests = resolveMaxRequests(clientId);

        long nowEpoch = System.currentTimeMillis() / 1000 / windowSeconds;
        String windowKey = String.valueOf(nowEpoch);

        // Atomic UPSERT: insert 1 or increment existing, then check limit
        Integer count = jdbc.queryForObject(
            "INSERT INTO client_rate_limit (namespace, client_id, window_key, cnt) VALUES (?, ?, ?, 1) " +
            "ON CONFLICT (namespace, client_id, window_key) DO UPDATE SET cnt = client_rate_limit.cnt + 1 " +
            "RETURNING cnt",
            Integer.class, namespace, clientId, windowKey
        );

        if (count != null && count > maxRequests) {
            throw new GatewayException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Request limit exceeded");
        }
    }

    @Override
    public RateLimitStatus getCurrentStatus(String clientId) {
        long windowSeconds = resolveWindowSeconds(clientId);
        int maxRequests = resolveMaxRequests(clientId);
        long nowEpoch = System.currentTimeMillis() / 1000 / windowSeconds;
        String windowKey = String.valueOf(nowEpoch);

        Integer count = jdbc.query(
            "SELECT cnt FROM client_rate_limit WHERE namespace = ? AND client_id = ? AND window_key = ?",
            rs -> rs.next() ? rs.getInt("cnt") : null,
            namespace, clientId, windowKey
        );

        long resetEpoch = (nowEpoch + 1) * windowSeconds;
        if (count == null) {
            return new RateLimitStatus(maxRequests, maxRequests, resetEpoch);
        }
        return new RateLimitStatus(maxRequests, Math.max(0, maxRequests - count), resetEpoch);
    }

    private long resolveWindowSeconds(String clientId) {
        ClientLimits clientLimits = resolveClientLimits(clientId);
        if (clientLimits != null && clientLimits.getWindow() != null) {
            long w = clientLimits.getWindow().getSeconds();
            if (w > 0) return w;
        }
        long globalWindow = properties.getLimit().getWindow().getSeconds();
        return globalWindow > 0 ? globalWindow : 60;
    }

    private int resolveMaxRequests(String clientId) {
        ClientLimits clientLimits = resolveClientLimits(clientId);
        if (clientLimits != null && clientLimits.getRequestsPerWindow() != null) {
            return clientLimits.getRequestsPerWindow();
        }
        return properties.getLimit().getRequestsPerWindow();
    }

    private ClientLimits resolveClientLimits(String clientId) {
        if (clientId == null) return null;
        var clients = properties.getClients();
        if (clients == null) return null;
        ClientConfig clientConfig = clients.get(clientId);
        if (clientConfig == null) return null;
        return clientConfig.getLimits();
    }

    @Override
    public void reset() {
        jdbc.update("DELETE FROM client_rate_limit WHERE namespace = ?", namespace);
    }
}
