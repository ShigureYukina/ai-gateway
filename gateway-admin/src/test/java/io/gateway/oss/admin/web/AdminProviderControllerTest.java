package io.gateway.oss.admin.web;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.admin.sync.ProviderModelCatalogService;
import io.gateway.oss.core.upstream.ProviderRuntimeStateStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SpringBootTest
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration-test",
        "spring.datasource.url=jdbc:h2:mem:provider-test;DB_CLOSE_DELAY=-1",
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
        "gateway.scenes.default-chat.primary-route=openai-primary"
})
class AdminProviderControllerTest {

    private static DisposableServer upstreamServer;
    private static String upstreamBaseUrl;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private WebTestCleanupSupport cleanup;

    @Autowired
    private ProviderRuntimeStateStore providerRuntimeStateStore;

    @Autowired
    private ProviderModelCatalogService providerModelCatalogService;

    @Autowired
    private GatewayProperties gatewayProperties;

    private String adminAccessToken;

    @BeforeAll
    static void startUpstreamServer() {
        upstreamServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.get("/v1/models", (request, response) -> response
                        .status(200)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .sendString(Mono.just("""
                                {"data":[{"id":"gpt-4.1"},{"id":"gpt-4o-mini"}]}
                                """))
                        .then()))
                .bindNow();
        upstreamBaseUrl = "http://localhost:" + upstreamServer.port();
    }

    @AfterAll
    static void stopUpstreamServer() {
        if (upstreamServer != null) {
            upstreamServer.disposeNow();
        }
    }

    @DynamicPropertySource
    static void dynamicProps(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.openai.base-url", () -> upstreamBaseUrl);
    }

    @BeforeEach
    void setUp() {
        cleanup.resetState();
        adminAccessToken = loginAndGetAccessToken("admin", "admin123");
    }

    @Test
    void shouldReturnProvidersListWithMaskedKeys() {
        webTestClient.put()
                .uri("/admin/providers/multi-key-provider")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "type", "openai-compatible",
                        "baseUrl", upstreamBaseUrl,
                        "apiKey", "secret-provider-key",
                        "timeoutSeconds", 15,
                        "enabled", true,
                        "models", List.of("gpt-4o-mini")
                ))
                .exchange()
                .expectStatus().isCreated();

        webTestClient.get()
                .uri("/admin/providers")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.generatedAt").exists()
                .jsonPath("$.providers.openai").exists()
                .jsonPath("$.providers.openai.apiKey").isEqualTo("****-key")
                .jsonPath("$.providers['multi-key-provider'].apiKey").isEqualTo("****-key")
                .jsonPath("$.providers['multi-key-provider'].baseUrl").isEqualTo(upstreamBaseUrl);
    }

    @Test
    void shouldReturn401WhenListingProvidersWithoutAuth() {
        webTestClient.get()
                .uri("/admin/providers")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void shouldReturnProviderRuntimeStates() {
        providerRuntimeStateStore.save("openai", new ProviderRuntimeStateStore.ProviderRuntimeState(
                false,
                Instant.parse("2026-06-04T10:15:30Z"),
                Instant.parse("2026-06-04T10:10:30Z"),
                2,
                0,
                503,
                120L,
                "upstream unavailable"
        ));

        webTestClient.get()
                .uri("/admin/providers/runtime")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.generatedAt").exists()
                .jsonPath("$.providers.openai.runtimeAvailable").isEqualTo(false)
                .jsonPath("$.providers.openai.httpStatus").isEqualTo(503)
                .jsonPath("$.providers.openai.latencyMs").isEqualTo(120)
                .jsonPath("$.providers.openai.reason").isEqualTo("upstream unavailable");
    }

    @Test
    void shouldReturnProviderModels() {
        providerModelCatalogService.replaceSnapshot(
                Map.of("openai", Set.of("gpt-4.1", "gpt-4o-mini")),
                Instant.parse("2026-06-04T11:00:00Z")
        );

        webTestClient.get()
                .uri("/admin/providers/openai/models")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.provider").isEqualTo("openai")
                .jsonPath("$.models.length()").isEqualTo(2)
                .jsonPath("$.models[0]").isEqualTo("gpt-4.1")
                .jsonPath("$.models[1]").isEqualTo("gpt-4o-mini");
    }

    @Test
    void shouldCreateNewProvider() {
        webTestClient.put()
                .uri("/admin/providers/new-provider")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "type", "openai-compatible",
                        "baseUrl", upstreamBaseUrl,
                        "apiKey", "brand-new-key",
                        "timeoutSeconds", 20,
                        "enabled", true,
                        "models", List.of("gpt-4o-mini", "gpt-4.1")
                ))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.type").isEqualTo("openai-compatible")
                .jsonPath("$.baseUrl").isEqualTo(upstreamBaseUrl)
                .jsonPath("$.apiKey").isEqualTo("brand-new-key")
                .jsonPath("$.models.length()").isEqualTo(2);
    }

    @Test
    void shouldUpdateExistingProvider() {
        webTestClient.put()
                .uri("/admin/providers/openai")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "baseUrl", upstreamBaseUrl,
                        "apiKey", "updated-openai-key",
                        "timeoutSeconds", 45,
                        "enabled", false,
                        "models", List.of("gpt-4.1")
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.type").isEqualTo("openai-compatible")
                .jsonPath("$.baseUrl").isEqualTo(upstreamBaseUrl)
                .jsonPath("$.apiKey").isEqualTo("updated-openai-key")
                .jsonPath("$.enabled").isEqualTo(false)
                .jsonPath("$.models[0]").isEqualTo("gpt-4.1");
    }

    @Test
    void shouldDeleteProvider() {
        webTestClient.put()
                .uri("/admin/providers/delete-me")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "type", "openai-compatible",
                        "baseUrl", upstreamBaseUrl,
                        "apiKey", "delete-me-key",
                        "timeoutSeconds", 10,
                        "enabled", true,
                        "models", List.of("gpt-4o-mini")
                ))
                .exchange()
                .expectStatus().isCreated();

        webTestClient.delete()
                .uri("/admin/providers/delete-me")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isNoContent();

        webTestClient.get()
                .uri("/admin/providers")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.providers['delete-me']").doesNotExist();
    }

    @Test
    void shouldTestProviderConnectivity() {
        webTestClient.post()
                .uri("/admin/providers/openai/test")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("ok")
                .jsonPath("$.httpStatus").isEqualTo(200)
                .jsonPath("$.latencyMs").isNumber()
                .jsonPath("$.error").doesNotExist();
    }

    private String loginAndGetAccessToken(String username, String password) {
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
