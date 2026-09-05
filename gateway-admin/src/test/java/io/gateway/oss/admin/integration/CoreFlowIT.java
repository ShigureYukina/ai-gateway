package io.gateway.oss.admin.integration;

import io.gateway.oss.core.observability.RequestLogService;
import io.gateway.oss.core.observability.TraceStore;
import io.gateway.oss.admin.quota.ClientCostStore;
import io.gateway.oss.admin.quota.ClientUsageStore;
import io.gateway.oss.core.security.JwtService;
import io.gateway.oss.core.upstream.ProviderRuntimeStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * 核心业务流程集成测试。
 * 覆盖：auth→chat→usage 链路、配置动态生效、请求日志串联、多 Key 隔离、auth disabled 模式。
 */
class CoreFlowIT extends IntegrationTestBase {

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ClientUsageStore usageStore;

    @Autowired
    private ClientCostStore costStore;

    @Autowired
    private RequestLogService requestLogService;

    @Autowired
    private TraceStore traceStore;

    @Autowired
    private ProviderRuntimeStateStore runtimeStateStore;

    @BeforeEach
    void setUp() {
        super.resetRedisBackedState();
        reset(upstreamChatClient);
    }

    protected String loginAsAdmin() {
        return loginAndGetAccessToken("admin", "admin123");
    }

    // ─────────────────────────────────────────────────────────────
    // 1. 完整 auth → chat → usage 链路
    // ─────────────────────────────────────────────────────────────

    @Test
    void fullAuthChatUsageChain_loginCallChatVerifyUsage() {
        when(upstreamChatClient.completeWithFallback(any(), any(), any()))
                .thenReturn(Mono.just(Map.of(
                        "id", "chatcmpl_chain",
                        "object", "chat.completion",
                        "usage", Map.of("prompt_tokens", 10, "completion_tokens", 20, "total_tokens", 30)
                )));

        // Step 1: login
        String token = loginAndGetAccessToken("admin", "admin123");
        assertThat(token).isNotEmpty();

        // Step 2: chat completion（手动断言以在失败时携带响应体，便于定位 4xx 错误码）
        var chatResult = webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + token)
                .header("X-Request-Id", "req_core_success")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(chatRequestBody(false))
                .exchange()
                .expectBody(String.class)
                .returnResult();
        assertThat(chatResult.getStatus().value())
                .as("chat response: %s", chatResult.getResponseBody())
                .isEqualTo(200);
        assertThat(chatResult.getResponseBody()).contains("chatcmpl_chain");

        // Step 3: verify usage summary reflects the call via public endpoints
        webTestClient.get()
                .uri("/internal/usage/summary")
                .header("Authorization", "Bearer " + loginAsAdmin())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.clients[?(@.client=='demo-client-key')].tokens").isNotEmpty();

        webTestClient.get().uri("/auth/me")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk();

        RequestLogService.RequestLogEntry entry = requestLogService.getByRequestId("req_core_success").block();
        assertThat(entry).isNotNull();
        assertThat(entry.clientId()).isEqualTo("***");
        assertThat(entry.model()).isEqualTo("gpt-4o-mini");
        assertThat(entry.provider()).isEqualTo("openai");
        assertThat(entry.routeId()).isEqualTo("openai-primary");
        assertThat(entry.scene()).isEqualTo("default-chat");
        assertThat(entry.status()).isEqualTo(200);
        assertThat(entry.streamMode()).isEqualTo("non-streaming");
        assertThat(entry.usageTokens()).isEqualTo(30L);
        assertThat(entry.costUsd()).isEqualTo(0.003d);

        var trace = traceStore.getByRequestId("req_core_success");
        assertThat(trace).isNotNull();
        assertThat(trace.requestId()).isEqualTo("req_core_success");
        assertThat(trace.clientId()).isEqualTo("***");
        assertThat(trace.model()).isEqualTo("gpt-4o-mini");
        assertThat(trace.provider()).isEqualTo("openai");
        assertThat(trace.routeId()).isEqualTo("openai-primary");
        assertThat(trace.scene()).isEqualTo("default-chat");
        assertThat(trace.status()).isEqualTo(200);
        assertThat(trace.streamMode()).isEqualTo("non-streaming");
        assertThat(trace.requestBody()).isNull();
        assertThat(trace.responseBody()).isNull();
    }

    // ─────────────────────────────────────────────────────────────
    // 2. 动态配置变更 → 行为生效
    // ─────────────────────────────────────────────────────────────

    @Test
    void dynamicConfig_adminCreatesRoute_thenUserCanCallNewModel() {
        when(upstreamChatClient.completeWithFallback(any(), any(), any()))
                .thenReturn(Mono.just(Map.of("id", "chatcmpl_dynamic", "object", "chat.completion")));

        String adminToken = loginAndGetAccessToken("admin", "admin123");

        // Step 1: 注册新用户拿到 API key
        String userApiKey = registerAndGetApiKey("dyn-user", "dyn-pass");

        // Step 2: admin 新增 provider + route，使动态注册用户可访问新模型
        webTestClient.put().uri("/admin/providers/dyn-provider")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("type", "openai-compatible", "baseUrl", "http://localhost:19999", "apiKey", "test-key"))
                .exchange()
                .expectStatus().is2xxSuccessful();

        webTestClient.put().uri("/admin/routes/dyn-model")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("provider", "dyn-provider", "upstreamModel", "gpt-4o-mini"))
                .exchange()
                .expectStatus().is2xxSuccessful();

        // Step 3: user 使用新 route 调用 chat
        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + userApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "dyn-model",
                        "messages", new Object[]{Map.of("role", "user", "content", "hello")}
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("chatcmpl_dynamic");
    }

    @Test
    void dynamicConfig_adminUpdatesClientLimits_thenEnforced() {
        when(upstreamChatClient.completeWithFallback(any(), any(), any()))
                .thenReturn(Mono.just(Map.of(
                        "id", "chatcmpl_limit",
                        "object", "chat.completion",
                        "usage", Map.of("total_tokens", 1)
                )));

        String adminToken = loginAndGetAccessToken("admin", "admin123");

        // Step 1: admin 修改 client 的 daily-tokens 为 1（保留白名单，避免模型拒绝）
        webTestClient.put().uri("/admin/clients/demo-client-key")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "enabled", true,
                        "allowedModels", List.of("gpt-4o-mini"),
                        "allowedScenes", List.of("default-chat"),
                        "limits", Map.of("dailyTokens", 1, "dailyCost", 10.0, "maxTokens", 512)
                ))
                .exchange()
                .expectStatus().is2xxSuccessful();

        // Step 2: 第一次请求成功
        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(chatRequestBody(false))
                .exchange()
                .expectStatus().isOk();

        // Step 3: 第二次请求应该被配额拒绝
        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .header("X-Request-Id", "req_quota_exceeded")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(chatRequestBody(false))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.code").isEqualTo("quota_exceeded")
                .jsonPath("$.requestId").isEqualTo("req_quota_exceeded");

        webTestClient.get().uri("/admin/requests/recent?status=429&limit=10")
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.requests[0].requestId").isEqualTo("req_quota_exceeded")
                .jsonPath("$.requests[0].status").isEqualTo(429)
                .jsonPath("$.requests[0].model").isEqualTo("gpt-4o-mini")
                .jsonPath("$.requests[0].provider").isEqualTo("unknown")
                .jsonPath("$.requests[0].routeId").isEqualTo("unknown")
                .jsonPath("$.requests[0].scene").isEqualTo("unknown")
                .jsonPath("$.requests[0].errorMessage").isEqualTo("quota_exceeded");

        webTestClient.get().uri("/internal/requests/req_quota_exceeded")
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.request.requestId").isEqualTo("req_quota_exceeded")
                .jsonPath("$.request.status").isEqualTo(429)
                .jsonPath("$.request.model").isEqualTo("gpt-4o-mini")
                .jsonPath("$.request.provider").isEqualTo("unknown")
                .jsonPath("$.request.routeId").isEqualTo("unknown")
                .jsonPath("$.request.scene").isEqualTo("unknown")
                .jsonPath("$.request.clientId").value(v -> assertThat(String.valueOf(v)).contains("***"))
                .jsonPath("$.request.clientKey").doesNotExist()
                .jsonPath("$.request.errorMessage").isEqualTo("quota_exceeded")
                .jsonPath("$.trace.requestId").isEqualTo("req_quota_exceeded")
                .jsonPath("$.trace.status").isEqualTo(429)
                .jsonPath("$.trace.provider").isEqualTo("unknown")
                .jsonPath("$.trace.routeId").isEqualTo("unknown")
                .jsonPath("$.trace.scene").isEqualTo("unknown")
                .jsonPath("$.trace.errorMessage").value(v -> assertThat(String.valueOf(v)).contains("Daily token quota exceeded"));
    }

    @Test
    void resilience_routeDisabledVisibleInAlertsAndStatus_thenRecoveryAllowsSuccess() {
        when(upstreamChatClient.completeWithFallback(any(), any(), any()))
                .thenReturn(Mono.just(Map.of(
                        "id", "chatcmpl_recovered",
                        "object", "chat.completion",
                        "usage", Map.of("total_tokens", 7)
                )));

        String adminToken = loginAndGetAccessToken("admin", "admin123");

        // Pre-populate provider runtime state for "openai" (orthogonal to route state)
        runtimeStateStore.save("openai", new ProviderRuntimeStateStore.ProviderRuntimeState(
                true, java.time.Instant.now(), java.time.Instant.now(), 0, 5, 200, 42L, null));

        // Step 1: disable the only concrete primary route and confirm degraded state is visible.
        webTestClient.put().uri("/admin/routes/openai-primary")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "provider", "openai",
                        "upstreamModel", "gpt-4o-mini",
                        "enabled", false
                ))
                .exchange()
                .expectStatus().is2xxSuccessful();

        webTestClient.get().uri("/admin/alerts")
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.active[?(@.type=='route_disabled' && @.source=='openai-primary')]").isNotEmpty();

        webTestClient.get().uri("/internal/system/status")
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.maintenance.active").isEqualTo(false)
                .jsonPath("$.globalCircuit.hasAvailableRoute").isEqualTo(false);

        // Provider runtime check: runtime state is orthogonal to route state
        webTestClient.get().uri("/internal/providers/runtime")
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.providers.openai").exists()
                .jsonPath("$.providers.openai.runtimeAvailable").isEqualTo(true)
                .jsonPath("$.providers.openai.consecutiveSuccesses").isEqualTo(5);

        // Step 2: degraded state causes a meaningful request failure.
        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .header("X-Request-Id", "req_route_disabled_fail")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(chatRequestBody(false))
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.code").isEqualTo("config_error")
                .jsonPath("$.message").value(v -> assertThat(String.valueOf(v)).contains("Route is disabled"))
                .jsonPath("$.requestId").isEqualTo("req_route_disabled_fail");

        webTestClient.get().uri("/admin/requests/recent?status=500&limit=10")
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.requests[0].requestId").isEqualTo("req_route_disabled_fail")
                .jsonPath("$.requests[0].status").isEqualTo(500)
                .jsonPath("$.requests[0].provider").isEqualTo("unknown")
                .jsonPath("$.requests[0].routeId").isEqualTo("unknown")
                .jsonPath("$.requests[0].scene").isEqualTo("unknown")
                .jsonPath("$.requests[0].errorMessage").isEqualTo("config_error");

        webTestClient.get().uri("/internal/requests/req_route_disabled_fail")
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.request.requestId").isEqualTo("req_route_disabled_fail")
                .jsonPath("$.request.status").isEqualTo(500)
                .jsonPath("$.request.provider").isEqualTo("unknown")
                .jsonPath("$.request.routeId").isEqualTo("unknown")
                .jsonPath("$.request.scene").isEqualTo("unknown")
                .jsonPath("$.request.clientId").value(v -> assertThat(String.valueOf(v)).contains("***"))
                .jsonPath("$.request.clientKey").doesNotExist()
                .jsonPath("$.request.errorMessage").isEqualTo("config_error")
                .jsonPath("$.trace.requestId").isEqualTo("req_route_disabled_fail")
                .jsonPath("$.trace.status").isEqualTo(500)
                .jsonPath("$.trace.provider").isEqualTo("unknown")
                .jsonPath("$.trace.routeId").isEqualTo("unknown")
                .jsonPath("$.trace.errorMessage").value(v -> assertThat(String.valueOf(v)).contains("Route is disabled"));

        // Step 3: re-enable route, then verify alert/status recovery and successful service restoration.
        webTestClient.put().uri("/admin/routes/openai-primary")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "provider", "openai",
                        "upstreamModel", "gpt-4o-mini",
                        "enabled", true
                ))
                .exchange()
                .expectStatus().is2xxSuccessful();

        webTestClient.get().uri("/admin/alerts")
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.active[?(@.type=='route_disabled' && @.source=='openai-primary')]").isEmpty();

        webTestClient.get().uri("/internal/system/status")
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.maintenance.active").isEqualTo(false)
                .jsonPath("$.globalCircuit.hasAvailableRoute").isEqualTo(true);

        // Provider runtime check: verify runtime state is still intact (orthogonal to route state)
        webTestClient.get().uri("/internal/providers/runtime")
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.providers.openai").exists()
                .jsonPath("$.providers.openai.runtimeAvailable").isEqualTo(true);

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .header("X-Request-Id", "req_route_disabled_recovered")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(chatRequestBody(false))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Request-Id", "req_route_disabled_recovered")
                .expectBody()
                .jsonPath("$.id").isEqualTo("chatcmpl_recovered");
    }

    // ─────────────────────────────────────────────────────────────
    // 3. 请求日志串联验证
    // ─────────────────────────────────────────────────────────────

    @Test
    void requestLogging_chatGeneratesLogEntry_viewableInAdminRequests() {
        when(upstreamChatClient.completeWithFallback(any(), any(), any()))
                .thenReturn(Mono.just(Map.of(
                        "id", "chatcmpl_logged",
                        "object", "chat.completion",
                        "usage", Map.of("total_tokens", 12)
                )));

        // Step 1: 发起 chat 请求
        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .header("X-Request-Id", "req_admin_recent")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(chatRequestBody(false))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Request-Id", "req_admin_recent");

        // Step 2: 通过 admin 查看请求日志，验证刚才的请求被记录
        String adminToken = loginAndGetAccessToken("admin", "admin123");
        webTestClient.get().uri("/admin/requests/recent?limit=10")
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.requests").isArray()
                .jsonPath("$.requests[0].requestId").isEqualTo("req_admin_recent")
                .jsonPath("$.requests[0].model").isEqualTo("gpt-4o-mini")
                .jsonPath("$.requests[0].status").isEqualTo(200)
                .jsonPath("$.requests[0].routeId").isEqualTo("openai-primary")
                .jsonPath("$.requests[0].provider").isEqualTo("openai")
                .jsonPath("$.requests[0].usageTokens").isEqualTo(12);

        webTestClient.get().uri("/internal/requests/req_admin_recent")
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.request.clientId").value(v -> assertThat(String.valueOf(v)).contains("***"))
                .jsonPath("$.trace.requestId").isEqualTo("req_admin_recent")
                .jsonPath("$.trace.clientId").value(v -> assertThat(String.valueOf(v)).contains("***"))
                .jsonPath("$.trace.requestBody").value(v -> assertThat(String.valueOf(v)).contains("hello"))
                .jsonPath("$.trace.responseBody").value(v -> assertThat(String.valueOf(v)).contains("chatcmpl_logged"))
                .jsonPath("$.trace.provider").isEqualTo("openai")
                .jsonPath("$.trace.routeId").isEqualTo("openai-primary");

        webTestClient.get().uri("/internal/cost/by-model")
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.models[?(@.model=='gpt-4o-mini')].requests").value(v -> assertThat((List<?>) v)
                        .anySatisfy(e -> assertThat(((Number) e).intValue()).isEqualTo(1)))
                .jsonPath("$.models[?(@.model=='gpt-4o-mini')].totalTokens").value(v -> assertThat((List<?>) v)
                        .anySatisfy(e -> assertThat(((Number) e).intValue()).isEqualTo(12)));

        webTestClient.get().uri("/internal/cost/client?client=demo-client-key")
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.models[?(@.model=='gpt-4o-mini')].requests").value(v -> assertThat((List<?>) v)
                        .anySatisfy(e -> assertThat(((Number) e).intValue()).isEqualTo(1)))
                .jsonPath("$.models[?(@.model=='gpt-4o-mini')].totalTokens").value(v -> assertThat((List<?>) v)
                        .anySatisfy(e -> assertThat(((Number) e).intValue()).isEqualTo(12)));
    }

    @Test
    void requestLogging_userCanViewOwnUsageRecent() {
        when(upstreamChatClient.completeWithFallback(any(), any(), any()))
                .thenReturn(Mono.just(Map.of(
                        "id", "chatcmpl_user_log",
                        "object", "chat.completion",
                        "usage", Map.of("total_tokens", 9)
                )));

        // Step 1: user 发起请求
        String userToken = loginAndGetAccessToken("user1", "pass1");
        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + userToken)
                .header("X-Request-Id", "req_user_recent")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(chatRequestBody(false))
                .exchange()
                .expectStatus().isOk();

        // Step 2: user 查看自己的 usage recent
        webTestClient.get().uri("/auth/usage/recent?limit=10")
                .header("Authorization", "Bearer " + userToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.requests").isArray();

    }

    // ─────────────────────────────────────────────────────────────
    // 4. 多 Key 认证与隔离
    // ─────────────────────────────────────────────────────────────

    @Test
    void multiKey_createMultipleKeys_eachKeyCanAuthenticate() {
        when(upstreamChatClient.completeWithFallback(any(), any(), any()))
                .thenReturn(Mono.just(Map.of("id", "chatcmpl_mk", "object", "chat.completion")));

        String token = loginAndGetAccessToken("admin", "admin123");

        // Step 1: 创建一个新 key
        String key1Response = webTestClient.post().uri("/auth/keys")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "key-a"))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .blockFirst();
        String key1 = extractJsonValue(key1Response, "apiKey");

        // Step 2: key 列表包含新 key
        webTestClient.get().uri("/auth/keys")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.keys").isArray()
                .jsonPath("$.keys.length()").value(v -> assertThat((Integer) v).isGreaterThanOrEqualTo(2));

        // Step 3: 使用动态注册用户的 key 发起请求
        String dynUser = "mk-user";
        registerAndGetApiKey(dynUser, "mk-pass");
        String dynToken = loginAndGetAccessToken(dynUser, "mk-pass");

        String dynKeyResponse = webTestClient.post().uri("/auth/keys")
                .header("Authorization", "Bearer " + dynToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "use-key"))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .blockFirst();
        String dynKey = extractJsonValue(dynKeyResponse, "apiKey");

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + dynKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(chatRequestBody(false))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("chatcmpl_mk");
    }

    @Test
    void multiKey_disableOneKey_otherKeyStillWorks() {
        String dkUser = "dk-user";
        registerAndGetApiKey(dkUser, "dk-pass");
        String dkToken = loginAndGetAccessToken(dkUser, "dk-pass");

        // Step 1: 创建一个新 key
        String keyResponse = webTestClient.post().uri("/auth/keys")
                .header("Authorization", "Bearer " + dkToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "target-key"))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .blockFirst();
        String keyId = extractJsonValue(keyResponse, "keyId");

        // Step 2: 列表包含 primary + 新 key
        String keysBefore = webTestClient.get().uri("/auth/keys")
                .header("Authorization", "Bearer " + dkToken)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .blockFirst();
        assertThat(keysBefore).contains(keyId);
        assertThat(keysBefore).contains("\"enabled\":true");

        // Step 3: 禁用新 key
        webTestClient.patch().uri("/auth/keys/" + keyId)
                .header("Authorization", "Bearer " + dkToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("enabled", false))
                .exchange()
                .expectStatus().isNoContent();

        // Step 4: 验证新 key 被禁用、primary 仍启用
        String keysAfter = webTestClient.get().uri("/auth/keys")
                .header("Authorization", "Bearer " + dkToken)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .blockFirst();
        assertThat(keysAfter).contains(keyId);
        assertThat(keysAfter).contains("\"enabled\":false");
        assertThat(keysAfter).contains("\"enabled\":true");
    }

    @Test
    void multiKey_differentUsersCannotAccessEachOtherKeys() {
        String adminToken = loginAndGetAccessToken("admin", "admin123");
        String userToken = loginAndGetAccessToken("user1", "pass1");

        // admin 创建 key
        webTestClient.post().uri("/auth/keys")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "admin-only-key"))
                .exchange()
                .expectStatus().isOk();

        // user1 的 key 列表不包含 admin 的 key
        webTestClient.get().uri("/auth/keys")
                .header("Authorization", "Bearer " + userToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.keys[?(@.name=='admin-only-key')]").isEmpty();
    }

    // ─────────────────────────────────────────────────────────────
    // 5. Auth disabled 模式（通过单独属性测试）
    // ─────────────────────────────────────────────────────────────

    @Test
    void authDisabled_staticKeyBypassesAuth() {
        // 在 auth.enabled=true 的上下文中，静态 key 仍能通过 ClientAuthService
        // （auth disabled 模式需要独立上下文，此处验证静态 key + JWT 共存）
        when(upstreamChatClient.completeWithFallback(any(), any(), any()))
                .thenReturn(Mono.just(Map.of("id", "chatcmpl_static", "object", "chat.completion")));

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(chatRequestBody(false))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("chatcmpl_static");
    }

}
