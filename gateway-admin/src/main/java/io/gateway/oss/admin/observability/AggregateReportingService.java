package io.gateway.oss.admin.observability;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AggregateReportingService {

    public static final String DIM_PROVIDER = "provider";
    public static final String DIM_USER = "user";
    public static final String DIM_KEY = "key";
    public static final String DIM_CLIENT = "client";
    public static final String DIM_MODEL = "model";
    public static final String DIM_STATUS = "status";

    private final AggregateMetricStore store;
    /** 已处理的请求 ID（带时间戳），用于去重。超过容量或 TTL 时自动裁剪，避免内存泄漏。 */
    private final ConcurrentHashMap<String, Long> processedRequestIds = new ConcurrentHashMap<>();
    /** 写缓冲：累积维度记录后批量刷入，减少 `aggregateMetricBatch` 的 SQL 频率 */
    private final ConcurrentLinkedQueue<AggregateMetricStore.DimensionRecord> pendingRecords = new ConcurrentLinkedQueue<>();
    /** O(1) pending 计数，替代 CLQ.size() 的 O(n) 遍历 */
    private final AtomicInteger pendingCount = new AtomicInteger(0);

    private static final long MAX_ENTRIES = 10_000;
    private static final long PRUNE_AGE_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final int FLUSH_BATCH_SIZE = 100; // flush threshold (6 dimensions per request)

    public AggregateReportingService(AggregateMetricStore store) {
        this.store = store;
    }

    public void recordSuccess(String requestId,
                               String provider,
                               String user,
                               String keyRef,
                               String keyDisplayName,
                               String clientId,
                               String model,
                               long usageTokens,
                               Double costUsd,
                               Instant now) {
        if (!markProcessed(requestId)) {
            return;
        }
        BigDecimal cost = costUsd == null ? BigDecimal.ZERO : BigDecimal.valueOf(costUsd);
        List<AggregateMetricStore.DimensionRecord> records = List.of(
                new AggregateMetricStore.DimensionRecord(DIM_PROVIDER, safe(provider, "unknown"), safe(provider, "unknown"), 1, usageTokens, cost),
                new AggregateMetricStore.DimensionRecord(DIM_USER, safe(user, "unknown"), safe(user, "unknown"), 1, usageTokens, cost),
                new AggregateMetricStore.DimensionRecord(DIM_KEY, safe(keyRef, "unknown"), keyDisplayName, 1, usageTokens, cost),
                new AggregateMetricStore.DimensionRecord(DIM_CLIENT, safe(clientId, "unknown"), safe(clientId, "unknown"), 1, usageTokens, cost),
                new AggregateMetricStore.DimensionRecord(DIM_MODEL, safe(model, "unknown"), safe(model, "unknown"), 1, usageTokens, cost),
                new AggregateMetricStore.DimensionRecord(DIM_STATUS, "2xx", "2xx", 1, usageTokens, cost)
        );
        for (AggregateMetricStore.DimensionRecord r : records) {
            pendingRecords.offer(r);
        }
        // O(1) threshold check — no longer calls CLQ.size() which is O(n)
        if (pendingCount.addAndGet(records.size()) >= FLUSH_BATCH_SIZE) {
            pendingCount.set(0);
            // Best-effort async flush — not called on producer thread to avoid blocking
            flushPending();
        }
    }

    /** 刷出所有待处理记录到 store，可在 shutdown / scheduled 时调用确保不丢数据 */
    public synchronized void flushPending() {
        if (pendingRecords.isEmpty()) return;
        List<AggregateMetricStore.DimensionRecord> batch = new ArrayList<>();
        AggregateMetricStore.DimensionRecord r;
        while ((r = pendingRecords.poll()) != null) {
            batch.add(r);
        }
        if (!batch.isEmpty()) {
            store.recordAll(batch, Instant.now());
        }
        // Note: pendingCount is NOT reset here — producers may have incremented
        // concurrently during drain. Small count drift is acceptable; @Scheduled
        // every 5s provides a safety net. See also pendingCount threshold in recordSuccess().
    }

    /** 每 5 秒定时 flush，兜底未被阈值触发的残留记录 */
    @Scheduled(fixedDelay = 5000)
    public void scheduledFlush() {
        if (!pendingRecords.isEmpty()) {
            flushPending();
        }
    }

    public void recordFailureStatus(String requestId, int status, Instant now) {
        if (!markProcessed(requestId)) {
            return;
        }
        String bucket = statusBucket(status);
        store.record(DIM_STATUS, bucket, bucket, 1, 0L, BigDecimal.ZERO, now);
    }

    public ReportingBucket providers(String period, String date) {
        return bucket(DIM_PROVIDER, period, date);
    }

    public ReportingBucket users(String period, String date) {
        return bucket(DIM_USER, period, date);
    }

    public ReportingBucket keys(String period, String date) {
        return bucket(DIM_KEY, period, date);
    }

    public ReportingBucket clients(String period, String date) {
        return bucket(DIM_CLIENT, period, date);
    }

    public ReportingBucket models(String period, String date) {
        return bucket(DIM_MODEL, period, date);
    }

    public ReportingBucket statuses(String period, String date) {
        return bucket(DIM_STATUS, period, date);
    }

    public void resetForTests() {
        processedRequestIds.clear();
    }

    private ReportingBucket bucket(String dimensionType, String period, String date) {
        String normalizedPeriod = "month".equalsIgnoreCase(period) ? "month" : "day";
        if ("month".equals(normalizedPeriod)) {
            YearMonth month = resolveMonth(date);
            return new ReportingBucket(normalizedPeriod, month.toString(), store.getMonthly(dimensionType, month));
        }
        LocalDate day = resolveDay(date);
        return new ReportingBucket(normalizedPeriod, day.toString(), store.getDaily(dimensionType, day));
    }

    private LocalDate resolveDay(String value) {
        try {
            return value == null || value.isBlank() ? LocalDate.now(ZoneOffset.UTC) : LocalDate.parse(value.trim());
        } catch (DateTimeParseException ignored) {
            return LocalDate.now(ZoneOffset.UTC);
        }
    }

    private YearMonth resolveMonth(String value) {
        try {
            return value == null || value.isBlank() ? YearMonth.now(ZoneOffset.UTC) : YearMonth.parse(value.trim());
        } catch (DateTimeParseException ignored) {
            return YearMonth.now(ZoneOffset.UTC);
        }
    }

    private String safe(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private boolean markProcessed(String requestId) {
        String normalized = safe(requestId, "unknown-request");
        long now = System.currentTimeMillis();
        // 定期裁剪过期条目，避免无界增长
        if (processedRequestIds.size() > MAX_ENTRIES) {
            processedRequestIds.entrySet().removeIf(e -> now - e.getValue() > PRUNE_AGE_MILLIS);
        }
        return processedRequestIds.putIfAbsent(normalized, now) == null;
    }

    private String statusBucket(int status) {
        if (status >= 200 && status < 300) {
            return "2xx";
        }
        if (status >= 400 && status < 500) {
            return "4xx";
        }
        if (status >= 500 && status < 600) {
            return "5xx";
        }
        return "other";
    }

    public record ReportingBucket(String period, String bucket, List<AggregateMetricStore.AggregateMetric> items) {
    }
}
