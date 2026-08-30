package io.gateway.oss.admin.web;

import io.gateway.oss.core.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;

/**
 * 复用无 Flyway + H2(in-memory) + WebTestClient 的全上下文测试配置，
 * 统一测试状态清理与静态用户 JWT 生成，提升 Spring context cache 命中。
 */
@SpringBootTest
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
        "gateway.shared-state.backend=in_memory",
        "gateway.security.block-internal-urls=true",
        "gateway.auth.enabled=true",
        "gateway.auth.jwt.secret=super-secret-key-that-is-at-least-32-chars",
        "gateway.auth.jwt.access-expiration=60s",
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
        "gateway.limit.window=5m",
        "gateway.webhook.dispatcher.enabled=false",
        "gateway.tracing.enabled=true"
})
abstract class AdminNoFlywayWebIntegrationTestBase {

    @Autowired
    protected WebTestClient webTestClient;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private WebTestCleanupSupport cleanup;

    protected String adminAccessToken;

    protected String userAccessToken;

    @BeforeEach
    protected void resetStateAndCreateTokens() {
        // 与 AdminInMemoryWebIntegrationTestBase 对齐：放宽响应超时应对全量负载
        webTestClient = webTestClient.mutate().responseTimeout(java.time.Duration.ofSeconds(30)).build();
        cleanup.resetState();
        adminAccessToken = jwtService.generateAccessToken("admin", List.of("gpt-4o-mini"), "admin");
        userAccessToken = jwtService.generateAccessToken("user1", List.of("gpt-4o-mini"), "user");
    }

    protected String loginAndGetAccessToken(String username, String password) {
        String responseBody = webTestClient.post()
                .uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", username, "password", password))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .blockFirst();

        String marker = "\"accessToken\":\"";
        int start = responseBody.indexOf(marker) + marker.length();
        int end = responseBody.indexOf('"', start);
        return responseBody.substring(start, end);
    }
}
