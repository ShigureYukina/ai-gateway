package io.gateway.oss.admin.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;


/**
 * 测试不同的注册模式（registration-mode）行为。
 * 这个测试类使用 gateway.auth.registration-mode=disabled，
 * 确保公开注册被拒绝，但同时其他认证/登录接口仍正常工作。
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
        "security.password.allow-plaintext=true",
        "gateway.auth.enabled=true",
        "gateway.auth.jwt.secret=super-secret-key-that-is-at-least-32-chars",
        "gateway.auth.jwt.access-expiration=5s",
        "gateway.auth.jwt.refresh-expiration=60s",
        "gateway.auth.users.admin.password=admin123",
        "gateway.auth.users.admin.client-id=demo-client-key",
        "gateway.auth.users.admin.role=admin",
        "gateway.auth.users.user.password=testpass",
        "gateway.auth.registration-mode=disabled",
        "gateway.clients.demo-client-key.enabled=true",
        "gateway.clients.demo-client-key.allowed-models[0]=gpt-4o-mini",
        "gateway.clients.demo-client-key.defaults.scene=default-chat",
        "gateway.clients.demo-client-key.capabilities.streaming=true",
        "gateway.clients.demo-client-key.limits.max-tokens=512",
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
class AuthRegistrationModeTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void shouldRejectRegistrationWhenDisabled() {
        webTestClient.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "username", "newuser",
                        "password", "newpass123"
                ))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("registration_disabled");
    }

    @Test
    void shouldAllowLoginWhenRegistrationDisabled() {
        webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "username", "admin",
                        "password", "admin123"
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.accessToken").exists();
    }

    @Test
    void shouldRejectRegistrationWithInvalidUsername() {
        // Empty username triggers @NotBlank validation before mode check -> 400
        webTestClient.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "username", "",
                        "password", "short"
                ))
                .exchange()
                .expectStatus().isBadRequest();
    }
}
