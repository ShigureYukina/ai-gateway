package io.gateway.oss.core.security;

import io.gateway.oss.core.config.InMemoryConfigStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

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
class RefreshTokenBlacklistTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private InMemoryConfigStore configStore;

    @BeforeEach
    void resetState() {
        configStore.clear();
    }

    @Test
    void shouldBlacklistAndRejectRefreshToken() {
        String loginBody = login("admin", "admin123");
        String refreshToken = extractJsonValue(loginBody, "refreshToken");

        webTestClient.post().uri("/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("refreshToken", refreshToken))
                .exchange()
                .expectStatus().isNoContent();

        webTestClient.post().uri("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("refreshToken", refreshToken))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("token_revoked");
    }

    @Test
    void shouldAllowRefreshBeforeLogout() {
        String loginBody = login("admin", "admin123");
        String refreshToken = extractJsonValue(loginBody, "refreshToken");

        webTestClient.post().uri("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("refreshToken", refreshToken))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.accessToken").isNotEmpty()
                .jsonPath("$.refreshToken").isNotEmpty();
    }

    @Test
    void shouldRejectSecondRefreshWithSameToken() {
        String loginBody = login("admin", "admin123");
        String refreshToken = extractJsonValue(loginBody, "refreshToken");

        webTestClient.post().uri("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("refreshToken", refreshToken))
                .exchange()
                .expectStatus().isOk();

        webTestClient.post().uri("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("refreshToken", refreshToken))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("token_revoked");
    }

    @Test
    void shouldReturn204OnLogout() {
        String loginBody = login("admin", "admin123");
        String refreshToken = extractJsonValue(loginBody, "refreshToken");

        webTestClient.post().uri("/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("refreshToken", refreshToken))
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void shouldReturn401OnLogoutWithInvalidToken() {
        webTestClient.post().uri("/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("refreshToken", "garbage-token"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_token");
    }

    private String login(String username, String password) {
        return webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", username, "password", password))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .blockFirst();
    }

    private String extractJsonValue(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int start = json.indexOf(pattern) + pattern.length();
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }
}
