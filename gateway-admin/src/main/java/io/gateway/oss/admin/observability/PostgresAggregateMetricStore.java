package io.gateway.oss.admin.observability;

import io.gateway.oss.core.observability.GatewayMetricsRecorder;
import io.gateway.oss.core.util.RedisStoreUtils;
import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class PostgresAggregateMetricStore implements AggregateMetricStore {

    private static final int COST_SCALE = 6;
    private static final Logger log = LoggerFactory.getLogger(PostgresAggregateMetricStore.class);
    private final JdbcTemplate jdbc;
    private final String namespace;
    private final GatewayMetricsRecorder metricsRecorder;

    public PostgresAggregateMetricStore(JdbcTemplate jdbc, String namespace) {
        this.jdbc = jdbc;
        this.namespace = RedisStoreUtils.safePrefix(namespace);
        this.metricsRecorder = new GatewayMetricsRecorder(Metrics.globalRegistry);
    }

    @Override
    public void record(String dimensionType,
                       String dimensionKey,
                       String displayName,
                       long requests,
                       long tokens,
                       BigDecimal costUsd,
                       Instant now) {
        recordAll(List.of(new DimensionRecord(dimensionType, dimensionKey, displayName, requests, tokens, costUsd)), now);
    }

    @Override
    public void recordAll(List<DimensionRecord> records, Instant now) {
        if (records.isEmpty()) {
            return;
        }
        long start = System.nanoTime();
        LocalDate day = now.atZone(ZoneOffset.UTC).toLocalDate();
        YearMonth month = YearMonth.from(day);
        String dayBucket = day.toString();
        String monthBucket = month.toString();

        // 所有维度 × day/month 两个 bucket 合并为单次 multi-row INSERT，
        // 减少 PostgreSQL round-trip，业务语义保持不变。
        StringBuilder sql = new StringBuilder(
                "INSERT INTO aggregate_metric (namespace, dimension_type, dimension_key, bucket, requests, tokens, cost_micros, display_name) VALUES ");
        List<Object> params = new ArrayList<>(records.size() * 16);

        for (int i = 0; i < records.size(); i++) {
            DimensionRecord r = records.get(i);
            long costMicros = toCostMicros(r.costUsd());
            if (i > 0) {
                sql.append(", ");
            }
            // day bucket
            sql.append("(?, ?, ?, ?, ?, ?, ?, ?)");
            addRowParams(params, r, dayBucket, costMicros);
            // month bucket
            sql.append(", (?, ?, ?, ?, ?, ?, ?, ?)");
            addRowParams(params, r, monthBucket, costMicros);
        }

        sql.append(" ON CONFLICT (namespace, dimension_type, dimension_key, bucket) DO UPDATE SET")
           .append("  requests = aggregate_metric.requests + EXCLUDED.requests,")
           .append("  tokens = aggregate_metric.tokens + EXCLUDED.tokens,")
           .append("  cost_micros = aggregate_metric.cost_micros + EXCLUDED.cost_micros,")
           .append("  display_name = COALESCE(NULLIF(EXCLUDED.display_name, ''), aggregate_metric.display_name)");

        jdbc.update(sql.toString(), params.toArray());

        long ms = (System.nanoTime() - start) / 1_000_000;
        recordWriteLatencyMetric("aggregateMetricBatch", ms);
        if (ms > 2) {
            log.info("pg_write_latency point=aggregateMetricBatch dimensions={} durationMs={}", records.size(), ms);
        }
    }

    private void recordWriteLatencyMetric(String point, long durationMs) {
        metricsRecorder.recordWriteLatency(point, durationMs);
    }

    private long toCostMicros(BigDecimal costUsd) {
        if (costUsd != null && costUsd.signum() > 0) {
            return costUsd.setScale(COST_SCALE, RoundingMode.HALF_UP)
                    .movePointRight(COST_SCALE).longValueExact();
        }
        return 0L;
    }

    private void addRowParams(List<Object> params, DimensionRecord r, String bucket, long costMicros) {
        params.add(namespace);
        params.add(r.dimensionType());
        params.add(r.dimensionKey());
        params.add(bucket);
        params.add(r.requests());
        params.add(r.tokens());
        params.add(costMicros);
        params.add(r.displayName());
    }

    @Override
    public List<AggregateMetric> getDaily(String dimensionType, LocalDate day) {
        return query(dimensionType, day.toString());
    }

    @Override
    public List<AggregateMetric> getMonthly(String dimensionType, YearMonth month) {
        return query(dimensionType, month.toString());
    }

    private List<AggregateMetric> query(String dimensionType, String bucket) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT dimension_type, dimension_key, display_name, requests, tokens, cost_micros, bucket " +
            "FROM aggregate_metric WHERE namespace = ? AND dimension_type = ? AND bucket = ?",
            namespace, dimensionType, bucket
        );
        List<AggregateMetric> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            long costMicros = ((Number) row.get("cost_micros")).longValue();
            BigDecimal costUsd = BigDecimal.valueOf(costMicros).movePointLeft(COST_SCALE);
            result.add(new AggregateMetric(
                (String) row.get("dimension_type"),
                (String) row.get("dimension_key"),
                (String) row.get("display_name"),
                ((Number) row.get("requests")).longValue(),
                ((Number) row.get("tokens")).longValue(),
                costUsd,
                (String) row.get("bucket")
            ));
        }
        result.sort(Comparator.comparing(AggregateMetric::dimensionKey));
        return result;
    }
}
