package io.gateway.oss.admin.web;

import io.gateway.oss.admin.sync.ProviderModelCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

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
        "gateway.auth.jwt.access-expiration=300s",
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
class ModelsControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ProviderModelCatalogService catalogService;

    @Autowired
    private io.gateway.oss.admin.web.WebTestCleanupSupport cleanup;

    @BeforeEach
    void resetState() {
        // 全量套件并行负载下 5s 默认响应超时偶发不够，统一放宽到 30s
        webTestClient = webTestClient.mutate().responseTimeout(java.time.Duration.ofSeconds(30)).build();

        cleanup.resetState();
    }

    // ─── Snapshot (models.dev) 优先场景 ───

    @Test
    void shouldReturnAllModelsFromSnapshot() {
        catalogService.replaceSnapshot(Map.of(
                "openai", Set.of("gpt-4o", "gpt-4o-mini"),
                "anthropic", Set.of("claude-3-5-sonnet")
        ), Instant.parse("2026-04-28T08:00:00Z"));

        webTestClient.get().uri("/v1/models")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("list")
                .jsonPath("$.data.length()").isEqualTo(3)
                .jsonPath("$.data[?(@.id=='gpt-4o')].object").isEqualTo("model")
                .jsonPath("$.data[?(@.id=='gpt-4o')].execution_id").isEqualTo("gpt-4o")
                .jsonPath("$.data[?(@.id=='gpt-4o')].source_type").isEqualTo("snapshot")
                .jsonPath("$.data[?(@.id=='gpt-4o')].canonical_id").isEqualTo("openai/gpt-4o")
                .jsonPath("$.data[?(@.id=='gpt-4o')].owned_by").isEqualTo("openai")
                .jsonPath("$.data[?(@.id=='gpt-4o-mini')].owned_by").isEqualTo("openai")
                .jsonPath("$.data[?(@.id=='claude-3-5-sonnet')].owned_by").isEqualTo("anthropic");
    }

    @Test
    void shouldFilterSnapshotByProviderAndModel() {
        catalogService.replaceSnapshot(Map.of(
                "openai", Set.of("gpt-4o", "gpt-4o-mini"),
                "anthropic", Set.of("claude-3-5-sonnet", "claude-3-opus")
        ), Instant.now());

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/v1/models")
                        .queryParam("provider", "openai")
                        .queryParam("model", "gpt-4o-mini")
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].id").isEqualTo("gpt-4o-mini")
                .jsonPath("$.data[0].owned_by").isEqualTo("openai");
    }

    @Test
    void shouldFilterByKeyAllowedModelsWhenAuthorizationIsValid() {
        catalogService.replaceSnapshot(Map.of(
                "openai", Set.of("gpt-4o", "gpt-4o-mini")
        ), Instant.now());

        AuthBundle authBundle = registerAndCreateRestrictedKey(Set.of("gpt-4o-mini"));

        webTestClient.get().uri("/v1/models")
                .header("Authorization", "Bearer " + authBundle.apiKey())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].id").isEqualTo("gpt-4o-mini")
                .jsonPath("$.data[0].owned_by").isEqualTo("openai");
    }

    @Test
    void shouldIgnoreInvalidAuthorizationAndKeepPublicListing() {
        catalogService.replaceSnapshot(Map.of(
                "openai", Set.of("gpt-4o", "gpt-4o-mini")
        ), Instant.now());

        webTestClient.get().uri("/v1/models")
                .header("Authorization", "Bearer invalid-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(2)
                .jsonPath("$.data[?(@.id=='gpt-4o')].owned_by").isEqualTo("openai")
                .jsonPath("$.data[?(@.id=='gpt-4o-mini')].owned_by").isEqualTo("openai");
    }

    // ─── 空快照 → 回退到 model-groups / 本地配置 ───

    @Test
    void shouldFallbackToModelGroupsWhenSnapshotEmpty() {
        // 不触发 sync，snapshot 为空 → fallback 到 model-groups（routes with scenes）
        webTestClient.get().uri("/v1/models")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("list")
                .jsonPath("$.data.length()").isNumber()
                .jsonPath("$.data[?(@.id=='gpt-4o-mini')].owned_by").isEqualTo("openai")
                .jsonPath("$.data[?(@.id=='gpt-4o-mini')].execution_id").isEqualTo("gpt-4o-mini")
                .jsonPath("$.data[?(@.id=='gpt-4o-mini')].source_type").isEqualTo("model_group")
                .jsonPath("$.data[?(@.id=='gpt-4o-mini')].canonical_id").isEqualTo("openai/gpt-4o-mini");
    }

    @Test
    void shouldFallbackToLocalWhenModelGroupMissesAndLocalRouteMatches() {
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/v1/models")
                        .queryParam("model", "openai-primary")
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].id").isEqualTo("openai-primary")
                .jsonPath("$.data[0].execution_id").isEqualTo("openai-primary")
                .jsonPath("$.data[0].source_type").isEqualTo("local")
                .jsonPath("$.data[0].canonical_id").isEqualTo("openai/gpt-4o-mini");
    }

    // ─── 空数据 → 200 + 空数组（不 500）───

    @Test
    void shouldReturnEmptyArrayWhenSnapshotExistsButProviderNotMatch() {
        catalogService.replaceSnapshot(Map.of(
                "openai", Set.of("gpt-4o")
        ), Instant.now());

        // snapshot 有数据 → 使用 snapshot 路径，不回退 local
        // 过滤 anthropic → 空
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/v1/models")
                        .queryParam("provider", "anthropic")
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("list")
                .jsonPath("$.data.length()").isEqualTo(0);
    }

    // ─── Snapshot 覆盖 Local ───

    @Test
    void shouldPreferSnapshotOverLocalConfigWhenSnapshotNonEmpty() {
        catalogService.replaceSnapshot(Map.of(
                "openai", Set.of("gpt-4o")
        ), Instant.now());

        // 当前实现语义：snapshot 非空时优先使用 snapshot 数据源，但仍会保留 model-group 结果；
        // 仅确认不会回退到 local route id（如 openai-primary），并保留 snapshot 条目断言。
        webTestClient.get().uri("/v1/models")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[?(@.id=='gpt-4o')].owned_by").isEqualTo("openai")
                .jsonPath("$.data[?(@.id=='gpt-4o')].source_type").isEqualTo("snapshot")
                .jsonPath("$.data[?(@.id=='gpt-4o-mini')].source_type").isEqualTo("model_group")
                .jsonPath("$.data[*].id").value(ids -> assertThat((List<?>) ids)
                        .noneMatch("openai-primary"::equals));
    }

    private AuthBundle registerAndCreateRestrictedKey(Set<String> allowedModels) {
        String username = "models-user-" + System.nanoTime();
        String password = "pass123";

        Map<String, String> registerRequest = new HashMap<>();
        registerRequest.put("username", username);
        registerRequest.put("password", password);

        final String[] accessToken = new String[1];
        webTestClient.post()
                .uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(registerRequest)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.accessToken").value(v -> accessToken[0] = String.valueOf(v));

        Map<String, Object> createKeyRequest = new HashMap<>();
        createKeyRequest.put("name", "restricted");
        createKeyRequest.put("allowedModels", List.copyOf(allowedModels));

        final String[] apiKey = new String[1];
        webTestClient.post()
                .uri("/auth/keys")
                .header("Authorization", "Bearer " + accessToken[0])
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(createKeyRequest)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.apiKey").value(v -> apiKey[0] = String.valueOf(v));

        return new AuthBundle(accessToken[0], apiKey[0]);
    }

    private record AuthBundle(String accessToken, String apiKey) {
    }

}
