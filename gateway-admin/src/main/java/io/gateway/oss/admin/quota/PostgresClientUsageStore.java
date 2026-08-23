package io.gateway.oss.admin.quota;

import io.gateway.oss.core.observability.GatewayMetricsRecorder;
import io.gateway.oss.core.util.RedisStoreUtils;

import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PostgresClientUsageStore implements ClientUsageStore {

    private static final String UPSERT_USAGE_SQL =
        "INSERT INTO client_usage (namespace, client_id, period_key, tokens) VALUES (?, ?, ?, ?) " +
        "ON CONFLICT (namespace, client_id, period_key) DO UPDATE SET tokens = client_usage.tokens + ?";
    private static final String UPSERT_REQUEST_COUNT_SQL =
        "INSERT INTO client_usage (namespace, client_id, period_key, request_cnt) VALUES (?, ?, ?, 1) " +
        "ON CONFLICT (namespace, client_id, period_key) DO UPDATE SET request_cnt = client_usage.request_cnt + 1";
    private static final String SELECT_TOKENS_SQL =
        "SELECT tokens FROM client_usage WHERE namespace = ? AND client_id = ? AND period_key = ?";
    private static final String SELECT_TOKENS_BY_PERIODS_SQL =
        "SELECT period_key, tokens FROM client_usage WHERE namespace = ? AND client_id = ? AND period_key IN (?, ?)";
    private static final String SELECT_REQUEST_COUNT_SQL =
        "SELECT request_cnt FROM client_usage WHERE namespace = ? AND client_id = ? AND period_key = ?";
    private static final String CHECK_AND_RECORD_DAILY_SQL =
        "INSERT INTO client_usage (namespace, client_id, period_key, tokens, request_cnt) " +
        "SELECT ?, ?, ?, ?, 1 WHERE ? <= ? " +
        "ON CONFLICT (namespace, client_id, period_key) DO UPDATE SET " +
        "  tokens = client_usage.tokens + EXCLUDED.tokens, " +
        "  request_cnt = client_usage.request_cnt + 1 " +
        "WHERE client_usage.tokens + EXCLUDED.tokens <= ? " +
        "RETURNING tokens";
    private static final String CHECK_AND_RECORD_MONTHLY_SQL =
        "INSERT INTO client_usage (namespace, client_id, period_key, tokens) " +
        "SELECT ?, ?, ?, ? WHERE ? <= ? " +
        "ON CONFLICT (namespace, client_id, period_key) DO UPDATE SET " +
        "  tokens = client_usage.tokens + EXCLUDED.tokens " +
        "WHERE client_usage.tokens + EXCLUDED.tokens <= ? " +
        "RETURNING tokens";
    private static final String CHECK_AND_RECORD_BOTH_SQL =
        "WITH daily_ins AS (" +
        "  INSERT INTO client_usage (namespace, client_id, period_key, tokens, request_cnt) " +
        "  SELECT ?, ?, ?, ?, 1 WHERE ? <= ? " +
        "  ON CONFLICT (namespace, client_id, period_key) DO UPDATE SET" +
        "    tokens = client_usage.tokens + EXCLUDED.tokens," +
        "    request_cnt = client_usage.request_cnt + 1" +
        "  WHERE client_usage.tokens + EXCLUDED.tokens <= ?" +
        "  RETURNING tokens" +
        "), monthly_ins AS (" +
        "  INSERT INTO client_usage (namespace, client_id, period_key, tokens) " +
        "  SELECT ?, ?, ?, ? WHERE ? <= ? " +
        "  ON CONFLICT (namespace, client_id, period_key) DO UPDATE SET" +
        "    tokens = client_usage.tokens + EXCLUDED.tokens" +
        "  WHERE client_usage.tokens + EXCLUDED.tokens <= ?" +
        "  RETURNING tokens" +
        ") SELECT 'daily' AS period, tokens FROM daily_ins " +
        "UNION ALL SELECT 'monthly' AS period, tokens FROM monthly_ins";

    private static final Logger log = LoggerFactory.getLogger(PostgresClientUsageStore.class);

    private final JdbcTemplate jdbc;
    private final String namespace;
    private final GatewayMetricsRecorder metricsRecorder;

    public PostgresClientUsageStore(JdbcTemplate jdbc, String namespace) {
        this.jdbc = jdbc;
        this.namespace = RedisStoreUtils.safePrefix(namespace);
        this.metricsRecorder = new GatewayMetricsRecorder(Metrics.globalRegistry);
    }

    @Override
    public long currentDailyUsage(String clientId, Instant now) {
        String key = RedisStoreUtils.dayKey(clientId, now);
        return jdbc.query(
            SELECT_TOKENS_SQL,
            rs -> rs.next() ? rs.getLong("tokens") : 0L,
            namespace, clientId, key
        );
    }

    @Override
    public long currentMonthlyUsage(String clientId, Instant now) {
        String key = RedisStoreUtils.monthKey(clientId, now);
        return jdbc.query(
            SELECT_TOKENS_SQL,
            rs -> rs.next() ? rs.getLong("tokens") : 0L,
            namespace, clientId, key
        );
    }

    @Override
    public void addDailyUsage(String clientId, long tokens, Instant now) {
        if (tokens <= 0) return;
        String key = RedisStoreUtils.dayKey(clientId, now);
        long start = System.nanoTime();
        jdbc.update(UPSERT_USAGE_SQL, namespace, clientId, key, tokens, tokens);
        logWriteLatency("usageAddDaily", clientId, start);
    }

    @Override
    public void addMonthlyUsage(String clientId, long tokens, Instant now) {
        if (tokens <= 0) return;
        String key = RedisStoreUtils.monthKey(clientId, now);
        long start = System.nanoTime();
        jdbc.update(UPSERT_USAGE_SQL, namespace, clientId, key, tokens, tokens);
        logWriteLatency("usageAddMonthly", clientId, start);
    }

    @Override
    public long currentDailyRequestCount(String clientId, Instant now) {
        String key = RedisStoreUtils.dayKey(clientId, now);
        return jdbc.query(
            SELECT_REQUEST_COUNT_SQL,
            rs -> rs.next() ? rs.getLong("request_cnt") : 0L,
            namespace, clientId, key
        );
    }

    @Override
    public void addDailyRequestCount(String clientId, Instant now) {
        String key = RedisStoreUtils.dayKey(clientId, now);
        long start = System.nanoTime();
        jdbc.update(UPSERT_REQUEST_COUNT_SQL, namespace, clientId, key);
        logWriteLatency("usageAddDailyRequestCount", clientId, start);
    }

    @Override
    public long checkAndRecord(String clientId, long tokens, long dailyQuota, Instant now) {
        if (tokens <= 0) {
            addDailyRequestCount(clientId, now);
            return currentDailyUsage(clientId, now);
        }
        String key = RedisStoreUtils.dayKey(clientId, now);

        long start = System.nanoTime();
        Long result = jdbc.query(
            CHECK_AND_RECORD_DAILY_SQL,
            rs -> rs.next() ? rs.getLong("tokens") : null,
            namespace, clientId, key, tokens, tokens, dailyQuota, dailyQuota
        );
        logWriteLatency("usageCheckAndRecordDaily", clientId, start);
        return result != null ? result : -1L;
    }

    @Override
    public long checkAndRecordMonthly(String clientId, long tokens, long monthlyQuota, Instant now) {
        if (tokens <= 0) return currentMonthlyUsage(clientId, now);
        String key = RedisStoreUtils.monthKey(clientId, now);

        long start = System.nanoTime();
        Long result = jdbc.query(
            CHECK_AND_RECORD_MONTHLY_SQL,
            rs -> rs.next() ? rs.getLong("tokens") : null,
            namespace, clientId, key, tokens, tokens, monthlyQuota, monthlyQuota
        );
        logWriteLatency("usageCheckAndRecordMonthly", clientId, start);
        return result != null ? result : -1L;
    }

    @Override
    public UsageCheckResult checkAndRecordBoth(String clientId, long tokens, long dailyQuota, long monthlyQuota, Instant now) {
        String dailyKey = RedisStoreUtils.dayKey(clientId, now);
        String monthlyKey = RedisStoreUtils.monthKey(clientId, now);
        long totalStart = System.nanoTime();
        try {
            return jdbc.execute((Connection connection) -> {
                if (tokens <= 0) {
                    long dailyStart = System.nanoTime();
                    executeDailyRequestCount(connection, clientId, dailyKey);
                    Map<String, Long> usageByPeriod = selectTokensByPeriods(connection, clientId, dailyKey, monthlyKey);
                    Long daily = usageByPeriod.getOrDefault(dailyKey, 0L);
                    logWriteLatency("usageCheckAndRecordDaily", clientId, dailyStart);

                    long monthlyStart = System.nanoTime();
                    Long monthly = usageByPeriod.getOrDefault(monthlyKey, 0L);
                    logWriteLatency("usageCheckAndRecordMonthly", clientId, monthlyStart);
                    return new UsageCheckResult(daily != null ? daily : 0L, monthly != null ? monthly : 0L);
                }

                // 单 SQL CTE 合并 daily+monthly 写入与配额检查，将两次 round-trip 减少为一次
                long combinedStart = System.nanoTime();
                Long daily = null;
                Long monthly = null;
                try (PreparedStatement stmt = connection.prepareStatement(CHECK_AND_RECORD_BOTH_SQL)) {
                    int idx = 1;
                    // daily CTE 入参: namespace, clientId, dailyKey, tokens, tokens, dailyQuota, dailyQuota
                    stmt.setString(idx++, namespace);
                    stmt.setString(idx++, clientId);
                    stmt.setString(idx++, dailyKey);
                    stmt.setLong(idx++, tokens);
                    stmt.setLong(idx++, tokens);
                    stmt.setLong(idx++, dailyQuota);
                    stmt.setLong(idx++, dailyQuota);
                    // monthly CTE 入参: namespace, clientId, monthlyKey, tokens, tokens, monthlyQuota, monthlyQuota
                    stmt.setString(idx++, namespace);
                    stmt.setString(idx++, clientId);
                    stmt.setString(idx++, monthlyKey);
                    stmt.setLong(idx++, tokens);
                    stmt.setLong(idx++, tokens);
                    stmt.setLong(idx++, monthlyQuota);
                    stmt.setLong(idx++, monthlyQuota);

                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            String period = rs.getString("period");
                            long val = rs.getLong("tokens");
                            if (!rs.wasNull()) {
                                if ("daily".equals(period)) {
                                    daily = val;
                                } else if ("monthly".equals(period)) {
                                    monthly = val;
                                }
                            }
                        }
                    }
                }
                long combinedMs = (System.nanoTime() - combinedStart) / 1_000_000;
                recordWriteLatencyMetric("usageCheckAndRecordDaily", combinedMs);
                recordWriteLatencyMetric("usageCheckAndRecordMonthly", combinedMs);
                return new UsageCheckResult(daily != null ? daily : -1L, monthly != null ? monthly : -1L);
            });
        } finally {
            recordWriteLatencyMetric("usageCheckAndRecordBoth", (System.nanoTime() - totalStart) / 1_000_000);
        }
    }

    @Override
    public Map<String, Long> batchDailyUsage(Collection<String> clientIds, Instant now) {
        if (clientIds.isEmpty()) return Map.of();
        List<Object> params = PostgresStoreHelper.batchParams(namespace, clientIds, now);
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT client_id, tokens FROM client_usage WHERE namespace = ? AND period_key IN (" +
            PostgresStoreHelper.placeholders(clientIds.size()) + ")",
            params.toArray()
        );
        return indexLong(clientIds, rows, "client_id", "tokens");
    }

    @Override
    public Map<String, Long> batchDailyRequestCount(Collection<String> clientIds, Instant now) {
        if (clientIds.isEmpty()) return Map.of();
        List<Object> params = PostgresStoreHelper.batchParams(namespace, clientIds, now);
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT client_id, request_cnt FROM client_usage WHERE namespace = ? AND period_key IN (" +
            PostgresStoreHelper.placeholders(clientIds.size()) + ")",
            params.toArray()
        );
        return indexLong(clientIds, rows, "client_id", "request_cnt");
    }

    private Map<String, Long> indexLong(Collection<String> clientIds,
                                         List<Map<String, Object>> rows,
                                         String idCol,
                                         String valCol) {
        Map<String, Long> result = new HashMap<>(clientIds.size());
        for (String clientId : clientIds) {
            result.put(clientId, 0L);
        }
        for (Map<String, Object> row : rows) {
            result.put((String) row.get(idCol), ((Number) row.get(valCol)).longValue());
        }
        return result;
    }

    private void logWriteLatency(String point, String clientId, long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        recordWriteLatencyMetric(point, ms);
        if (ms > 2) {
            log.info("pg_write_latency point={} clientId={} durationMs={}", point, clientId, ms);
        }
    }

    private void recordWriteLatencyMetric(String point, long durationMs) {
        metricsRecorder.recordWriteLatency(point, durationMs);
    }

    private void executeDailyRequestCount(Connection connection, String clientId, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPSERT_REQUEST_COUNT_SQL)) {
            statement.setString(1, namespace);
            statement.setString(2, clientId);
            statement.setString(3, key);
            statement.executeUpdate();
        }
    }

    private Map<String, Long> selectTokensByPeriods(Connection connection,
                                                     String clientId,
                                                     String dailyKey,
                                                     String monthlyKey) throws SQLException {
        Map<String, Long> tokensByPeriod = new HashMap<>(2);
        try (PreparedStatement statement = connection.prepareStatement(SELECT_TOKENS_BY_PERIODS_SQL)) {
            statement.setString(1, namespace);
            statement.setString(2, clientId);
            statement.setString(3, dailyKey);
            statement.setString(4, monthlyKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    tokensByPeriod.put(resultSet.getString("period_key"), resultSet.getLong("tokens"));
                }
            }
        }
        return tokensByPeriod;
    }

}
