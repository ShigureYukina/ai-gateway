package io.gateway.oss.admin.web;

import io.gateway.oss.admin.AdminTestConfiguration;
import io.gateway.oss.core.config.GatewayProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

/**
 * {@link AdminClientController} 的集成测试。
 * 覆盖客户端 CRUD：列表、创建、更新、删除、鉴权。
 */
@SpringBootTest(classes = AdminTestConfiguration.class)
@AutoConfigureWebTestClient
class AdminClientControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private GatewayProperties properties;

    private static final String ADMIN_AUTH = "Bearer valid-admin-token";

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry reg) {
        reg.add("spring.autoconfigure.exclude", () ->
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration");
        reg.add("gateway.shared-state.backend", () -> "in_memory");
        reg.add("gateway.auth.enabled", () -> "true");
        reg.add("gateway.auth.jwt.secret", () -> "super-secret-key-that-is-at-least-32-chars");
        reg.add("gateway.auth.jwt.access-expiration", () -> "5s");
        reg.add("gateway.auth.jwt.refresh-expiration", () -> "60s");
        reg.add("gateway.auth.users.admin.password", () -> "admin123");
        reg.add("gateway.auth.users.admin.client-id", () -> "demo-client-key");
        reg.add("gateway.auth.users.admin.role", () -> "admin");
        reg.add("gateway.clients.demo-client-key.enabled", () -> "true");
        reg.add("gateway.clients.demo-client-key.allowed-models[0]", () -> "gpt-4o-mini");
        reg.add("gateway.clients.demo-client-key.allowed-scenes[0]", () -> "default-chat");
        reg.add("gateway.clients.demo-client-key.defaults.scene", () -> "default-chat");
        reg.add("gateway.clients.demo-client-key.defaults.temperature", () -> "0.7");
        reg.add("gateway.clients.demo-client-key.defaults.max-tokens", () -> "256");
        reg.add("gateway.clients.demo-client-key.capabilities.streaming", () -> "true");
        reg.add("gateway.clients.demo-client-key.limits.max-tokens", () -> "4096");
        reg.add("gateway.clients.demo-client-key.limits.requests-per-window", () -> "1000");
        reg.add("gateway.clients.demo-client-key.limits.window", () -> "1m");
    }

    @BeforeAll
    static void init() {
        // Use a global mock auth token so the controller can authenticate
        System.setProperty("GATEWAY_JWT_SECRET", "super-secret-key-that-is-at-least-32-chars");
        System.setProperty("GATEWAY_ADMIN_PASSWORD", "admin123");
        System.setProperty("GATEWAY_USER_PASSWORD", "user123");
    }

    // ─── GET /admin/clients ───

    @Test
    void shouldListClients() {
        // First login to get a real token
        String token = loginAsAdmin();

        webTestClient.get().uri("/admin/clients")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.clients").isMap()
                .jsonPath("$.generatedAt").isNotEmpty();
    }

    @Test
    void shouldRejectListWithoutAuth() {
        webTestClient.get().uri("/admin/clients")
                .exchange()
                .expectStatus().is4xxClientError();
    }

    // ─── PUT /admin/clients/{key} ───

    @Test
    void shouldCreateNewClient() {
        String token = loginAsAdmin();
        String newKey = "test-new-client-" + System.currentTimeMillis();

        webTestClient.put().uri("/admin/clients/{key}", newKey)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "enabled", true,
                        "allowedModels", java.util.List.of("gpt-4o-mini"),
                        "allowedScenes", java.util.List.of("default-chat"),
                        "defaults", Map.of("scene", "default-chat", "temperature", 0.5, "maxTokens", 128),
                        "capabilities", Map.of("streaming", true),
                        "limits", Map.of("maxTokens", 2048, "requestsPerWindow", 500, "window", "PT1M")
                ))
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    void shouldUpdateExistingClient() {
        String token = loginAsAdmin();

        webTestClient.put().uri("/admin/clients/{key}", "demo-client-key")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "enabled", true,
                        "allowedModels", java.util.List.of("gpt-4o-mini"),
                        "allowedScenes", java.util.List.of("default-chat"),
                        "defaults", Map.of("scene", "default-chat", "temperature", 0.8, "maxTokens", 512),
                        "capabilities", Map.of("streaming", true),
                        "limits", Map.of("maxTokens", 4096, "requestsPerWindow", 2000, "window", "PT1M")
                ))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void shouldRejectPutWithoutAuth() {
        webTestClient.put().uri("/admin/clients/test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("enabled", true))
                .exchange()
                .expectStatus().is4xxClientError();
    }

    // ─── DELETE /admin/clients/{key} ───

    @Test
    void shouldDeleteExistingClient() {
        String token = loginAsAdmin();
        String keyToDelete = "temp-delete-key-" + System.currentTimeMillis();

        // First create
        webTestClient.put().uri("/admin/clients/{key}", keyToDelete)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "enabled", true,
                        "allowedModels", java.util.List.of("gpt-4o-mini"),
                        "allowedScenes", java.util.List.of("default-chat"),
                        "capabilities", Map.of("streaming", false),
                        "limits", Map.of("requestsPerWindow", 100, "window", "PT1M")
                ))
                .exchange()
                .expectStatus().isCreated();

        // Then delete
        webTestClient.delete().uri("/admin/clients/{key}", keyToDelete)
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void shouldReturn404ForDeletingNonExistentClient() {
        String token = loginAsAdmin();

        webTestClient.delete().uri("/admin/clients/{key}", "non-existent-client")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void shouldRejectDeleteWithoutAuth() {
        webTestClient.delete().uri("/admin/clients/test-key")
                .exchange()
                .expectStatus().is4xxClientError();
    }

    // ─── helper ───

    private String loginAsAdmin() {
        var result = webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "admin", "password", "admin123"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        return (String) result.get("accessToken");
    }
}
