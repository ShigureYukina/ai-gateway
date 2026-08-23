package io.gateway.oss.admin.integration;

import io.gateway.oss.core.upstream.OpenAiCompatibleChatProviderAdapter;
import io.gateway.oss.core.upstream.Resilience4jCircuitBreakerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * 可靠性场景集成测试。
 * 覆盖：Fallback 路由、熔断器（CB）开关、SSE 流式。
 * <p>
 * 继承 {@link RedisIntegrationTestSupport}（Testcontainers PG+Redis），
 * 绕过 {@link IntegrationTestBase} 的 {@code @MockBean UpstreamChatClient}，
 * 改为 mock {@link OpenAiCompatibleChatProviderAdapter}，保持真实
 * {@link io.gateway.oss.core.upstream.UpstreamChatClient} 运行。
 */
@SpringBootTest
@AutoConfigureWebTestClient
@ActiveProfiles({"test", "test-redis"})
@TestPropertySource(properties = {
        "gateway.auth.enabled=true",
        "gateway.auth.jwt.secret=super-secret-key-that-is-at-least-32-chars",
        "gateway.auth.jwt.access-expiration=30s",
        "gateway.auth.jwt.refresh-expiration=60s",
        "gateway.auth.users.admin.password=admin123",
        "gateway.auth.users.admin.client-id=demo-client-key",
        "gateway.auth.users.admin.role=admin",

        "gateway.providers.test-provider.type=openai-compatible",
        "gateway.providers.test-provider.base-url=http://localhost:18080",
        "gateway.providers.test-provider.api-key=test-upstream-key",
        "gateway.providers.test-provider.timeout=5s",

        "gateway.routes.test-model.provider=test-provider",
        "gateway.routes.test-model.upstream-model=gpt-4o",
        "gateway.routes.test-model.fallback-routes[0]=route-fallback",
        "gateway.routes.test-model.enabled=true",

        "gateway.routes.route-fallback.provider=test-provider",
        "gateway.routes.route-fallback.upstream-model=gpt-4o-mini",
        "gateway.routes.route-fallback.enabled=true",

        "gateway.clients.demo-client-key.enabled=true",
        "gateway.clients.demo-client-key.allowed-models[0]=test-model",
        "gateway.clients.demo-client-key.capabilities.streaming=true",

        "gateway.resilience.sliding-window-size=2",
        "gateway.resilience.minimum-number-of-calls=2",
        "gateway.resilience.wait-duration-in-open-state=60s",
        "gateway.resilience.retry-max-attempts=1",

        "gateway.security.block-internal-urls=false",
})
class ReliabilityIT extends RedisIntegrationTestSupport {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private Resilience4jCircuitBreakerService circuitBreakerService;

    @MockBean
    private OpenAiCompatibleChatProviderAdapter chatProviderAdapter;

    private String adminToken;

    @BeforeEach
    void setUp() {
        super.resetRedisBackedState();
        reset(chatProviderAdapter);

        // 所有测试的通用 stubs
        lenient().when(chatProviderAdapter.providerType()).thenReturn("openai-compatible");
        lenient().when(chatProviderAdapter.supportsStreaming()).thenReturn(true);

        // login
        adminToken = loginAndGetAccessToken("admin", "admin123");
    }

    // ─────────────────────────────────────────────────────────────
    // Test 1: Fallback 路由 — 主 route 失败后降级到 fallback route
    // ─────────────────────────────────────────────────────────────

    @Test
    void shouldFallbackToSecondaryRouteWhenPrimaryFails() {
        // 主 route 返回错误，fallback route 返回成功
        when(chatProviderAdapter.complete(any(), any())).thenAnswer(invocation -> {
            var route = invocation.getArgument(1,
                    io.gateway.oss.core.contract.routing.ResolvedRoute.class);
            if ("test-model".equals(route.routeId())) {
                return Mono.error(new io.gateway.oss.core.error.GatewayException(
                        org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "upstream_error", "Simulated failure"));
            }
            return Mono.just(Map.of(
                    "id", "fallback-cmpl",
                    "object", "chat.completion",
                    "usage", Map.of("prompt_tokens", 5, "completion_tokens", 5, "total_tokens", 10)
            ));
        });

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "test-model",
                        "messages", new Object[]{Map.of("role", "user", "content", "hello")},
                        "stream", false
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("fallback-cmpl")
                .jsonPath("$.object").isEqualTo("chat.completion")
                .jsonPath("$.usage.total_tokens").isEqualTo(10);
    }

    // ─────────────────────────────────────────────────────────────
    // Test 2: 熔断器 — 失败触发开闸 -> 503 -> 恢复后成功
    // ─────────────────────────────────────────────────────────────

    @Test
    void circuitBreaker_opensAfterFailures_thenRecoversAfterReset() {
        // 适配器始终返回错误
        when(chatProviderAdapter.complete(any(), any()))
                .thenReturn(Mono.error(new io.gateway.oss.core.error.GatewayException(
                        org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "upstream_error", "Simulated failure")));

        // 第 1 次请求 -> 502 (upstream_error)
        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "test-model",
                        "messages", new Object[]{Map.of("role", "user", "content", "hello")},
                        "stream", false
                ))
                .exchange()
                .expectStatus().is5xxServerError();

        // 第 2 次请求 -> 502 (upstream_error) -> CB opens
        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "test-model",
                        "messages", new Object[]{Map.of("role", "user", "content", "hello")},
                        "stream", false
                ))
                .exchange()
                .expectStatus().is5xxServerError();

        // 第 3 次请求 -> 503 circuit_breaker_open
        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "test-model",
                        "messages", new Object[]{Map.of("role", "user", "content", "hello")},
                        "stream", false
                ))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.code").isEqualTo("circuit_breaker_open");

        // 重置熔断器
        circuitBreakerService.resetResilience();

        // 适配器改为返回成功
        reset(chatProviderAdapter);
        lenient().when(chatProviderAdapter.providerType()).thenReturn("openai-compatible");
        when(chatProviderAdapter.complete(any(), any()))
                .thenReturn(Mono.just(Map.of(
                        "id", "recovered-cmpl",
                        "object", "chat.completion",
                        "usage", Map.of("prompt_tokens", 10, "completion_tokens", 20, "total_tokens", 30)
                )));

        // 第 4 次请求 -> 200 (恢复成功)
        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "test-model",
                        "messages", new Object[]{Map.of("role", "user", "content", "recovery")},
                        "stream", false
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("recovered-cmpl")
                .jsonPath("$.usage.total_tokens").isEqualTo(30);
    }

    // ─────────────────────────────────────────────────────────────
    // Test 3: SSE 流式 — Mock 返回 SSE chunk，验证流式响应
    // ─────────────────────────────────────────────────────────────

    @Test
    void sseStreaming_returnsEventStreamFromAdapter() {
        List<String> sseChunks = List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\" World\"}}]}\n\n",
                "data: {\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12}}\n\n",
                "data: [DONE]\n\n"
        );

        when(chatProviderAdapter.stream(any(), any()))
                .thenReturn(Flux.fromIterable(sseChunks));

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of(
                        "model", "test-model",
                        "messages", new Object[]{Map.of("role", "user", "content", "hello")},
                        "stream", true,
                        "max_tokens", 128
                ))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody()
                .consumeWith(result -> {
                    byte[] body = result.getResponseBody();
                    assert body != null;
                    String bodyText = new String(body, java.nio.charset.StandardCharsets.UTF_8);
                    org.assertj.core.api.Assertions.assertThat(bodyText)
                            .contains("data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}")
                            .contains("data: {\"choices\":[{\"delta\":{\"content\":\" World\"}}]}")
                            .contains("data: [DONE]");
                });
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private String loginAndGetAccessToken(String username, String password) {
        String responseBody = webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", username, "password", password))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .blockFirst();
        return extractJsonValue(responseBody, "accessToken");
    }

    private String extractJsonValue(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int start = json.indexOf(pattern) + pattern.length();
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }
}
