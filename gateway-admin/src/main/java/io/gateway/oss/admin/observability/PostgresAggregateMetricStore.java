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
import java.util.LinkedHashMap;
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
        // 减少 PostgreSQL round-trip。同一 flush 批次内多条请求常共享维度值
        // （如同一 client/model），必须先按冲突键 (type,key,bucket) 聚合：
        // 同一冲突行在单条语句内出现两次会触发 PG 21000
        // "ON CONFLICT DO UPDATE command cannot affect row a second time"，整批失败。
        Map<BucketKey, BucketAccumulator> merged = new LinkedHashMap<>();
        for (DimensionRecord r : records) {
            long costMicros = toCostMicros(r.costUsd());
            mergeRecord(merged, r, dayBucket, costMicros);
            mergeRecord(merged, r, monthBucket, costMicros);
        }

        StringBuilder sql = new StringBuilder(
                "INSERT INTO aggregate_metric (namespace, dimension_type, dimension_key, bucket, requests, tokens, cost_micros, display_name) VALUES ");
        List<Object> params = new ArrayList<>(merged.size() * 8);

        boolean first = true;
        for (Map.Entry<BucketKey, BucketAccumulator> entry : merged.entrySet()) {
            if (!first) {
                sql.append(", ");
            }
            first = false;
            sql.append("(?, ?, ?, ?, ?, ?, ?, ?)");
            addRowParams(params, entry.getKey(), entry.getValue());
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
            log.info("pg_write_latency point=aggregateMetricBatch records={} rows={} durationMs={}",
                    records.size(), merged.size(), ms);
        }
    }

    private void mergeRecord(Map<BucketKey, BucketAccumulator> merged,
                             DimensionRecord r, String bucket, long costMicros) {
        merged.merge(
                new BucketKey(r.dimensionType(), r.dimensionKey(), bucket),
                new BucketAccumulator(r.requests(), r.tokens(), costMicros, r.displayName()),
                this::combine);
    }

    private BucketAccumulator combine(BucketAccumulator a, BucketAccumulator b) {
        String displayName = (a.displayName() != null && !a.displayName().isBlank())
                ? a.displayName() : b.displayName();
        return new BucketAccumulator(
                a.requests() + b.requests(),
                a.tokens() + b.tokens(),
                a.costMicros() + b.costMicros(),
                displayName);
    }

    private void addRowParams(List<Object> params, BucketKey key, BucketAccumulator acc) {
        params.add(namespace);
        params.add(key.dimensionType());
        params.add(key.dimensionKey());
        params.add(key.bucket());
        params.add(acc.requests());
        params.add(acc.tokens());
        params.add(acc.costMicros());
        params.add(acc.displayName());
    }

    private record BucketKey(String dimensionType, String dimensionKey, String bucket) {
    }

    private record BucketAccumulator(long requests, long tokens, long costMicros, String displayName) {
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
