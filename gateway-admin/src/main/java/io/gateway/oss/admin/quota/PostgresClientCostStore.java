package io.gateway.oss.admin.quota;

import io.gateway.oss.core.observability.GatewayMetricsRecorder;
import io.gateway.oss.core.util.RedisStoreUtils;

import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PostgresClientCostStore implements ClientCostStore {

    private static final int COST_SCALE = 6;
    private static final String SELECT_COST_SQL =
        "SELECT cost_micros FROM client_cost WHERE namespace = ? AND client_id = ? AND period_key = ?";
    private static final String SELECT_COSTS_SQL =
        "SELECT period_key, cost_micros FROM client_cost WHERE namespace = ? AND client_id = ? AND period_key IN (?, ?)";
    private static final String UPSERT_COST_SQL =
        "INSERT INTO client_cost (namespace, client_id, period_key, cost_micros) VALUES (?, ?, ?, ?) " +
        "ON CONFLICT (namespace, client_id, period_key) DO UPDATE SET cost_micros = client_cost.cost_micros + ?";
    private static final String CHECK_AND_RECORD_COST_SQL =
        "INSERT INTO client_cost (namespace, client_id, period_key, cost_micros) " +
        "SELECT ?, ?, ?, ? WHERE ? <= ? " +
        "ON CONFLICT (namespace, client_id, period_key) DO UPDATE SET " +
        "  cost_micros = client_cost.cost_micros + EXCLUDED.cost_micros " +
        "WHERE client_cost.cost_micros + EXCLUDED.cost_micros <= ? " +
        "RETURNING cost_micros";
    private static final String CHECK_AND_RECORD_BOTH_COST_SQL =
        "WITH daily_ins AS (" +
        "  INSERT INTO client_cost (namespace, client_id, period_key, cost_micros) " +
        "  SELECT ?, ?, ?, ? WHERE ? <= ? " +
        "  ON CONFLICT (namespace, client_id, period_key) DO UPDATE SET" +
        "    cost_micros = client_cost.cost_micros + EXCLUDED.cost_micros" +
        "  WHERE client_cost.cost_micros + EXCLUDED.cost_micros <= ?" +
        "  RETURNING cost_micros" +
        "), monthly_ins AS (" +
        "  INSERT INTO client_cost (namespace, client_id, period_key, cost_micros) " +
        "  SELECT ?, ?, ?, ? WHERE ? <= ? " +
        "  ON CONFLICT (namespace, client_id, period_key) DO UPDATE SET" +
        "    cost_micros = client_cost.cost_micros + EXCLUDED.cost_micros" +
        "  WHERE client_cost.cost_micros + EXCLUDED.cost_micros <= ?" +
        "  RETURNING cost_micros" +
        ") SELECT 'daily' AS period, cost_micros FROM daily_ins " +
        "UNION ALL SELECT 'monthly' AS period, cost_micros FROM monthly_ins";
    private static final Logger log = LoggerFactory.getLogger(PostgresClientCostStore.class);
    private final JdbcTemplate jdbc;
    private final String namespace;
    private final GatewayMetricsRecorder metricsRecorder;

    public PostgresClientCostStore(JdbcTemplate jdbc, String namespace) {
        this.jdbc = jdbc;
        this.namespace = RedisStoreUtils.safePrefix(namespace);
        this.metricsRecorder = new GatewayMetricsRecorder(Metrics.globalRegistry);
    }

    @Override
    public BigDecimal currentDailyCost(String clientId, Instant now) {
        String key = RedisStoreUtils.dayKey(clientId, now);
        Long micros = jdbc.query(
            SELECT_COST_SQL,
            rs -> rs.next() ? rs.getLong("cost_micros") : 0L,
            namespace, clientId, key
        );
        if (micros == null || micros == 0L) return BigDecimal.ZERO;
        return BigDecimal.valueOf(micros).movePointLeft(COST_SCALE);
    }

    @Override
    public BigDecimal currentMonthlyCost(String clientId, Instant now) {
        String key = RedisStoreUtils.monthBucketKey(clientId, now);
        Long micros = jdbc.query(
            SELECT_COST_SQL,
            rs -> rs.next() ? rs.getLong("cost_micros") : 0L,
            namespace, clientId, key
        );
        if (micros == null || micros == 0L) return BigDecimal.ZERO;
        return BigDecimal.valueOf(micros).movePointLeft(COST_SCALE);
    }

    @Override
    public void addDailyCost(String clientId, BigDecimal cost, Instant now) {
        if (cost == null || cost.signum() <= 0) return;
        long deltaMicros = cost.setScale(COST_SCALE, RoundingMode.HALF_UP)
                .movePointRight(COST_SCALE).longValueExact();
        String key = RedisStoreUtils.dayKey(clientId, now);
        long start = System.nanoTime();
        jdbc.update(UPSERT_COST_SQL, namespace, clientId, key, deltaMicros, deltaMicros);
        logWriteLatency("costAddDaily", clientId, start);
    }

    @Override
    public void addMonthlyCost(String clientId, BigDecimal cost, Instant now) {
        if (cost == null || cost.signum() <= 0) return;
        long deltaMicros = cost.setScale(COST_SCALE, RoundingMode.HALF_UP)
                .movePointRight(COST_SCALE).longValueExact();
        String key = RedisStoreUtils.monthBucketKey(clientId, now);
        long start = System.nanoTime();
        jdbc.update(UPSERT_COST_SQL, namespace, clientId, key, deltaMicros, deltaMicros);
        logWriteLatency("costAddMonthly", clientId, start);
    }

    @Override
    public long checkAndRecord(String clientId, long costMicros, long dailyBudgetMicros, Instant now) {
        if (costMicros <= 0) {
            String key = RedisStoreUtils.dayKey(clientId, now);
            Long micros = jdbc.query(
                SELECT_COST_SQL,
                rs -> rs.next() ? rs.getLong("cost_micros") : 0L,
                namespace, clientId, key
            );
            return micros == null ? 0L : micros;
        }
        String key = RedisStoreUtils.dayKey(clientId, now);

        long start = System.nanoTime();
        Long result = jdbc.query(
            CHECK_AND_RECORD_COST_SQL,
            rs -> rs.next() ? rs.getLong("cost_micros") : null,
            namespace, clientId, key, costMicros, costMicros, dailyBudgetMicros, dailyBudgetMicros
        );
        logWriteLatency("costCheckAndRecordDaily", clientId, start, true);
        return result != null ? result : -1L;
    }

    @Override
    public long checkAndRecordMonthly(String clientId, long costMicros, long monthlyBudgetMicros, Instant now) {
        if (costMicros <= 0) {
            String key = RedisStoreUtils.monthBucketKey(clientId, now);
            Long micros = jdbc.query(
                SELECT_COST_SQL,
                rs -> rs.next() ? rs.getLong("cost_micros") : 0L,
                namespace, clientId, key
            );
            return micros == null ? 0L : micros;
        }
        String key = RedisStoreUtils.monthBucketKey(clientId, now);

        long start = System.nanoTime();
        Long result = jdbc.query(
            CHECK_AND_RECORD_COST_SQL,
            rs -> rs.next() ? rs.getLong("cost_micros") : null,
            namespace, clientId, key, costMicros, costMicros, monthlyBudgetMicros, monthlyBudgetMicros
        );
        logWriteLatency("costCheckAndRecordMonthly", clientId, start, true);
        return result != null ? result : -1L;
    }

    @Override
    public CostCheckResult checkAndRecordBoth(String clientId,
                                              long costMicros,
                                              long dailyBudgetMicros,
                                              long monthlyBudgetMicros,
                                              Instant now) {
        String dailyKey = RedisStoreUtils.dayKey(clientId, now);
        String monthlyKey = RedisStoreUtils.monthBucketKey(clientId, now);
        long totalStart = System.nanoTime();
        try {
            return jdbc.execute((Connection connection) -> {
                if (costMicros <= 0) {
                    Map<String, Long> costs = selectCosts(connection, clientId, dailyKey, monthlyKey);
                    return new CostCheckResult(costs.getOrDefault(dailyKey, 0L), costs.getOrDefault(monthlyKey, 0L));
                }

                // 单 SQL CTE 合并 daily+monthly 成本写入与预算检查，减少一次 round-trip
                long combinedStart = System.nanoTime();
                Long daily = null;
                Long monthly = null;
                try (PreparedStatement stmt = connection.prepareStatement(CHECK_AND_RECORD_BOTH_COST_SQL)) {
                    int idx = 1;
                    // daily CTE: namespace, clientId, dailyKey, costMicros, costMicros, dailyBudgetMicros, dailyBudgetMicros
                    stmt.setString(idx++, namespace);
                    stmt.setString(idx++, clientId);
                    stmt.setString(idx++, dailyKey);
                    stmt.setLong(idx++, costMicros);
                    stmt.setLong(idx++, costMicros);
                    stmt.setLong(idx++, dailyBudgetMicros);
                    stmt.setLong(idx++, dailyBudgetMicros);
                    // monthly CTE: namespace, clientId, monthlyKey, costMicros, costMicros, monthlyBudgetMicros, monthlyBudgetMicros
                    stmt.setString(idx++, namespace);
                    stmt.setString(idx++, clientId);
                    stmt.setString(idx++, monthlyKey);
                    stmt.setLong(idx++, costMicros);
                    stmt.setLong(idx++, costMicros);
                    stmt.setLong(idx++, monthlyBudgetMicros);
                    stmt.setLong(idx++, monthlyBudgetMicros);

                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            String period = rs.getString("period");
                            long val = rs.getLong("cost_micros");
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
                recordWriteLatencyMetric("costCheckAndRecordDaily", combinedMs);
                recordWriteLatencyMetric("costCheckAndRecordMonthly", combinedMs);
                return new CostCheckResult(daily != null ? daily : -1L, monthly != null ? monthly : -1L);
            });
        } finally {
            recordWriteLatencyMetric("costCheckAndRecordBoth", (System.nanoTime() - totalStart) / 1_000_000);
        }
    }

    @Override
    public Map<String, BigDecimal> batchDailyCost(Collection<String> clientIds, Instant now) {
        if (clientIds.isEmpty()) return Map.of();
        List<Object> params = PostgresStoreHelper.batchParams(namespace, clientIds, now);
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT client_id, cost_micros FROM client_cost WHERE namespace = ? AND period_key IN (" +
            PostgresStoreHelper.placeholders(clientIds.size()) + ")",
            params.toArray()
        );
        Map<String, BigDecimal> result = new HashMap<>(clientIds.size());
        for (String clientId : clientIds) {
            result.put(clientId, BigDecimal.ZERO);
        }
        for (Map<String, Object> row : rows) {
            long micros = ((Number) row.get("cost_micros")).longValue();
            result.put((String) row.get("client_id"), BigDecimal.valueOf(micros).movePointLeft(COST_SCALE));
        }
        return result;
    }

    private void logWriteLatency(String point, String clientId, long startNanos) {
        logWriteLatency(point, clientId, startNanos, false);
    }

    private void logWriteLatency(String point, String clientId, long startNanos, boolean alwaysLog) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        recordWriteLatencyMetric(point, ms);
        if (alwaysLog || ms > 2) {
            log.info("pg_write_latency point={} clientId={} durationMs={}", point, clientId, ms);
        }
    }

    private void recordWriteLatencyMetric(String point, long durationMs) {
        metricsRecorder.recordWriteLatency(point, durationMs);
    }

    private Map<String, Long> selectCosts(Connection connection,
                                          String clientId,
                                          String dailyKey,
                                          String monthlyKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_COSTS_SQL)) {
            statement.setString(1, namespace);
            statement.setString(2, clientId);
            statement.setString(3, dailyKey);
            statement.setString(4, monthlyKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                Map<String, Long> costs = new HashMap<>(2);
                while (resultSet.next()) {
                    costs.put(resultSet.getString("period_key"), resultSet.getLong("cost_micros"));
                }
                return costs;
            }
        }
    }

}
