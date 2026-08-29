package io.gateway.oss.admin.quota;

import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.dto.ChatCompletionsRequest;
import io.gateway.oss.core.contract.routing.ResolvedRoute;
import io.gateway.oss.core.contract.security.ClientPrincipal;
import io.gateway.oss.core.contract.security.UserAccount;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Service
public class ClientBudgetService implements io.gateway.oss.core.contract.BudgetService {

    private static final Logger log = LoggerFactory.getLogger(ClientBudgetService.class);
    private static final int COST_SCALE = 6;

    private final ClientCostStore costStore;
    private final CostCalculator costCalculator;

    private final Cache<String, BigDecimal> dailyCostCache = Caffeine.newBuilder()
            .expireAfterWrite(2, TimeUnit.SECONDS)
            .maximumSize(2000)
            .build();

    private final Cache<String, BigDecimal> monthlyCostCache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.SECONDS)
            .maximumSize(2000)
            .build();

    public ClientBudgetService(ClientCostStore costStore, CostCalculator costCalculator) {
        this.costStore = costStore;
        this.costCalculator = costCalculator;
    }

    private String dailyKey(String clientId, Instant now) {
        return clientId + ":daily:" + costPeriodKey(now);
    }

    private String monthlyKey(String clientId, Instant now) {
        return clientId + ":monthly:" + costMonthlyPeriodKey(now);
    }

    public void checkDailyBudget(ClientPrincipal principal, Instant now) {
        BigDecimal dailyCostBudget = getEffectiveDailyCost(principal);
        if (dailyCostBudget == null) {
            return;
        }
        String key = dailyKey(principal.clientId(), now);
        BigDecimal used = dailyCostCache.get(key, k -> costStore.currentDailyCost(principal.clientId(), now));
        if (used.compareTo(dailyCostBudget) >= 0) {
            throw new GatewayException(HttpStatus.TOO_MANY_REQUESTS, "budget_exceeded", "Daily cost budget exceeded");
        }
    }

    public void checkMonthlyBudget(ClientPrincipal principal, Instant now) {
        BigDecimal monthlyCostBudget = getEffectiveMonthlyCost(principal);
        if (monthlyCostBudget == null) {
            return;
        }
        String key = monthlyKey(principal.clientId(), now);
        BigDecimal used = monthlyCostCache.get(key, k -> costStore.currentMonthlyCost(principal.clientId(), now));
        if (used.compareTo(monthlyCostBudget) >= 0) {
            throw new GatewayException(HttpStatus.TOO_MANY_REQUESTS, "monthly_budget_exceeded", "Monthly cost budget exceeded");
        }
    }

    /**
     * Record cost on success using split prompt/completion tokens.
     */
    public void recordCostOnSuccess(ClientPrincipal principal,
                                     ChatCompletionsRequest effectiveRequest,
                                     ResolvedRoute route,
                                     long promptTokens,
                                     long completionTokens,
                                     Instant now) {
        BigDecimal cost = costCalculator.calculate(effectiveRequest, route, promptTokens, completionTokens);
        recordCost(principal, cost, now);
    }

    /**
     * Backward-compatible overload: treats all tokens as prompt tokens.
     */
    public void recordCostOnSuccess(ClientPrincipal principal,
                                     ChatCompletionsRequest effectiveRequest,
                                     ResolvedRoute route,
                                     long usageTokens,
                                     Instant now) {
        BigDecimal cost = costCalculator.calculate(effectiveRequest, route, usageTokens);
        recordCost(principal, cost, now);
    }

    private void recordCost(ClientPrincipal principal, BigDecimal cost, Instant now) {
        long dailyBudgetMicros = resolveDailyBudgetMicros(principal);
        long monthlyBudgetMicros = resolveMonthlyBudgetMicros(principal);
        long costMicros = cost.setScale(COST_SCALE, RoundingMode.HALF_UP)
                .movePointRight(COST_SCALE).longValueExact();
        if (costMicros <= 0) {
            return;
        }
        // 无 daily 预算时 dailyBudgetMicros 为 Long.MAX_VALUE，必须仍走 checkAndRecordBoth：
        // 该方法是 monthly 成本唯一的入账路径，提前返回会导致只配月度预算的客户端
        // 月度成本恒为 0、月度预算永不拦截。
        ClientCostStore.CostCheckResult result = costStore.checkAndRecordBoth(
                principal.clientId(), costMicros, dailyBudgetMicros, monthlyBudgetMicros, now);
        if (result.dailyMicros() < 0) {
            log.warn("cost_budget_exceeded_during_record clientId={}", principal.clientId());
        }
        if (result.monthlyMicros() < 0) {
            log.warn("cost_monthly_budget_exceeded_during_record clientId={}", principal.clientId());
        }
        dailyCostCache.put(dailyKey(principal.clientId(), now),
                BigDecimal.valueOf(result.dailyMicros() >= 0 ? result.dailyMicros() : 0).scaleByPowerOfTen(-COST_SCALE));
        monthlyCostCache.put(monthlyKey(principal.clientId(), now),
                BigDecimal.valueOf(result.monthlyMicros() >= 0 ? result.monthlyMicros() : 0).scaleByPowerOfTen(-COST_SCALE));
    }

    static String costPeriodKey(Instant now) {
        return java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
    }

    static String costMonthlyPeriodKey(Instant now) {
        return java.time.YearMonth.now(java.time.ZoneOffset.UTC).toString();
    }

    public BigDecimal currentDailyCost(String clientId, Instant now) {
        return costStore.currentDailyCost(clientId, now);
    }

    public BigDecimal currentMonthlyCost(String clientId, Instant now) {
        return costStore.currentMonthlyCost(clientId, now);
    }

    private BigDecimal getEffectiveDailyCost(ClientPrincipal principal) {
        if (principal.config() != null) {
            BigDecimal v = principal.config().getLimits().getDailyCost();
            if (v != null) return v;
        }
        UserAccount.UserLimits ul = principal.userLimits();
        return ul != null ? ul.dailyCost() : null;
    }

    private BigDecimal getEffectiveMonthlyCost(ClientPrincipal principal) {
        if (principal.config() != null) {
            BigDecimal v = principal.config().getLimits().getMonthlyCost();
            if (v != null) return v;
        }
        UserAccount.UserLimits ul = principal.userLimits();
        return ul != null ? ul.monthlyCost() : null;
    }

    private long resolveDailyBudgetMicros(ClientPrincipal principal) {
        BigDecimal v = getEffectiveDailyCost(principal);
        if (v == null) {
            return Long.MAX_VALUE;
        }
        return v.setScale(COST_SCALE, RoundingMode.HALF_UP)
                .movePointRight(COST_SCALE).longValueExact();
    }

    private long resolveMonthlyBudgetMicros(ClientPrincipal principal) {
        BigDecimal v = getEffectiveMonthlyCost(principal);
        if (v == null) {
            return Long.MAX_VALUE;
        }
        return v.setScale(COST_SCALE, RoundingMode.HALF_UP)
                .movePointRight(COST_SCALE).longValueExact();
    }
}
