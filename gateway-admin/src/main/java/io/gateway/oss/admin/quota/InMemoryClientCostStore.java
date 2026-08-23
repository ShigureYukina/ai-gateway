package io.gateway.oss.admin.quota;

import io.gateway.oss.core.util.RedisStoreUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryClientCostStore implements ClientCostStore {

    private static final int COST_SCALE = 6;

    private final Map<String, AtomicLong> dailyCostMicros = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> monthlyCostMicros = new ConcurrentHashMap<>();

    @Override
    public BigDecimal currentDailyCost(String clientId, Instant now) {
        AtomicLong counter = dailyCostMicros.get(RedisStoreUtils.dayKey(clientId, now));
        if (counter == null || counter.get() == 0L) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(counter.get()).movePointLeft(COST_SCALE);
    }

    @Override
    public BigDecimal currentMonthlyCost(String clientId, Instant now) {
        AtomicLong counter = monthlyCostMicros.get(RedisStoreUtils.monthKey(clientId, now));
        if (counter == null || counter.get() == 0L) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(counter.get()).movePointLeft(COST_SCALE);
    }

    @Override
    public void addDailyCost(String clientId, BigDecimal cost, Instant now) {
        if (cost == null || cost.signum() <= 0) {
            return;
        }
        long deltaMicros = cost.setScale(COST_SCALE, RoundingMode.HALF_UP)
                .movePointRight(COST_SCALE).longValueExact();
        String key = RedisStoreUtils.dayKey(clientId, now);
        dailyCostMicros.computeIfAbsent(key, ignored -> new AtomicLong(0)).addAndGet(deltaMicros);
    }

    @Override
    public void addMonthlyCost(String clientId, BigDecimal cost, Instant now) {
        if (cost == null || cost.signum() <= 0) {
            return;
        }
        long deltaMicros = cost.setScale(COST_SCALE, RoundingMode.HALF_UP)
                .movePointRight(COST_SCALE).longValueExact();
        String key = RedisStoreUtils.monthKey(clientId, now);
        monthlyCostMicros.computeIfAbsent(key, ignored -> new AtomicLong(0)).addAndGet(deltaMicros);
    }

    @Override
    public long checkAndRecord(String clientId, long costMicros, long dailyBudgetMicros, Instant now) {
        if (costMicros <= 0) {
            AtomicLong counter = dailyCostMicros.get(RedisStoreUtils.dayKey(clientId, now));
            return counter == null ? 0L : counter.get();
        }
        String key = RedisStoreUtils.dayKey(clientId, now);
        AtomicLong counter = dailyCostMicros.computeIfAbsent(key, ignored -> new AtomicLong(0));
        while (true) {
            long current = counter.get();
            if (current + costMicros > dailyBudgetMicros) {
                return -1L;
            }
            if (counter.compareAndSet(current, current + costMicros)) {
                return current + costMicros;
            }
        }
    }

    @Override
    public long checkAndRecordMonthly(String clientId, long costMicros, long monthlyBudgetMicros, Instant now) {
        if (costMicros <= 0) {
            AtomicLong counter = monthlyCostMicros.get(RedisStoreUtils.monthKey(clientId, now));
            return counter == null ? 0L : counter.get();
        }
        String key = RedisStoreUtils.monthKey(clientId, now);
        AtomicLong counter = monthlyCostMicros.computeIfAbsent(key, ignored -> new AtomicLong(0));
        while (true) {
            long current = counter.get();
            if (current + costMicros > monthlyBudgetMicros) {
                return -1L;
            }
            if (counter.compareAndSet(current, current + costMicros)) {
                return current + costMicros;
            }
        }
    }


    /** Reset all cost counters (test helper). */
    public void resetForTests() {
        dailyCostMicros.clear();
        monthlyCostMicros.clear();
    }
}
