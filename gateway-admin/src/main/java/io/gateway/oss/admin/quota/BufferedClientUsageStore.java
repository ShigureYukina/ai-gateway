package io.gateway.oss.admin.quota;

import io.gateway.oss.core.util.RedisStoreUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Buffered wrapper around PostgresClientUsageStore.
 * <p>
 * {@code checkAndRecordBoth()} runs entirely in-memory (seeded from PG on first access
 * per client+period key), returning projected totals immediately without blocking on PG.
 * Accumulated records are flushed to PG as batch UPSERTs on a periodic schedule
 * and when the pending buffer reaches a threshold.
 * </p>
 * <p>
 * Quota enforcement is done against the in-memory running total, which includes both the
 * PG baseline and all buffered (unflushed) records.  The Caffeine cache in
 * {@link ClientQuotaService} is updated after each {@code checkAndRecordBoth()} call,
 * so the pre-route quota check sees the buffered total.
 * </p>
 */
public class BufferedClientUsageStore implements ClientUsageStore {

    private static final Logger log = LoggerFactory.getLogger(BufferedClientUsageStore.class);
    private static final int FLUSH_BATCH_SIZE = 100;
    private static final String UPSERT_DAILY_SQL =
            "INSERT INTO client_usage (namespace, client_id, period_key, tokens, request_cnt) " +
            "VALUES (?, ?, ?, ?, 1) " +
            "ON CONFLICT (namespace, client_id, period_key) DO UPDATE SET " +
            "  tokens = client_usage.tokens + EXCLUDED.tokens, " +
            "  request_cnt = client_usage.request_cnt + 1";
    private static final String UPSERT_MONTHLY_SQL =
            "INSERT INTO client_usage (namespace, client_id, period_key, tokens) VALUES (?, ?, ?, ?) " +
            "ON CONFLICT (namespace, client_id, period_key) DO UPDATE SET " +
            "  tokens = client_usage.tokens + EXCLUDED.tokens";

    private final ClientUsageStore delegate;
    private final JdbcTemplate jdbc;
    private final String namespace;

    /** Running totals (PG baseline + unflushed deltas), keyed by period_key. */
    private final ConcurrentHashMap<String, AtomicLong> dailyUsage = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> monthlyUsage = new ConcurrentHashMap<>();

    /** Pending flush queue. */
    private final ConcurrentLinkedQueue<UsageRecord> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingSize = new AtomicInteger();

    private record UsageRecord(String clientId, long tokens, Instant now) {}

    public BufferedClientUsageStore(JdbcTemplate jdbc, ClientUsageStore delegate, String namespace) {
        this.jdbc = jdbc;
        this.delegate = delegate;
        this.namespace = RedisStoreUtils.safePrefix(namespace);
    }

    @Override
    public long currentDailyUsage(String clientId, Instant now) {
        String dk = RedisStoreUtils.dayKey(clientId, now);
        AtomicLong counter = dailyUsage.get(dk);
        return counter != null ? counter.get() : delegate.currentDailyUsage(clientId, now);
    }

    @Override
    public long currentMonthlyUsage(String clientId, Instant now) {
        String mk = RedisStoreUtils.monthKey(clientId, now);
        AtomicLong counter = monthlyUsage.get(mk);
        return counter != null ? counter.get() : delegate.currentMonthlyUsage(clientId, now);
    }

    @Override
    public void addDailyUsage(String clientId, long tokens, Instant now) {
        delegate.addDailyUsage(clientId, tokens, now);
    }

    @Override
    public void addMonthlyUsage(String clientId, long tokens, Instant now) {
        delegate.addMonthlyUsage(clientId, tokens, now);
    }

    @Override
    public long currentDailyRequestCount(String clientId, Instant now) {
        return delegate.currentDailyRequestCount(clientId, now);
    }

    @Override
    public void addDailyRequestCount(String clientId, Instant now) {
        delegate.addDailyRequestCount(clientId, now);
    }

    @Override
    public long checkAndRecord(String clientId, long tokens, long dailyQuota, Instant now) {
        return delegate.checkAndRecord(clientId, tokens, dailyQuota, now);
    }

    @Override
    public long checkAndRecordMonthly(String clientId, long tokens, long monthlyQuota, Instant now) {
        return delegate.checkAndRecordMonthly(clientId, tokens, monthlyQuota, now);
    }

    @Override
    public UsageCheckResult checkAndRecordBoth(String clientId, long tokens, long dailyQuota, long monthlyQuota, Instant now) {
        if (tokens <= 0) {
            return delegate.checkAndRecordBoth(clientId, 0, dailyQuota, monthlyQuota, now);
        }
        String dk = RedisStoreUtils.dayKey(clientId, now);
        String mk = RedisStoreUtils.monthKey(clientId, now);

        // Seed from PG on first access for this period key.
        AtomicLong daily = dailyUsage.computeIfAbsent(dk, k -> new AtomicLong(delegate.currentDailyUsage(clientId, now)));
        AtomicLong monthly = monthlyUsage.computeIfAbsent(mk, k -> new AtomicLong(delegate.currentMonthlyUsage(clientId, now)));

        // Atomically increment
        long newDaily = daily.addAndGet(tokens);
        long newMonthly = monthly.addAndGet(tokens);

        // Check quota
        if (newDaily > dailyQuota) {
            daily.addAndGet(-tokens);
            monthly.addAndGet(-tokens);
            return new UsageCheckResult(-1L, -1L);
        }
        if (newMonthly > monthlyQuota) {
            daily.addAndGet(-tokens);
            monthly.addAndGet(-tokens);
            return new UsageCheckResult(-1L, -1L);
        }

        // Buffer for async flush
        pending.offer(new UsageRecord(clientId, tokens, now));
        int sz = pendingSize.incrementAndGet();
        if (sz >= FLUSH_BATCH_SIZE) {
            flushPending();
        }

        return new UsageCheckResult(newDaily, newMonthly);
    }

    @Override
    public Map<String, Long> batchDailyUsage(java.util.Collection<String> clientIds, Instant now) {
        Map<String, Long> result = new java.util.HashMap<>(clientIds.size());
        for (String clientId : clientIds) {
            result.put(clientId, currentDailyUsage(clientId, now));
        }
        return result;
    }

    @Override
    public Map<String, Long> batchDailyRequestCount(java.util.Collection<String> clientIds, Instant now) {
        return delegate.batchDailyRequestCount(clientIds, now);
    }

    @Scheduled(fixedDelay = 5000)
    public void scheduledFlush() {
        flushPending();
    }

    void flushPending() {
        BufferedStoreHelper.flushBatch(
            pending, jdbc,
            UPSERT_DAILY_SQL, UPSERT_MONTHLY_SQL,
            rec -> new Object[]{namespace, rec.clientId(), RedisStoreUtils.dayKey(rec.clientId(), rec.now()), rec.tokens(), rec.tokens()},
            rec -> new Object[]{namespace, rec.clientId(), RedisStoreUtils.monthKey(rec.clientId(), rec.now()), rec.tokens()},
            log, "usage"
        );
    }
}
