package io.gateway.oss.admin.quota;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public interface ClientUsageStore {

    long currentDailyUsage(String clientId, Instant now);

    long currentMonthlyUsage(String clientId, Instant now);

    void addDailyUsage(String clientId, long tokens, Instant now);

    void addMonthlyUsage(String clientId, long tokens, Instant now);

    long currentDailyRequestCount(String clientId, Instant now);

    void addDailyRequestCount(String clientId, Instant now);

    /**
     * Atomic check-and-record: verify daily token quota is not exceeded, then record usage.
     * Returns the new total token usage after recording, or -1 if quota would be exceeded.
     */
    long checkAndRecord(String clientId, long tokens, long dailyQuota, Instant now);

    long checkAndRecordMonthly(String clientId, long tokens, long monthlyQuota, Instant now);

    /**
     * Batch version of {@link #currentDailyUsage(Collection, Instant)}.
     * Default implementation loops per client — override in Postgres store for single-query batch.
     */
    default Map<String, Long> batchDailyUsage(Collection<String> clientIds, Instant now) {
        Map<String, Long> result = new HashMap<>(clientIds.size());
        for (String clientId : clientIds) {
            result.put(clientId, currentDailyUsage(clientId, now));
        }
        return result;
    }

    /**
     * Batch version of {@link #currentDailyRequestCount(String, Instant)}.
     * Default implementation loops per client — override in Postgres store for single-query batch.
     */
    default Map<String, Long> batchDailyRequestCount(Collection<String> clientIds, Instant now) {
        Map<String, Long> result = new HashMap<>(clientIds.size());
        for (String clientId : clientIds) {
            result.put(clientId, currentDailyRequestCount(clientId, now));
        }
        return result;
    }

    /**
     * Result of an atomic daily+monthly check-and-record batch operation.
     */
    record UsageCheckResult(long daily, long monthly) {}

    /**
     * Atomic check-and-record for both daily and monthly in one batch.
     * Default implementation delegates to separate {@link #checkAndRecord} and
     * {@link #checkAndRecordMonthly} calls. Postgres may override this to
     * reduce application-layer round-trips while still keeping day/month as two
     * independent SQL statements.
     */
    default UsageCheckResult checkAndRecordBoth(String clientId, long tokens, long dailyQuota, long monthlyQuota, Instant now) {
        return new UsageCheckResult(
            checkAndRecord(clientId, tokens, dailyQuota, now),
            checkAndRecordMonthly(clientId, tokens, monthlyQuota, now)
        );
    }
}
