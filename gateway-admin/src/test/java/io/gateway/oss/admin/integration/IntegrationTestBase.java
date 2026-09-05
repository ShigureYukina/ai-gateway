package io.gateway.oss.admin.integration;

import io.gateway.oss.core.util.BatchFlusher;
import io.gateway.oss.core.upstream.UpstreamChatClient;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

/**
 * 集成测试共享基类：统一属性、mock、helper 方法。
 */
@SpringBootTest
@AutoConfigureWebTestClient
@ActiveProfiles({"test", "test-redis"})
@TestPropertySource(properties = {
        "gateway.auth.enabled=true",
        // IT 的用例意图是"自注册用户可直接调用"，默认 restricted 模式会给注册用户
        // 套上注册模板 client 配置（allowedModels 为空 → 任何模型 403 forbidden_model）
        "gateway.auth.registration-mode=open",
        "gateway.auth.jwt.secret=super-secret-key-that-is-at-least-32-chars",
        "gateway.auth.jwt.access-expiration=30s",
        "gateway.auth.jwt.refresh-expiration=60s",
        "gateway.auth.users.admin.password=admin123",
        "gateway.auth.users.admin.client-id=demo-client-key",
        "gateway.auth.users.admin.role=admin",
        "gateway.auth.users.user1.password=pass1",
        "gateway.auth.users.user1.client-id=demo-client-key",
        "gateway.auth.users.user1.role=user",
        "gateway.clients.demo-client-key.enabled=true",
        "gateway.clients.demo-client-key.allowed-models[0]=gpt-4o-mini",
        "gateway.clients.demo-client-key.allowed-scenes[0]=default-chat",
        "gateway.clients.demo-client-key.defaults.scene=default-chat",
        "gateway.clients.demo-client-key.defaults.temperature=0.7",
        "gateway.clients.demo-client-key.defaults.max-tokens=256",
        "gateway.clients.demo-client-key.capabilities.streaming=true",
        "gateway.clients.demo-client-key.limits.max-tokens=512",
        "gateway.providers.openai.base-url=http://localhost:18080",
        "gateway.providers.openai.api-key=test-upstream-key",
        "gateway.routes.gpt-4o-mini.scene=default-chat",
        "gateway.routes.openai-primary.provider=openai",
        "gateway.routes.openai-primary.upstream-model=gpt-4o-mini",
        "gateway.scenes.default-chat.primary-route=openai-primary",
        "gateway.pricing.default.unit-price=0.0001",
        "gateway.tracing.enabled=true",
        "gateway.security.block-internal-urls=false"
})
public abstract class IntegrationTestBase extends RedisIntegrationTestSupport {

    @Autowired
    protected WebTestClient webTestClient;

    @MockBean
    protected UpstreamChatClient upstreamChatClient;

    @Autowired
    private BatchFlusher batchFlusher;

    @BeforeEach
    void enableSyncBatchFlusher() {
        // 全量套件并行负载下 5s 默认响应超时偶发不够，统一放宽到 30s
        webTestClient = webTestClient.mutate().responseTimeout(java.time.Duration.ofSeconds(30)).build();

        batchFlusher.setSynchronous(true);
    }

    protected String loginAndGetAccessToken(String username, String password) {
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

    protected String loginAndGetRefreshToken(String username, String password) {
        String responseBody = webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", username, "password", password))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .blockFirst();
        return extractJsonValue(responseBody, "refreshToken");
    }

    protected String extractJsonValue(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int start = json.indexOf(pattern) + pattern.length();
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }

    protected Map<String, Object> chatRequestBody(String model, boolean stream) {
        return Map.of(
                "model", model,
                "messages", new Object[]{Map.of("role", "user", "content", "hello")},
                "stream", stream,
                "temperature", 0.7,
                "max_tokens", 128
        );
    }

    protected Map<String, Object> chatRequestBody(boolean stream) {
        return chatRequestBody("gpt-4o-mini", stream);
    }

    protected String registerAndGetApiKey(String username, String password) {
        String responseBody = webTestClient.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", username, "password", password))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .blockFirst();
        return extractJsonValue(responseBody, "apiKey");
    }

    protected String loginAsAdmin() {
        return loginAndGetAccessToken("admin", "admin123");
    }
}
