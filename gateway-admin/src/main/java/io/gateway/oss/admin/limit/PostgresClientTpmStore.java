package io.gateway.oss.admin.limit;

import io.gateway.oss.core.util.RedisStoreUtils;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PostgresClientTpmStore implements ClientTpmStore {

    private final JdbcTemplate jdbc;
    private final String namespace;

    public PostgresClientTpmStore(JdbcTemplate jdbc, String namespace) {
        this.jdbc = jdbc;
        this.namespace = RedisStoreUtils.safePrefix(namespace);
    }

    @Override
    public long currentMinuteUsage(String clientId, Instant now) {
        String minuteKey = minuteKey(clientId, now);
        Long tokens = jdbc.query(
            "SELECT tokens FROM client_tpm_usage WHERE namespace = ? AND client_id = ? AND minute_key = ?",
            rs -> rs.next() ? rs.getLong("tokens") : 0L,
            namespace, clientId, minuteKey
        );
        return tokens == null ? 0L : tokens;
    }

    @Override
    public long reserve(String clientId, long tokens, long tpmLimit, Instant now) {
        if (tokens <= 0) {
            return currentMinuteUsage(clientId, now);
        }
        String minuteKey = minuteKey(clientId, now);

        // Single SQL with RETURNING — INSERT or conditional UPDATE in one round-trip
        Long result = jdbc.query(
            "INSERT INTO client_tpm_usage (namespace, client_id, minute_key, tokens) VALUES (?, ?, ?, ?) " +
            "ON CONFLICT (namespace, client_id, minute_key) DO UPDATE SET tokens = client_tpm_usage.tokens + ? " +
            "WHERE client_tpm_usage.tokens + ? <= ? " +
            "RETURNING tokens",
            rs -> rs.next() ? rs.getLong("tokens") : null,
            namespace, clientId, minuteKey, tokens, tokens, tokens, tpmLimit
        );
        // No rows returned when the UPSERT conflicts but the WHERE prevents the update
        return result != null ? result : -1L;
    }

    @Override
    public void adjust(String clientId, long deltaTokens, Instant now) {
        if (deltaTokens == 0) return;
        String minuteKey = minuteKey(clientId, now);
        jdbc.update(
            "INSERT INTO client_tpm_usage (namespace, client_id, minute_key, tokens) VALUES (?, ?, ?, ?) " +
            "ON CONFLICT (namespace, client_id, minute_key) DO UPDATE SET tokens = GREATEST(0, client_tpm_usage.tokens + ?)",
            namespace, clientId, minuteKey, deltaTokens, deltaTokens
        );
    }

    @Override
    public Map<String, Long> batchCurrentMinuteUsage(Collection<String> clientIds, Instant now) {
        if (clientIds.isEmpty()) return Map.of();
        String minuteStr = LocalDateTime.ofInstant(now, ZoneOffset.UTC).withSecond(0).withNano(0)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
        List<Object> params = new ArrayList<>(clientIds.size() + 1);
        params.add(namespace);
        for (String clientId : clientIds) {
            params.add(clientId + ":" + minuteStr);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT client_id, tokens FROM client_tpm_usage WHERE namespace = ? AND minute_key IN (" +
            placeholders(clientIds.size()) + ")",
            params.toArray()
        );
        Map<String, Long> result = new HashMap<>(clientIds.size());
        for (String clientId : clientIds) {
            result.put(clientId, 0L);
        }
        for (Map<String, Object> row : rows) {
            result.put((String) row.get("client_id"), ((Number) row.get("tokens")).longValue());
        }
        return result;
    }

    private static String placeholders(int count) {
        StringBuilder sb = new StringBuilder(count * 2 - 1);
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        return sb.toString();
    }

    private String minuteKey(String clientId, Instant now) {
        LocalDateTime minute = LocalDateTime.ofInstant(now, ZoneOffset.UTC).withSecond(0).withNano(0);
        return clientId + ":" + minute.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
    }
}
