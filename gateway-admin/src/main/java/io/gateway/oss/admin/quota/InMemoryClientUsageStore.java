package io.gateway.oss.admin.quota;

import io.gateway.oss.core.util.RedisStoreUtils;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryClientUsageStore implements ClientUsageStore {

    private final Map<String, AtomicLong> dailyUsage = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> monthlyUsage = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> dailyRequestCounts = new ConcurrentHashMap<>();

    @Override
    public long currentDailyUsage(String clientId, Instant now) {
        return dayCounter(clientId, now).get();
    }

    @Override
    public long currentMonthlyUsage(String clientId, Instant now) {
        return monthCounter(clientId, now).get();
    }

    @Override
    public void addDailyUsage(String clientId, long tokens, Instant now) {
        if (tokens <= 0) {
            return;
        }
        dayCounter(clientId, now).addAndGet(tokens);
    }

    @Override
    public void addMonthlyUsage(String clientId, long tokens, Instant now) {
        if (tokens <= 0) {
            return;
        }
        monthCounter(clientId, now).addAndGet(tokens);
    }

    @Override
    public long currentDailyRequestCount(String clientId, Instant now) {
        String key = RedisStoreUtils.dayKey(clientId, now);
        AtomicLong count = dailyRequestCounts.get(key);
        return count == null ? 0L : count.get();
    }

    @Override
    public void addDailyRequestCount(String clientId, Instant now) {
        String key = RedisStoreUtils.dayKey(clientId, now);
        dailyRequestCounts.computeIfAbsent(key, ignored -> new AtomicLong(0)).incrementAndGet();
    }

    @Override
    public long checkAndRecord(String clientId, long tokens, long dailyQuota, Instant now) {
        if (tokens <= 0) {
            addDailyRequestCount(clientId, now);
            return currentDailyUsage(clientId, now);
        }
        AtomicLong usageCounter = dayCounter(clientId, now);
        while (true) {
            long current = usageCounter.get();
            if (current + tokens > dailyQuota) {
                return -1L;
            }
            if (usageCounter.compareAndSet(current, current + tokens)) {
                addDailyRequestCount(clientId, now);
                return current + tokens;
            }
        }
    }

    @Override
    public long checkAndRecordMonthly(String clientId, long tokens, long monthlyQuota, Instant now) {
        if (tokens <= 0) {
            return currentMonthlyUsage(clientId, now);
        }
        AtomicLong usageCounter = monthCounter(clientId, now);
        while (true) {
            long current = usageCounter.get();
            if (current + tokens > monthlyQuota) {
                return -1L;
            }
            if (usageCounter.compareAndSet(current, current + tokens)) {
                return current + tokens;
            }
        }
    }

    private AtomicLong dayCounter(String clientId, Instant now) {
        String key = RedisStoreUtils.dayKey(clientId, now);
        return dailyUsage.computeIfAbsent(key, ignored -> new AtomicLong(0));
    }

    private AtomicLong monthCounter(String clientId, Instant now) {
        String key = RedisStoreUtils.monthKey(clientId, now);
        return monthlyUsage.computeIfAbsent(key, ignored -> new AtomicLong(0));
    }


    /** Reset all usage counters (test helper). */
    public void resetForTests() {
        dailyUsage.clear();
        monthlyUsage.clear();
        dailyRequestCounts.clear();
    }
}
