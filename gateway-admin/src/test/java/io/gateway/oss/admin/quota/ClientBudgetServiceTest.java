package io.gateway.oss.admin.quota;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.ClientConfig;
import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.dto.ChatCompletionsRequest;
import io.gateway.oss.core.dto.ChatMessage;
import io.gateway.oss.core.contract.routing.ResolvedRoute;
import io.gateway.oss.core.contract.security.ClientPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientBudgetServiceTest {

    @Test
    void shouldRejectWhenDailyBudgetExceeded() {
        InMemoryClientCostStore costStore = new InMemoryClientCostStore();
        GatewayProperties properties = new GatewayProperties();
        properties.getPricing().getDefault().setUnitPrice(new BigDecimal("0.0001"));

        ClientPrincipal principal = principalWithDailyCost("0.0010");
        Instant now = Instant.parse("2026-04-27T03:00:00Z");
        costStore.addDailyCost(principal.clientId(), new BigDecimal("0.0010"), now);

        ClientBudgetService budgetService = new ClientBudgetService(costStore, new CostCalculator(properties));
        GatewayException error = assertThrows(GatewayException.class, () -> budgetService.checkDailyBudget(principal, now));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.getStatus());
        assertEquals("budget_exceeded", error.getCode());
    }

    @Test
    void shouldRejectWhenMonthlyBudgetExceeded() {
        InMemoryClientCostStore costStore = new InMemoryClientCostStore();
        GatewayProperties properties = new GatewayProperties();
        properties.getPricing().getDefault().setUnitPrice(new BigDecimal("0.0001"));

        ClientPrincipal principal = principalWithBudgets("1.0000", "0.0010");
        Instant now = Instant.parse("2026-04-27T03:00:00Z");
        costStore.addMonthlyCost(principal.clientId(), new BigDecimal("0.0010"), now);

        ClientBudgetService budgetService = new ClientBudgetService(costStore, new CostCalculator(properties));
        GatewayException error = assertThrows(GatewayException.class, () -> budgetService.checkMonthlyBudget(principal, now));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.getStatus());
        assertEquals("monthly_budget_exceeded", error.getCode());
    }

    @Test
    void shouldRejectWhenDailyBudgetIsZero() {
        InMemoryClientCostStore costStore = new InMemoryClientCostStore();
        GatewayProperties properties = new GatewayProperties();
        properties.getPricing().getDefault().setUnitPrice(new BigDecimal("0.0001"));

        ClientPrincipal principal = principalWithDailyCost("0.0");
        ClientBudgetService budgetService = new ClientBudgetService(costStore, new CostCalculator(properties));
        Instant now = Instant.parse("2026-04-27T03:00:00Z");

        GatewayException error = assertThrows(GatewayException.class, () -> budgetService.checkDailyBudget(principal, now));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.getStatus());
        assertEquals("budget_exceeded", error.getCode());
    }

    @Test
    void shouldRecordCostOnSuccess() {
        InMemoryClientCostStore costStore = new InMemoryClientCostStore();
        GatewayProperties properties = new GatewayProperties();
        properties.getPricing().getDefault().setUnitPrice(new BigDecimal("0.0002"));

        ClientPrincipal principal = principalWithBudgets("1.0000", "2.0000");
        ClientBudgetService budgetService = new ClientBudgetService(costStore, new CostCalculator(properties));
        Instant now = Instant.parse("2026-04-27T03:00:00Z");

        budgetService.recordCostOnSuccess(principal, request(), route(), 50L, now);

        assertEquals(new BigDecimal("0.010000"), costStore.currentDailyCost(principal.clientId(), now));
        assertEquals(new BigDecimal("0.010000"), costStore.currentMonthlyCost(principal.clientId(), now));
    }

    private ClientPrincipal principalWithDailyCost(String budget) {
        return principalWithBudgets(budget, null);
    }

    private ClientPrincipal principalWithBudgets(String dailyBudget, String monthlyBudget) {
        ClientConfig config = new ClientConfig();
        config.getLimits().setDailyCost(new BigDecimal(dailyBudget));
        if (monthlyBudget != null) {
            config.getLimits().setMonthlyCost(new BigDecimal(monthlyBudget));
        }
        return new ClientPrincipal("client-budget", config);
    }

    private ChatCompletionsRequest request() {
        return new ChatCompletionsRequest(
                "gpt-4o-mini",
                List.of(new ChatMessage("user", "hello")),
                false,
                0.7d,
                128
        );
    }

    private ResolvedRoute route() {
        return new ResolvedRoute(
                "gpt-4o-mini",
                "route-budget",
                null,
                "openai",
                "openai-compatible",
                "gpt-4o-mini",
                "http://localhost:18080",
                "key",
                Duration.ofSeconds(3),
                2,
                List.of()
        );
    }
}
