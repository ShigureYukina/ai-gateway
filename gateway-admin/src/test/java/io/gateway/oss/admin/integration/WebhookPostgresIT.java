package io.gateway.oss.admin.integration;

import io.gateway.oss.admin.repository.WebhookDeliveryLogRepository;
import io.gateway.oss.admin.repository.WebhookEndpointRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookPostgresIT extends IntegrationTestBase {

    private static DisposableServer webhookServer;
    private static String webhookUrl;
    private static final AtomicInteger hits = new AtomicInteger();

    @Autowired
    private WebhookEndpointRepository webhookEndpointRepository;

    @Autowired
    private WebhookDeliveryLogRepository webhookDeliveryLogRepository;

    @BeforeAll
    static void startWebhookServer() {
        webhookServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/hook", (request, response) -> {
                    hits.incrementAndGet();
                    return request.receive().aggregate().asString()
                            .then(response.status(200).sendString(Mono.just("ok")).then());
                }))
                .bindNow();
        webhookUrl = "http://localhost:" + webhookServer.port() + "/hook";
    }

    @AfterAll
    static void stopWebhookServer() {
        if (webhookServer != null) {
            webhookServer.disposeNow();
        }
    }

    @Test
    void postgresWebhookJsonbAndMinimalFlowProof() {
        hits.set(0);
        String adminToken = loginAsAdmin();

        webTestClient.post()
                .uri("/admin/webhooks")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "name", "pg-webhook",
                        "url", webhookUrl,
                        "secret", "pg-secret",
                        "enabled", true,
                        "eventTypes", List.of("alert.triggered"),
                        "retryMax", 0,
                        "timeoutMs", 1000
                ))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").exists();

        Long endpointId = webhookEndpointRepository.findAll().getFirst().getId();

        webTestClient.put()
                .uri("/admin/webhooks/" + endpointId)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "name", "pg-webhook-updated",
                        "url", webhookUrl,
                        "secret", "pg-secret-updated",
                        "enabled", true,
                        "eventTypes", List.of("alert.triggered", "*"),
                        "retryMax", 0,
                        "timeoutMs", 1000
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.eventTypes.length()").isEqualTo(2);

        var persistedEndpoint = webhookEndpointRepository.findById(endpointId).orElseThrow();
        assertThat(persistedEndpoint.getEventTypes())
                .containsExactly("alert.triggered", "*");

        String eventTypesJson = jdbcTemplate.queryForObject(
                "select event_types::text from webhook_endpoint where id = ?",
                String.class,
                endpointId
        );
        assertThat(eventTypesJson).isNotBlank();
        assertThat(eventTypesJson).contains("alert.triggered");
        assertThat(eventTypesJson).contains("*");

        webTestClient.get()
                .uri("/admin/alerts")
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk();

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(hits.get()).isGreaterThan(0);
            assertThat(webhookDeliveryLogRepository.findTop100ByOrderByCreatedAtDesc()).isNotEmpty();
            assertThat(webhookDeliveryLogRepository.findTop100ByOrderByCreatedAtDesc().getFirst().getStatus()).isEqualTo("delivered");
        });
    }
}
