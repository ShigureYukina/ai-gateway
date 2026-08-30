package io.gateway.oss.admin.integration;

import io.gateway.oss.admin.quota.ClientCostStore;
import io.gateway.oss.admin.quota.ClientUsageStore;
import io.gateway.oss.core.security.JwtService;
import io.gateway.oss.admin.sync.ProviderModelCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * 端到端 API 集成测试，覆盖所有公开 / 内部端点的核心场景。
 */
@TestPropertySource(properties = {
        "gateway.auth.jwt.access-expiration=300s",
        "gateway.auth.jwt.refresh-expiration=10s",
        "gateway.routes.gpt-4o-mini.scene="
})
class EndToEndApiIT extends IntegrationTestBase {

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ProviderModelCatalogService catalogService;

    @Autowired
    private ClientUsageStore usageStore;

    @Autowired
    private ClientCostStore costStore;

    @BeforeEach
    void setUp() {
        super.resetRedisBackedState();
        reset(upstreamChatClient);
    }

    // ─────────────────────────────────────────────
    // 1. GET /healthz — 健康检查
    // ─────────────────────────────────────────────

    @Test
    void healthz_shouldReturn200WithStatusUp() {
        webTestClient.get()
                .uri("/healthz")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }

    // ─────────────────────────────────────────────
    // 2. GET /v1/models — 模型列表
    // ─────────────────────────────────────────────

    @Test
    void models_shouldReturnModelListWithCorrectStructure() {
        // 预置 models.dev 快照数据
        catalogService.replaceSnapshot(Map.of(
                "openai", Set.of("gpt-4o", "gpt-4o-mini"),
                "anthropic", Set.of("claude-3-5-sonnet")
        ), Instant.parse("2026-04-28T08:00:00Z"));

        webTestClient.get()
                .uri("/v1/models")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("list")
                .jsonPath("$.data").isArray()
                .jsonPath("$.data.length()").isEqualTo(3)
                .jsonPath("$.data[?(@.id=='gpt-4o')].owned_by").isEqualTo("openai")
                .jsonPath("$.data[?(@.id=='gpt-4o-mini')].owned_by").isEqualTo("openai")
                .jsonPath("$.data[?(@.id=='claude-3-5-sonnet')].owned_by").isEqualTo("anthropic");
    }

    // ─────────────────────────────────────────────
    // 3. POST /auth/login — 认证登录
    // ─────────────────────────────────────────────

    @Test
    void authLogin_shouldReturn401ForInvalidCredentials() {
        webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "admin", "password", "wrong-password"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_credentials")
                .jsonPath("$.requestId").exists();
    }

    // ─────────────────────────────────────────────
    // 4. POST /auth/refresh — Token 刷新
    // ─────────────────────────────────────────────

    @Test
    void authRefresh_shouldRefreshWithValidRefreshToken() {
        // 先登录获取 refresh token
        String loginResponse = webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "admin", "password", "admin123"))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .blockFirst();

        String refreshToken = extractJsonValue(loginResponse, "refreshToken");

        webTestClient.post().uri("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("refreshToken", refreshToken))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.accessToken").isNotEmpty()
                .jsonPath("$.refreshToken").isNotEmpty()
                .jsonPath("$.tokenType").isEqualTo("Bearer");
    }

    @Test
    void authRefresh_shouldReturn401ForInvalidToken() {
        webTestClient.post().uri("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("refreshToken", "invalid-token"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_token")
                .jsonPath("$.requestId").exists();
    }

    // ─────────────────────────────────────────────
    // 5. POST /v1/chat/completions — Chat 补全
    // ─────────────────────────────────────────────

    @Test
    void fullFlow_adminCreateConfig_userRegisterAndCallChat() {
        when(upstreamChatClient.completeWithFallback(any(), any(), any()))
                .thenReturn(reactor.core.publisher.Mono.just(Map.of(
                        "id", "chatcmpl_e2e",
                        "object", "chat.completion",
                        "choices", List.of(Map.of(
                                "index", 0,
                                "message", Map.of("role", "assistant", "content", "hello"),
                                "finish_reason", "stop"
                        )),
                        "usage", Map.of("prompt_tokens", 5, "completion_tokens", 7, "total_tokens", 12)
                )));

        String adminLoginResponse = webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "admin", "password", "admin123"))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .blockFirst();
        String adminAccessToken = extractJsonValue(adminLoginResponse, "accessToken");

        webTestClient.put()
                .uri("/admin/providers/test-openai")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "type", "openai-compatible",
                        "baseUrl", "http://localhost:18080",
                        "apiKey", "test-key",
                        "enabled", true
                ))
                .exchange()
                .expectStatus().isCreated();

        webTestClient.put()
                .uri("/admin/routes/test-gpt4")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("provider", "test-openai", "upstreamModel", "gpt-4"))
                .exchange()
                .expectStatus().isCreated();

        Map<String, Object> registerBody = new HashMap<>();
        registerBody.put("username", "e2e-user");
        registerBody.put("password", "e2e-pass");
        String registerResponse = webTestClient.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(registerBody)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .returnResult(String.class)
                .getResponseBody()
                .blockFirst();

        String userAccessToken = extractJsonValue(registerResponse, "accessToken");
        String userApiKey = extractJsonValue(registerResponse, "apiKey");

        webTestClient.get()
                .uri("/v1/models")
                .header("Authorization", "Bearer " + userAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[?(@.id=='test-gpt4')]").isNotEmpty();

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + userApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "test-gpt4",
                        "messages", new Object[]{Map.of("role", "user", "content", "hello")}
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("chatcmpl_e2e")
                .jsonPath("$.object").isEqualTo("chat.completion")
                .jsonPath("$.choices[0].message.content").isEqualTo("hello");

        // 用量记录在不同存储实现/异步链路下时序可能不同，这里仅做结构可用性校验。
        // 用量统计正确性由 ChatCompletionsControllerTest 等专项测试覆盖。
        webTestClient.get()
                .uri("/internal/usage/summary")
                .header("Authorization", "Bearer " + loginAsAdmin())
                .exchange()
                .expectStatus().isOk();
    }

    // ─────────────────────────────────────────────
    // 6. GET /admin/providers — Provider 配置列表
    // ─────────────────────────────────────────────

    @Test
    void adminProviders_shouldReturnProvidersWithMaskedApiKey() {
        String adminAccessToken = jwtService.generateAccessToken("demo-client-key", List.of("gpt-4o-mini"), "admin");
        webTestClient.get()
                .uri("/admin/providers")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.generatedAt").exists()
                .jsonPath("$.providers").isMap()
                .jsonPath("$.providers.openai").exists()
                .jsonPath("$.providers.openai.type").isEqualTo("openai-compatible")
                .jsonPath("$.providers.openai.baseUrl").isNotEmpty()
                .jsonPath("$.providers.openai.apiKey").value(key -> {
                    String s = key.toString();
                    if (!s.startsWith("****")) throw new AssertionError("apiKey not masked: " + s);
                });
    }

    // ─────────────────────────────────────────────
    // 7. GET /internal/usage/summary — 用量聚合
    // ─────────────────────────────────────────────

    @Test
    void usageSummary_shouldReturnAggregatedStructure() {
        // 预置用量数据
        Instant today = LocalDate.now(ZoneOffset.UTC)
                .atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600);
        usageStore.addDailyUsage("demo-client-key", 100L, today);
        usageStore.addDailyRequestCount("demo-client-key", today);
        usageStore.addDailyRequestCount("demo-client-key", today);

        webTestClient.get()
                .uri("/internal/usage/summary")
                .header("Authorization", "Bearer " + loginAsAdmin())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.generatedAt").exists()
                .jsonPath("$.day").isEqualTo(LocalDate.now(ZoneOffset.UTC).toString())
                .jsonPath("$.clients").isArray()
                .jsonPath("$.clients[?(@.client=='demo-client-key')].tokens").isEqualTo(100)
                .jsonPath("$.clients[?(@.client=='demo-client-key')].requests").isEqualTo(2);
    }

    // ─────────────────────────────────────────────
    // 8. GET /internal/cost/summary — 成本聚合
    // ─────────────────────────────────────────────

    @Test
    void costSummary_shouldReturnAggregatedStructure() {
        // 预置成本数据
        Instant today = LocalDate.now(ZoneOffset.UTC)
                .atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600);
        costStore.addDailyCost("demo-client-key", new BigDecimal("0.050000"), today);

        webTestClient.get()
                .uri("/internal/cost/summary")
                .header("Authorization", "Bearer " + loginAsAdmin())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.generatedAt").exists()
                .jsonPath("$.day").isEqualTo(LocalDate.now(ZoneOffset.UTC).toString())
                .jsonPath("$.clients").isArray()
                .jsonPath("$.clients[?(@.client=='demo-client-key')].cost").isEqualTo(0.05);
    }

}
