package io.gateway.oss.admin.web;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.admin.limit.ClientTpmStore;
import io.gateway.oss.admin.observability.AggregateMetricStore;
import io.gateway.oss.admin.quota.ClientCostStore;
import io.gateway.oss.admin.quota.ClientUsageStore;
import io.gateway.oss.core.observability.RequestLogService;
import io.gateway.oss.core.security.UserAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

@SpringBootTest
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
        "gateway.shared-state.backend=in_memory",
        "gateway.auth.enabled=true",
        "gateway.auth.jwt.secret=super-secret-key-that-is-at-least-32-chars",
        "gateway.auth.jwt.access-expiration=5s",
        "gateway.auth.jwt.refresh-expiration=60s",
        "gateway.auth.users.admin.password=admin123",
        "gateway.auth.users.admin.client-id=demo-client-key",
        "gateway.auth.users.admin.role=admin",
        "gateway.auth.users.user1.password=pass1",
        "gateway.auth.users.user1.client-id=demo-client-key",
        "gateway.auth.users.user1.role=user",
        "gateway.auth.users.user.password=testpass",
        "gateway.clients.demo-client-key.enabled=true",
        "gateway.clients.demo-client-key.allowed-models[0]=gpt-4o-mini",
        "gateway.clients.demo-client-key.allowed-scenes[0]=default-chat",
        "gateway.clients.demo-client-key.defaults.scene=default-chat",
        "gateway.clients.demo-client-key.defaults.temperature=0.7",
        "gateway.clients.demo-client-key.defaults.max-tokens=256",
        "gateway.clients.demo-client-key.capabilities.streaming=true",
        "gateway.clients.demo-client-key.limits.max-tokens=512",
        "gateway.clients.demo-client-key.limits.daily-tokens=1000",
        "gateway.clients.demo-client-key.limits.daily-cost=1.25",
        "gateway.clients.demo-client-key.limits.monthly-tokens=5000",
        "gateway.clients.demo-client-key.limits.monthly-cost=9.99",
        "gateway.clients.jwt-only-client.enabled=true",
        "gateway.clients.jwt-only-client.allowed-models[0]=gpt-4o-mini",
        "gateway.clients.jwt-only-client.defaults.scene=default-chat",
        "gateway.providers.openai.base-url=http://localhost:18080",
        "gateway.providers.openai.api-key=upstream-demo-key",
        "gateway.providers.openai.models[0]=gpt-4o-mini",
        "gateway.routes.gpt-4o-mini.scene=default-chat",
        "gateway.routes.openai-primary.provider=openai",
        "gateway.routes.openai-primary.upstream-model=gpt-4o-mini",
        "gateway.routes.openai-fallback.provider=openai",
        "gateway.routes.openai-fallback.upstream-model=gpt-4o-mini",
        "gateway.scenes.default-chat.fallback-routes[0]=openai-fallback",
        "gateway.scenes.default-chat.primary-route=openai-primary",
        "gateway.sync.models-dev.endpoint=http://127.0.0.1:1/api.json",
        "gateway.sync.models-dev.timeout=500ms",
        "gateway.limit.requests-per-window=100",
        "gateway.limit.window=5m"
})
class InternalUsageSummaryControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private WebTestCleanupSupport cleanup;

    @Autowired
    private ClientUsageStore usageStore;

    @Autowired
    private ClientCostStore costStore;

    @Autowired
    private ClientTpmStore clientTpmStore;

    @Autowired
    private RequestLogService requestLogService;

    @Autowired
    private AggregateMetricStore aggregateMetricStore;

    @Autowired
    private GatewayProperties properties;

    @Autowired
    private UserAccountService userAccountService;

    @BeforeEach
    void resetState() {
        cleanup.resetState();
        userAccountService.register("admin", "admin123", "admin").block();
    }

    private String loginAsAdmin() {
        String body = webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "admin", "password", "admin123"))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody().blockFirst();
        int start = body.indexOf("\"accessToken\":\"") + "\"accessToken\":\"".length();
        int end = body.indexOf("\"", start);
        return body.substring(start, end);
    }

    // ─── Usage Summary ───

    @Test
    void shouldReturnUsageSummaryWithZeroTokensWhenNoDataRecorded() {
        // When no client filter → all configured clients returned with zero usage
        webTestClient.get()
                .uri("/internal/usage/summary")
                .header("Authorization", "Bearer " + loginAsAdmin())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.generatedAt").exists()
                .jsonPath("$.day").isEqualTo(LocalDate.now(ZoneOffset.UTC).toString())
                .jsonPath("$.clients").isArray()
                .jsonPath("$.clients[?(@.client=='demo-client-key')].tokens").isEqualTo(0)
                .jsonPath("$.clients[?(@.client=='demo-client-key')].requests").isEqualTo(0);
    }

    @Test
    void shouldCombineClientAndDayFiltersForUsage() {
        String targetDay = "2026-04-26";
        Instant dayInstant = LocalDate.parse(targetDay).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600);
        usageStore.addDailyUsage("demo-client-key", 300L, dayInstant);
        usageStore.addDailyRequestCount("demo-client-key", dayInstant);

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/internal/usage/summary")
                        .queryParam("client", "demo-client-key")
                        .queryParam("day", targetDay)
                        .build())
                .header("Authorization", "Bearer " + loginAsAdmin())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.day").isEqualTo(targetDay)
                .jsonPath("$.clients.length()").isEqualTo(1)
                .jsonPath("$.clients[0].client").isEqualTo("demo-client-key")
                .jsonPath("$.clients[0].tokens").isEqualTo(300)
                .jsonPath("$.clients[0].requests").isEqualTo(1);
    }

    @Test
    void shouldAccumulateMultipleRequestsInUsage() {
        Instant today = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600);
        usageStore.addDailyUsage("demo-client-key", 100L, today);
        usageStore.addDailyRequestCount("demo-client-key", today);
        usageStore.addDailyUsage("demo-client-key", 200L, today);
        usageStore.addDailyRequestCount("demo-client-key", today);
        usageStore.addDailyRequestCount("demo-client-key", today);

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/internal/usage/summary")
                        .queryParam("client", "demo-client-key")
                        .build())
                .header("Authorization", "Bearer " + loginAsAdmin())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.clients[0].tokens").isEqualTo(300)
                .jsonPath("$.clients[0].requests").isEqualTo(3);
    }

    // ─── Cost Summary ───

    @Test
    void shouldReturnCostSummaryWithZeroCostWhenNoDataRecorded() {
        // When no client filter → all configured clients returned with zero cost
        webTestClient.get()
                .uri("/internal/cost/summary")
                .header("Authorization", "Bearer " + loginAsAdmin())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.generatedAt").exists()
                .jsonPath("$.day").isEqualTo(LocalDate.now(ZoneOffset.UTC).toString())
                .jsonPath("$.clients").isArray()
                .jsonPath("$.clients[?(@.client=='demo-client-key')].cost").isEqualTo(0);
    }

    @Test
    void shouldCombineClientAndDayFiltersForCost() {
        String targetDay = "2026-04-26";
        Instant dayInstant = LocalDate.parse(targetDay).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600);
        costStore.addDailyCost("demo-client-key", new BigDecimal("0.123456"), dayInstant);

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/internal/cost/summary")
                        .queryParam("client", "demo-client-key")
                        .queryParam("day", targetDay)
                        .build())
                .header("Authorization", "Bearer " + loginAsAdmin())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.day").isEqualTo(targetDay)
                .jsonPath("$.clients.length()").isEqualTo(1)
                .jsonPath("$.clients[0].client").isEqualTo("demo-client-key")
                .jsonPath("$.clients[0].cost").isEqualTo(0.123456);
    }

    // ─── Response safety ───

    @Test
    void shouldNotExposeSensitiveFieldsInUsageSummary() {
        webTestClient.get()
                .uri("/internal/usage/summary")
                .header("Authorization", "Bearer " + loginAsAdmin())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.api-key").doesNotExist()
                .jsonPath("$.keys").doesNotExist()
                .jsonPath("$.secret").doesNotExist()
                .jsonPath("$.token").doesNotExist();
    }

    @Test
    void shouldFallbackToTodayWhenDayFormatInvalid() {
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/internal/usage/summary")
                        .queryParam("day", "invalid-date")
                        .build())
                .header("Authorization", "Bearer " + loginAsAdmin())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_request")
                .jsonPath("$.message").isEqualTo("Invalid day, expected YYYY-MM-DD");
    }

    @Test
    void shouldReturnDashboardOverviewFromServerAggregates() {
        String targetDay = "2026-04-26";
        Instant requestTime = LocalDate.parse(targetDay).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600);
        for (int i = 0; i < 1500; i++) {
            usageStore.addDailyRequestCount("demo-client-key", requestTime);
        }
        usageStore.addDailyUsage("demo-client-key", 90000L, requestTime);
        costStore.addDailyCost("demo-client-key", new BigDecimal("12.34"), requestTime);
        aggregateMetricStore.record("model", "gpt-4o-mini", "gpt-4o-mini", 1200, 70000, new BigDecimal("8.00"), requestTime);
        aggregateMetricStore.record("model", "claude-3-5-sonnet", "claude-3-5-sonnet", 300, 20000, new BigDecimal("4.34"), requestTime);
        aggregateMetricStore.record("status", "2xx", "2xx", 1490, 90000, new BigDecimal("12.34"), requestTime);
        aggregateMetricStore.record("status", "4xx", "4xx", 7, 0, BigDecimal.ZERO, requestTime);
        aggregateMetricStore.record("status", "5xx", "5xx", 3, 0, BigDecimal.ZERO, requestTime);
        requestLogService.record(new RequestLogService.RequestLogEntry(
                "req-1", "demo-client-key", "demo-client-key", "gpt-4o-mini", "openai", "openai-primary", "default-chat",
                200, 120, requestTime, "false", 120L, 80L, 40L, 0.21d, null
        ));
        requestLogService.record(new RequestLogService.RequestLogEntry(
                "req-2", "demo-client-key", "demo-client-key", "gpt-4o-mini", "openai", "openai-primary", "default-chat",
                429, 90, requestTime.plusSeconds(30), "false", 180L, 120L, 60L, 0.21d, "tpm_exceeded"
        ));

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/internal/dashboard/overview")
                        .queryParam("day", targetDay)
                        .build())
                .header("Authorization", "Bearer " + loginAsAdmin())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.day").isEqualTo(targetDay)
                .jsonPath("$.totalRequests").isEqualTo(1500)
                .jsonPath("$.totalTokens").isEqualTo(90000)
                .jsonPath("$.totalCost").isEqualTo(12.34)
                .jsonPath("$.success2xx").isEqualTo(1490)
                .jsonPath("$.status4xx").isEqualTo(7)
                .jsonPath("$.status5xx").isEqualTo(3)
                .jsonPath("$.activeClients").isEqualTo(1)
                .jsonPath("$.topModels[0].model").isEqualTo("gpt-4o-mini")
                .jsonPath("$.topModels[0].requests").isEqualTo(1200)
                .jsonPath("$.topClients[0].client").isEqualTo("demo-client-key")
                .jsonPath("$.topClients[0].cost").isEqualTo(12.34);
    }

    @Test
    void shouldReconcileDashboardOverviewWithRecentRequestsAndSystemStatusForConsole() {
        String targetDay = "2026-04-27";
        Instant baseTime = LocalDate.parse(targetDay).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(1800);

        requestLogService.record(new RequestLogService.RequestLogEntry(
                "req_metric_001", "demo-client-key", "demo-client-key", "gpt-4o-mini", "openai", "openai-primary", "default-chat",
                200, 100, baseTime, "false", 100L, 60L, 40L, 0.0100d, null
        ));
        requestLogService.record(new RequestLogService.RequestLogEntry(
                "req_metric_002", "demo-client-key", "demo-client-key", "gpt-4o-mini", "openai", "openai-primary", "default-chat",
                200, 80, baseTime.plusSeconds(10), "false", 50L, 30L, 20L, 0.0050d, null
        ));
        requestLogService.record(new RequestLogService.RequestLogEntry(
                "req_metric_003", "demo-client-key", "demo-client-key", "gpt-4o-mini", "openai", "openai-primary", "default-chat",
                500, 150, baseTime.plusSeconds(20), "false", 0L, 0L, 0L, 0.0000d, "upstream_error"
        ));

        for (int i = 0; i < 3; i++) {
            usageStore.addDailyRequestCount("demo-client-key", baseTime);
        }
        usageStore.addDailyUsage("demo-client-key", 150L, baseTime);
        costStore.addDailyCost("demo-client-key", new BigDecimal("0.0150"), baseTime);

        aggregateMetricStore.record("status", "2xx", "2xx", 2, 150, new BigDecimal("0.0150"), baseTime);
        aggregateMetricStore.record("status", "4xx", "4xx", 0, 0, BigDecimal.ZERO, baseTime);
        aggregateMetricStore.record("status", "5xx", "5xx", 1, 0, BigDecimal.ZERO, baseTime);
        aggregateMetricStore.record("model", "gpt-4o-mini", "gpt-4o-mini", 3, 150, new BigDecimal("0.0150"), baseTime);

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/internal/dashboard/overview")
                        .queryParam("day", targetDay)
                        .build())
                .header("Authorization", "Bearer " + loginAsAdmin())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.day").isEqualTo(targetDay)
                .jsonPath("$.totalRequests").isEqualTo(3)
                .jsonPath("$.totalTokens").isEqualTo(150)
                .jsonPath("$.totalCost").isEqualTo(0.015)
                .jsonPath("$.success2xx").isEqualTo(2)
                .jsonPath("$.status4xx").isEqualTo(0)
                .jsonPath("$.status5xx").isEqualTo(1)
                .jsonPath("$.activeClients").isEqualTo(1)
                .jsonPath("$.topModels[0].model").isEqualTo("gpt-4o-mini")
                .jsonPath("$.topModels[0].requests").isEqualTo(3)
                .jsonPath("$.topClients[0].client").isEqualTo("demo-client-key")
                .jsonPath("$.topClients[0].cost").isEqualTo(0.015);

        webTestClient.get()
                .uri("/admin/requests/recent?limit=10")
                .header("Authorization", "Bearer " + loginAsAdmin())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.requests.length()").isEqualTo(3)
                .jsonPath("$.requests[0].requestId").exists()
                .jsonPath("$.requests[0].provider").isEqualTo("openai")
                .jsonPath("$.requests[0].routeId").isEqualTo("openai-primary")
                .jsonPath("$.requests[?(@.requestId=='req_metric_001')].status").isEqualTo(200)
                .jsonPath("$.requests[?(@.requestId=='req_metric_002')].status").isEqualTo(200)
                .jsonPath("$.requests[?(@.requestId=='req_metric_003')].status").isEqualTo(500);

        webTestClient.get()
                .uri("/internal/system/status")
                .header("Authorization", "Bearer " + loginAsAdmin())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.maintenance.active").isEqualTo(false)
                .jsonPath("$.emergencyRateLimit.enabled").isEqualTo(false)
                .jsonPath("$.globalCircuit.hasAvailableRoute").isEqualTo(true);
    }

    @Test
    void shouldReturnCurrentMinuteTpmUsageForConfiguredClient() {
        Instant now = Instant.now();
        properties.getClients().get("demo-client-key").getLimits().setTokensPerMinute(500L);
        clientTpmStore.reserve("demo-client-key", 120L, 500L, now);

        webTestClient.get()
                .uri("/internal/usage/tpm")
                .header("Authorization", "Bearer " + loginAsAdmin())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.clients[0].client").isEqualTo("demo-client-key")
                .jsonPath("$.clients[0].usedTokens").isEqualTo(120)
                .jsonPath("$.clients[0].limitTokens").isEqualTo(500)
                .jsonPath("$.clients[0].remainingTokens").isEqualTo(380)
                .jsonPath("$.clients[0].utilizationPercent").isEqualTo(24.0);
    }

}
