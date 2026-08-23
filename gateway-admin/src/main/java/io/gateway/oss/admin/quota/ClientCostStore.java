package io.gateway.oss.admin.quota;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public interface ClientCostStore {

    BigDecimal currentDailyCost(String clientId, Instant now);

    BigDecimal currentMonthlyCost(String clientId, Instant now);

    void addDailyCost(String clientId, BigDecimal cost, Instant now);

    void addMonthlyCost(String clientId, BigDecimal cost, Instant now);

    /**
     * Atomic check-and-record: verify daily budget is not exceeded, then record cost.
     * Returns the new total cost (in micro-units) after recording, or -1 if budget would be exceeded.
     */
    long checkAndRecord(String clientId, long costMicros, long dailyBudgetMicros, Instant now);

    long checkAndRecordMonthly(String clientId, long costMicros, long monthlyBudgetMicros, Instant now);

    /**
     * Batch version of {@link #currentDailyCost(String, Instant)}.
     * Default implementation loops per client — override in Postgres store for single-query batch.
     */
    default Map<String, BigDecimal> batchDailyCost(Collection<String> clientIds, Instant now) {
        Map<String, BigDecimal> result = new HashMap<>(clientIds.size());
        for (String clientId : clientIds) {
            result.put(clientId, currentDailyCost(clientId, now));
        }
        return result;
    }

    /**
     * Result of an atomic daily+monthly check-and-record batch operation.
     */
    record CostCheckResult(long dailyMicros, long monthlyMicros) {}

    /**
     * Atomic check-and-record for both daily and monthly in one batch.
     * Default implementation delegates to separate {@link #checkAndRecord} and
     * {@link #checkAndRecordMonthly} calls — override in Postgres store for
     * single SQL round-trip via CTE.
     */
    default CostCheckResult checkAndRecordBoth(String clientId, long costMicros, long dailyBudgetMicros, long monthlyBudgetMicros, Instant now) {
        return new CostCheckResult(
            checkAndRecord(clientId, costMicros, dailyBudgetMicros, now),
            checkAndRecordMonthly(clientId, costMicros, monthlyBudgetMicros, now)
        );
    }
}
