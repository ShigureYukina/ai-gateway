package io.gateway.oss.core.contract;

import io.gateway.oss.core.contract.routing.ResolvedRoute;
import io.gateway.oss.core.contract.security.ClientPrincipal;
import io.gateway.oss.core.dto.ChatCompletionsRequest;

import java.time.Instant;

/**
 * Optional budget service — implemented by gateway-admin's ClientBudgetService.
 * When absent from the classpath, all checks pass through (unlimited).
 */
public interface BudgetService {

    void checkDailyBudget(ClientPrincipal principal, Instant now);

    void checkMonthlyBudget(ClientPrincipal principal, Instant now);

    void recordCostOnSuccess(ClientPrincipal principal,
                             ChatCompletionsRequest effectiveRequest,
                             ResolvedRoute route,
                             long promptTokens,
                             long completionTokens,
                             Instant now);

    void recordCostOnSuccess(ClientPrincipal principal,
                             ChatCompletionsRequest effectiveRequest,
                             ResolvedRoute route,
                             long usageTokens,
                             Instant now);
}
