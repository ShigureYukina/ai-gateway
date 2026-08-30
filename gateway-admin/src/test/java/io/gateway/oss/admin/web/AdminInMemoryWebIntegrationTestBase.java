package io.gateway.oss.admin.web;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import io.gateway.oss.core.security.JwtService;

import java.util.List;

/**
 * 复用 Flyway + H2(in-memory) + WebTestClient 的全上下文测试配置，
 * 以提升 Spring context cache 命中并收敛重复登录/清理逻辑。
 */
@SpringBootTest
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration-test",
        "spring.datasource.url=jdbc:h2:mem:admin-web-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
        "gateway.shared-state.backend=in_memory",
        "gateway.auth.enabled=true",
        "gateway.auth.jwt.secret=super-secret-key-that-is-at-least-32-chars",
        "gateway.auth.jwt.access-expiration=300s",
        "gateway.auth.jwt.refresh-expiration=60s",
        "gateway.auth.users.admin.password=admin123",
        "gateway.auth.users.admin.client-id=demo-client-key",
        "gateway.auth.users.admin.role=admin",
        "gateway.clients.demo-client-key.enabled=true",
        "gateway.clients.demo-client-key.allowed-models[0]=gpt-4o-mini",
        "gateway.clients.demo-client-key.allowed-scenes[0]=default-chat",
        "gateway.clients.demo-client-key.defaults.scene=default-chat",
        "gateway.clients.demo-client-key.defaults.temperature=0.7",
        "gateway.clients.demo-client-key.defaults.max-tokens=256",
        "gateway.clients.demo-client-key.capabilities.streaming=true",
        "gateway.providers.openai.base-url=http://localhost:18080",
        "gateway.providers.openai.api-key=upstream-demo-key",
        "gateway.providers.openai.models[0]=gpt-4o-mini",
        "gateway.routes.gpt-4o-mini.scene=default-chat",
        "gateway.routes.openai-primary.provider=openai",
        "gateway.routes.openai-primary.upstream-model=gpt-4o-mini",
        "gateway.scenes.default-chat.primary-route=openai-primary",
        "gateway.sync.models-dev.endpoint=http://127.0.0.1:1/api.json",
        "gateway.sync.models-dev.timeout=500ms"
})
abstract class AdminInMemoryWebIntegrationTestBase {

    @Autowired
    protected WebTestClient webTestClient;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private WebTestCleanupSupport cleanup;

    protected String adminAccessToken;

    @BeforeEach
    void resetStateAndLoginAdmin() {
        // 全量套件并行负载下 5s 默认响应超时偶发不够（发布链路含别名锁 + 补偿装配），
        // 统一放宽到 30s
        webTestClient = webTestClient.mutate().responseTimeout(java.time.Duration.ofSeconds(30)).build();
        cleanup.resetState();
        adminAccessToken = jwtService.generateAccessToken("admin", List.of("gpt-4o-mini"), "admin");
    }

    protected String loginAndGetAccessToken(String username, String password) {
        return webTestClient.post()
                .uri("/auth/login")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("username", username, "password", password))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .map(responseBody -> {
                    String marker = "\"accessToken\":\"";
                    int start = responseBody.indexOf(marker) + marker.length();
                    int end = responseBody.indexOf('"', start);
                    return responseBody.substring(start, end);
                })
                .blockFirst();
    }
}
