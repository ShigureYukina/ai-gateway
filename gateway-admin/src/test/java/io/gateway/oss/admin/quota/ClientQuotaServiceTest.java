package io.gateway.oss.admin.quota;

import io.gateway.oss.core.config.ClientConfig;
import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.dto.ChatCompletionsRequest;
import io.gateway.oss.core.dto.ChatMessage;
import io.gateway.oss.core.contract.security.ClientPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientQuotaServiceTest {

    @Test
    void shouldRejectWhenDailyQuotaExceeded() {
        InMemoryClientUsageStore usageStore = new InMemoryClientUsageStore();
        ClientPrincipal principal = principalWithDailyQuota(100);
        Instant now = Instant.parse("2026-04-27T01:00:00Z");
        usageStore.addDailyUsage(principal.clientId(), 100, now);

        ClientQuotaService quotaService = new ClientQuotaService(usageStore);
        GatewayException error = assertThrows(GatewayException.class,
                () -> quotaService.checkDailyQuota(principal, now));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.getStatus());
        assertEquals("quota_exceeded", error.getCode());
    }

    @Test
    void shouldRejectWhenMonthlyQuotaExceeded() {
        InMemoryClientUsageStore usageStore = new InMemoryClientUsageStore();
        ClientPrincipal principal = principalWithQuotas(1000, 100L);
        Instant now = Instant.parse("2026-04-27T01:00:00Z");
        usageStore.addMonthlyUsage(principal.clientId(), 100, now);

        ClientQuotaService quotaService = new ClientQuotaService(usageStore);
        GatewayException error = assertThrows(GatewayException.class,
                () -> quotaService.checkMonthlyQuota(principal, now));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.getStatus());
        assertEquals("monthly_quota_exceeded", error.getCode());
    }

    @Test
    void shouldRecordTotalTokensFromUsage() {
        InMemoryClientUsageStore usageStore = new InMemoryClientUsageStore();
        ClientPrincipal principal = principalWithQuotas(1000, 2000L);
        ClientQuotaService quotaService = new ClientQuotaService(usageStore);
        Instant now = Instant.parse("2026-04-27T01:00:00Z");

        quotaService.recordUsage(principal,
                Map.of("usage", Map.of("total_tokens", 77L)),
                requestWithMaxTokens(256),
                now);

        assertEquals(77L, usageStore.currentDailyUsage(principal.clientId(), now));
        assertEquals(77L, usageStore.currentMonthlyUsage(principal.clientId(), now));
    }

    @Test
    void shouldFallbackToPromptPlusCompletionWhenTotalMissing() {
        InMemoryClientUsageStore usageStore = new InMemoryClientUsageStore();
        ClientPrincipal principal = principalWithQuotas(1000, 2000L);
        ClientQuotaService quotaService = new ClientQuotaService(usageStore);
        Instant now = Instant.parse("2026-04-27T01:00:00Z");

        quotaService.recordUsage(principal,
                Map.of("usage", Map.of("prompt_tokens", 20, "completion_tokens", 30)),
                requestWithMaxTokens(256),
                now);

        assertEquals(50L, usageStore.currentDailyUsage(principal.clientId(), now));
    }

    @Test
    void shouldFallbackToRequestMaxTokensWhenUsageMissing() {
        InMemoryClientUsageStore usageStore = new InMemoryClientUsageStore();
        ClientPrincipal principal = principalWithQuotas(1000, 2000L);
        ClientQuotaService quotaService = new ClientQuotaService(usageStore);
        Instant now = Instant.parse("2026-04-27T01:00:00Z");

        quotaService.recordUsage(principal,
                Map.of("id", "resp_no_usage"),
                requestWithMaxTokens(123),
                now);

        assertEquals(123L, usageStore.currentDailyUsage(principal.clientId(), now));
    }

    @Test
    void shouldRecordStreamingUsageFromUpstreamTotalTokens() {
        InMemoryClientUsageStore usageStore = new InMemoryClientUsageStore();
        ClientPrincipal principal = principalWithQuotas(1000, 2000L);
        ClientQuotaService quotaService = new ClientQuotaService(usageStore);
        Instant now = Instant.parse("2026-04-27T01:00:00Z");

        quotaService.recordStreamingUsageOnSuccess(principal, requestWithMaxTokens(256), 88L, now);

        assertEquals(88L, usageStore.currentDailyUsage(principal.clientId(), now));
    }

    @Test
    void shouldFallbackStreamingUsageToRequestMaxTokensWhenUpstreamUsageMissing() {
        InMemoryClientUsageStore usageStore = new InMemoryClientUsageStore();
        ClientPrincipal principal = principalWithQuotas(1000, 2000L);
        ClientQuotaService quotaService = new ClientQuotaService(usageStore);
        Instant now = Instant.parse("2026-04-27T01:00:00Z");

        quotaService.recordStreamingUsageOnSuccess(principal, requestWithMaxTokens(111), null, now);

        assertEquals(111L, usageStore.currentDailyUsage(principal.clientId(), now));
    }

    @Test
    void shouldFallbackStreamingUsageToZeroWhenUsageAndMaxTokensMissing() {
        InMemoryClientUsageStore usageStore = new InMemoryClientUsageStore();
        ClientPrincipal principal = principalWithQuotas(1000, 2000L);
        ClientQuotaService quotaService = new ClientQuotaService(usageStore);
        Instant now = Instant.parse("2026-04-27T01:00:00Z");

        quotaService.recordStreamingUsageOnSuccess(principal, requestWithMaxTokens(null), null, now);

        assertEquals(0L, usageStore.currentDailyUsage(principal.clientId(), now));
    }

    @Test
    void shouldExposeResolvedUsageForResponseFallbackOrder() {
        InMemoryClientUsageStore usageStore = new InMemoryClientUsageStore();
        ClientQuotaService quotaService = new ClientQuotaService(usageStore);

        long tokens = quotaService.resolveUsageTokensForResponse(
                Map.of("usage", Map.of("prompt_tokens", 9, "completion_tokens", 11)),
                requestWithMaxTokens(256)
        );

        assertEquals(20L, tokens);
    }

    private ClientPrincipal principalWithDailyQuota(long dailyTokens) {
        return principalWithQuotas(dailyTokens, null);
    }

    private ClientPrincipal principalWithQuotas(long dailyTokens, Long monthlyTokens) {
        ClientConfig config = new ClientConfig();
        config.getLimits().setDailyTokens(dailyTokens);
        config.getLimits().setMonthlyTokens(monthlyTokens);
        return new ClientPrincipal("client-A", config);
    }

    private ChatCompletionsRequest requestWithMaxTokens(Integer maxTokens) {
        return new ChatCompletionsRequest(
                "gpt-4o-mini",
                List.of(new ChatMessage("user", "hello")),
                false,
                0.7d,
                maxTokens
        );
    }
}
