package io.gateway.oss.admin.web;

import io.gateway.oss.admin.repository.WebhookDeliveryLogRepository;
import io.gateway.oss.admin.repository.WebhookEndpointRepository;
import org.awaitility.Awaitility;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration-test",
        "spring.datasource.url=jdbc:h2:mem:webhook-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
        "gateway.shared-state.backend=in_memory",
        "security.password.allow-plaintext=true",
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
        "gateway.scenes.default-chat.primary-route=openai-primary"
})
class AdminWebhookControllerTest {

    private static DisposableServer webhookServer;
    private static String webhookUrl;
    private static String webhookFailUrl;
    private static final AtomicInteger hits = new AtomicInteger();
    private static final AtomicInteger failHits = new AtomicInteger();
    private static final AtomicReference<String> lastPayload = new AtomicReference<>();
    private static final AtomicReference<String> lastTimestamp = new AtomicReference<>();
    private static final AtomicReference<String> lastSignature = new AtomicReference<>();

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private WebhookEndpointRepository webhookEndpointRepository;

    @Autowired
    private WebhookDeliveryLogRepository webhookDeliveryLogRepository;

    private String adminAccessToken;

    @BeforeAll
    static void startWebhookServer() {
        webhookServer = HttpServer.create()
                .port(0)
                .route(routes -> routes
                        .post("/hook", (request, response) -> {
                            hits.incrementAndGet();
                            lastTimestamp.set(request.requestHeaders().get("X-Webhook-Timestamp"));
                            lastSignature.set(request.requestHeaders().get("X-Webhook-Signature"));
                            return request.receive().aggregate().asString()
                                    .doOnNext(lastPayload::set)
                                    .then(response.status(200).sendString(Mono.just("ok")).then());
                        })
                        .post("/hook-fail", (request, response) -> {
                            failHits.incrementAndGet();
                            return request.receive().aggregate().asString()
                                    .doOnNext(lastPayload::set)
                                    .then(response.status(500).sendString(Mono.just("failed")).then());
                        }))
                .bindNow();
        webhookUrl = "http://localhost:" + webhookServer.port() + "/hook";
        webhookFailUrl = "http://localhost:" + webhookServer.port() + "/hook-fail";
    }

    @AfterAll
    static void stopWebhookServer() {
        if (webhookServer != null) {
            webhookServer.disposeNow();
        }
    }

    @DynamicPropertySource
    static void dynamicProps(DynamicPropertyRegistry registry) {
        registry.add("test.webhook.url", () -> webhookUrl);
    }

    @BeforeEach
    void setUp() {
        // 全量套件并行负载下 5s 默认响应超时偶发不够，统一放宽到 30s
        webTestClient = webTestClient.mutate().responseTimeout(java.time.Duration.ofSeconds(30)).build();

        webhookDeliveryLogRepository.deleteAll();
        webhookEndpointRepository.deleteAll();
        hits.set(0);
        failHits.set(0);
        lastPayload.set(null);
        lastTimestamp.set(null);
        lastSignature.set(null);
        adminAccessToken = loginAndGetAccessToken("admin", "admin123");
    }

    @Test
    void shouldCrudWebhookEndpointAndListDeliveries() {
        String url = webhookUrl;

        webTestClient.post()
                .uri("/admin/webhooks")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "name", "alerts-webhook",
                        "url", url,
                        "secret", "top-secret",
                        "enabled", true,
                        "eventTypes", java.util.List.of("alert.triggered"),
                        "retryMax", 0,
                        "timeoutMs", 1000
                ))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").exists()
                .jsonPath("$.secret").isEqualTo("****et");

        webTestClient.get()
                .uri("/admin/webhooks")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.endpoints.length()").isEqualTo(1)
                .jsonPath("$.endpoints[0].name").isEqualTo("alerts-webhook");

        Long endpointId = webhookEndpointRepository.findAll().getFirst().getId();
        webTestClient.get()
                .uri("/admin/webhooks/" + endpointId)
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(endpointId.intValue())
                .jsonPath("$.name").isEqualTo("alerts-webhook")
                .jsonPath("$.secret").isEqualTo("****et");

        webTestClient.put()
                .uri("/admin/webhooks/" + endpointId)
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "name", "alerts-webhook-updated",
                        "url", url,
                        "secret", "new-secret",
                        "enabled", true,
                        "eventTypes", java.util.List.of("*"),
                        "retryMax", 0,
                        "timeoutMs", 1000
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("alerts-webhook-updated")
                .jsonPath("$.secret").isEqualTo("****et");

        webTestClient.get()
                .uri("/admin/webhooks/" + endpointId)
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("alerts-webhook-updated")
                .jsonPath("$.secret").isEqualTo("****et")
                .jsonPath("$.enabled").isEqualTo(true)
                .jsonPath("$.retryMax").isEqualTo(0);

        webTestClient.get()
                .uri("/admin/alerts")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk();

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(hits.get()).isGreaterThan(0);
            assertThat(webhookDeliveryLogRepository.findTop100ByOrderByCreatedAtDesc()).isNotEmpty();
            assertThat(webhookDeliveryLogRepository.findTop100ByOrderByCreatedAtDesc().getFirst().getStatus()).isEqualTo("delivered");
            assertThat(lastPayload.get()).isNotBlank();
            assertThat(lastTimestamp.get()).isNotBlank();
            assertThat(lastSignature.get()).isEqualTo(sign("new-secret", lastTimestamp.get(), lastPayload.get()));
        });

        webTestClient.get()
                .uri("/admin/webhooks/deliveries")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.deliveries.length()").isEqualTo(1)
                .jsonPath("$.deliveries[0].eventType").isEqualTo("alert.triggered");

        webTestClient.delete()
                .uri("/admin/webhooks/" + endpointId)
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void shouldReturn404WhenUpdatingMissingWebhook() {
        webTestClient.put()
                .uri("/admin/webhooks/999999")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "name", "missing",
                        "url", webhookUrl,
                        "secret", "secret",
                        "enabled", true,
                        "eventTypes", java.util.List.of("alert.triggered"),
                        "retryMax", 0,
                        "timeoutMs", 1000
                ))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("webhook_not_found");
    }

    @Test
    void shouldFullyMaskVeryShortWebhookSecret() {
        webTestClient.post()
                .uri("/admin/webhooks")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "name", "short-secret-webhook",
                        "url", webhookUrl,
                        "secret", "ab",
                        "enabled", true,
                        "eventTypes", java.util.List.of("alert.triggered"),
                        "retryMax", 0,
                        "timeoutMs", 1000
                ))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.secret").isEqualTo("****");
    }

    @Test
    void shouldReturn404WhenGettingMissingWebhook() {
        webTestClient.get()
                .uri("/admin/webhooks/999999")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("webhook_not_found");
    }

    @Test
    void shouldDeliverWithoutSignatureWhenSecretBlank() {
        webTestClient.post()
                .uri("/admin/webhooks")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "name", "unsigned-webhook",
                        "url", webhookUrl,
                        "secret", "   ",
                        "enabled", true,
                        "eventTypes", java.util.List.of("alert.triggered"),
                        "retryMax", 0,
                        "timeoutMs", 1000
                ))
                .exchange()
                .expectStatus().isCreated();

        webTestClient.get()
                .uri("/admin/alerts")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk();

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(hits.get()).isGreaterThan(0);
            assertThat(webhookDeliveryLogRepository.findTop100ByOrderByCreatedAtDesc()).isNotEmpty();
            assertThat(webhookDeliveryLogRepository.findTop100ByOrderByCreatedAtDesc().getFirst().getStatus()).isEqualTo("delivered");
            assertThat(lastTimestamp.get()).isNull();
            assertThat(lastSignature.get()).isNull();
        });
    }

    @Test
    void shouldKeepAlerts200WhenWebhookDownstreamFailsAndPersistFailureLog() {
        webTestClient.post()
                .uri("/admin/webhooks")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "name", "failing-webhook",
                        "url", webhookFailUrl,
                        "secret", "failing-secret",
                        "enabled", true,
                        "eventTypes", java.util.List.of("alert.triggered"),
                        "retryMax", 0,
                        "timeoutMs", 1000
                ))
                .exchange()
                .expectStatus().isCreated();

        webTestClient.get()
                .uri("/admin/alerts")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk();

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(failHits.get()).isGreaterThan(0);
            assertThat(webhookDeliveryLogRepository.findTop100ByOrderByCreatedAtDesc()).isNotEmpty();
            assertThat(webhookDeliveryLogRepository.findTop100ByOrderByCreatedAtDesc().getFirst().getStatus()).isEqualTo("failed");
        });
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

    private String sign(String secret, String timestamp, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
