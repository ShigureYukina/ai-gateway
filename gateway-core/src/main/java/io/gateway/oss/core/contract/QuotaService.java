package io.gateway.oss.core.contract;

import io.gateway.oss.core.contract.security.ClientPrincipal;
import io.gateway.oss.core.dto.ChatCompletionsRequest;

import java.time.Instant;
import java.util.Map;

/**
 * Optional quota service — implemented by gateway-admin's ClientQuotaService.
 * When absent from the classpath, all checks pass through (unlimited).
 */
public interface QuotaService {

    void checkDailyQuota(ClientPrincipal principal, Instant now);

    void checkMonthlyQuota(ClientPrincipal principal, Instant now);

    void recordUsage(ClientPrincipal principal,
                     Map<String, Object> upstreamResponse,
                     ChatCompletionsRequest effectiveRequest,
                     Instant now);

    void recordStreamingUsageOnSuccess(ClientPrincipal principal,
                                       ChatCompletionsRequest effectiveRequest,
                                       Long upstreamTotalTokens,
                                       Instant now);

    long currentDailyUsage(String clientId, Instant now);

    long currentMonthlyUsage(String clientId, Instant now);

    long resolveUsageTokensForResponse(Map<String, Object> upstreamResponse,
                                       ChatCompletionsRequest effectiveRequest);

    long resolveUsageTokensForStreaming(Long upstreamTotalTokens,
                                        ChatCompletionsRequest effectiveRequest);
}
