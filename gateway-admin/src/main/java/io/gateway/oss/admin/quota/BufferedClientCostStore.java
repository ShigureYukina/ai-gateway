package io.gateway.oss.admin.quota;

import io.gateway.oss.core.util.RedisStoreUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Buffered wrapper around PostgresClientCostStore.
 * <p>
 * Mirrors the design of {@link BufferedClientUsageStore}: {@code checkAndRecordBoth()}
 * runs in-memory (seeded from PG on first access), buffers records for async batch flush.
 * </p>
 */
public class BufferedClientCostStore implements ClientCostStore {

    private static final Logger log = LoggerFactory.getLogger(BufferedClientCostStore.class);
    private static final int COST_SCALE = 6;
    private static final int FLUSH_BATCH_SIZE = 100;
    private static final String UPSERT_DAILY_SQL =
            "INSERT INTO client_cost (namespace, client_id, period_key, cost_micros) VALUES (?, ?, ?, ?) " +
            "ON CONFLICT (namespace, client_id, period_key) DO UPDATE SET " +
            "  cost_micros = client_cost.cost_micros + EXCLUDED.cost_micros";
    private static final String UPSERT_MONTHLY_SQL =
            "INSERT INTO client_cost (namespace, client_id, period_key, cost_micros) VALUES (?, ?, ?, ?) " +
            "ON CONFLICT (namespace, client_id, period_key) DO UPDATE SET " +
            "  cost_micros = client_cost.cost_micros + EXCLUDED.cost_micros";

    private final ClientCostStore delegate;
    private final JdbcTemplate jdbc;
    private final String namespace;

    /** Running totals in micros (PG baseline + unflushed deltas), keyed by period_key. */
    private final ConcurrentHashMap<String, AtomicLong> dailyCostMicros = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> monthlyCostMicros = new ConcurrentHashMap<>();

    /** Pending flush queue. */
    private final ConcurrentLinkedQueue<CostRecord> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingSize = new AtomicInteger();

    private record CostRecord(String clientId, long costMicros, Instant now) {}

    public BufferedClientCostStore(JdbcTemplate jdbc, ClientCostStore delegate, String namespace) {
        this.jdbc = jdbc;
        this.delegate = delegate;
        this.namespace = RedisStoreUtils.safePrefix(namespace);
    }

    @Override
    public BigDecimal currentDailyCost(String clientId, Instant now) {
        String dk = RedisStoreUtils.dayKey(clientId, now);
        AtomicLong counter = dailyCostMicros.get(dk);
        if (counter != null) {
            return BigDecimal.valueOf(counter.get()).movePointLeft(COST_SCALE);
        }
        return delegate.currentDailyCost(clientId, now);
    }

    @Override
    public BigDecimal currentMonthlyCost(String clientId, Instant now) {
        String mk = RedisStoreUtils.monthBucketKey(clientId, now);
        AtomicLong counter = monthlyCostMicros.get(mk);
        if (counter != null) {
            return BigDecimal.valueOf(counter.get()).movePointLeft(COST_SCALE);
        }
        return delegate.currentMonthlyCost(clientId, now);
    }

    @Override
    public void addDailyCost(String clientId, BigDecimal cost, Instant now) {
        delegate.addDailyCost(clientId, cost, now);
    }

    @Override
    public void addMonthlyCost(String clientId, BigDecimal cost, Instant now) {
        delegate.addMonthlyCost(clientId, cost, now);
    }

    @Override
    public long checkAndRecord(String clientId, long costMicros, long dailyBudgetMicros, Instant now) {
        return delegate.checkAndRecord(clientId, costMicros, dailyBudgetMicros, now);
    }

    @Override
    public long checkAndRecordMonthly(String clientId, long costMicros, long monthlyBudgetMicros, Instant now) {
        return delegate.checkAndRecordMonthly(clientId, costMicros, monthlyBudgetMicros, now);
    }

    @Override
    public CostCheckResult checkAndRecordBoth(String clientId, long costMicros, long dailyBudgetMicros, long monthlyBudgetMicros, Instant now) {
        if (costMicros <= 0) {
            return delegate.checkAndRecordBoth(clientId, 0, dailyBudgetMicros, monthlyBudgetMicros, now);
        }
        String dk = RedisStoreUtils.dayKey(clientId, now);
        String mk = RedisStoreUtils.monthBucketKey(clientId, now);

        // Seed from PG on first access for this period key.
        Long pgDaily = delegate.currentDailyCost(clientId, now)
                .movePointRight(COST_SCALE).longValue();
        AtomicLong daily = dailyCostMicros.computeIfAbsent(dk, k -> new AtomicLong(pgDaily));

        Long pgMonthly = delegate.currentMonthlyCost(clientId, now)
                .movePointRight(COST_SCALE).longValue();
        AtomicLong monthly = monthlyCostMicros.computeIfAbsent(mk, k -> new AtomicLong(pgMonthly));

        long newDaily = daily.addAndGet(costMicros);
        long newMonthly = monthly.addAndGet(costMicros);

        if (newDaily > dailyBudgetMicros) {
            daily.addAndGet(-costMicros);
            monthly.addAndGet(-costMicros);
            return new CostCheckResult(-1L, -1L);
        }
        if (newMonthly > monthlyBudgetMicros) {
            daily.addAndGet(-costMicros);
            monthly.addAndGet(-costMicros);
            return new CostCheckResult(-1L, -1L);
        }

        pending.offer(new CostRecord(clientId, costMicros, now));
        int sz = pendingSize.incrementAndGet();
        if (sz >= FLUSH_BATCH_SIZE) {
            flushPending();
        }

        return new CostCheckResult(newDaily, newMonthly);
    }

    @Override
    public java.util.Map<String, BigDecimal> batchDailyCost(java.util.Collection<String> clientIds, Instant now) {
        java.util.Map<String, BigDecimal> result = new java.util.HashMap<>(clientIds.size());
        for (String clientId : clientIds) {
            result.put(clientId, currentDailyCost(clientId, now));
        }
        return result;
    }

    @Scheduled(fixedDelay = 5000)
    public void scheduledFlush() {
        flushPending();
    }

    void flushPending() {
        int drained = BufferedStoreHelper.flushBatch(
            pending, jdbc,
            UPSERT_DAILY_SQL, UPSERT_MONTHLY_SQL,
            rec -> new Object[]{namespace, rec.clientId(), RedisStoreUtils.dayKey(rec.clientId(), rec.now()), rec.costMicros()},
            rec -> new Object[]{namespace, rec.clientId(), RedisStoreUtils.monthBucketKey(rec.clientId(), rec.now()), rec.costMicros()},
            rec -> rec.clientId() + ":" + RedisStoreUtils.dayKey(rec.clientId(), rec.now()),
            (a, b) -> new CostRecord(a.clientId(), a.costMicros() + b.costMicros(), a.now()),
            log, "cost"
        );
        pendingSize.addAndGet(-drained);
    }
}
