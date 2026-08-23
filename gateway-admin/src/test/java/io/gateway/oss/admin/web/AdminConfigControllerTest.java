package io.gateway.oss.admin.web;

import io.gateway.oss.core.config.ClientConfig;
import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.observability.RequestLogService;
import io.gateway.oss.core.observability.RequestLogService.RequestLogEntry;
import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.upstream.Resilience4jCircuitBreakerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AdminConfigControllerTest extends AdminNoFlywayWebIntegrationTestBase {

    @Autowired
    private GatewayProperties properties;

    @Autowired
    private RequestLogService requestLogService;

    @Autowired
    private Resilience4jCircuitBreakerService resilience4jCircuitBreakerService;

    // ─── GET /admin/providers ───

    @Test
    void shouldReturnProvidersList() {
        webTestClient.get()
                .uri("/admin/providers")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.generatedAt").exists()
                .jsonPath("$.providers").isMap()
                .jsonPath("$.providers.openai").exists()
                .jsonPath("$.providers.openai.type").isEqualTo("openai-compatible")
                .jsonPath("$.providers.openai.baseUrl").isNotEmpty();
    }

    @Test
    void shouldMaskProviderApiKey() {
        webTestClient.get()
                .uri("/admin/providers")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.providers.openai.apiKey").value(val -> {
                    String value = (String) val;
                    if (value != null && !value.startsWith("****")) {
                        throw new AssertionError("apiKey should be masked, got: " + value);
                    }
                });
    }

    // ─── GET /admin/routes ───

    @Test
    void shouldReturnRoutesList() {
        webTestClient.get()
                .uri("/admin/routes")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.generatedAt").exists()
                .jsonPath("$.routes").isMap()
                .jsonPath("$.routes.gpt-4o-mini").exists()
                .jsonPath("$.routes.gpt-4o-mini.scene").isEqualTo("default-chat")
                .jsonPath("$.routes.openai-primary").exists()
                .jsonPath("$.routes.openai-primary.provider").isEqualTo("openai")
                .jsonPath("$.routes.openai-primary.upstreamModel").isEqualTo("gpt-4o-mini")
                .jsonPath("$.routes.openai-primary.weight").isEqualTo(1)
                .jsonPath("$.routes.openai-primary.enabled").isEqualTo(true);
    }

    @Test
    void shouldReturnRoutesWithFallbackRoutes() {
        webTestClient.get()
                .uri("/admin/routes")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.routes.openai-primary.fallbackRoutes").isArray();
    }

    @Test
    void shouldExposeDisabledRouteInAdminRoutesPayload() {
        webTestClient.put()
                .uri("/admin/routes/disabled-route")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "provider", "openai",
                        "upstreamModel", "gpt-4o-mini",
                        "enabled", false
                ))
                .exchange()
                .expectStatus().isEqualTo(201);

        webTestClient.get()
                .uri("/admin/routes")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.routes['disabled-route'].enabled").isEqualTo(false);
    }

    @Test
    void shouldFreezeUserViaAdminApi() {
        webTestClient.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "freeze-me", "password", "pass"))
                .exchange()
                .expectStatus().isOk();

        webTestClient.put()
                .uri("/admin/users/freeze-me")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("frozen", true))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo("freeze-me")
                .jsonPath("$.frozen").isEqualTo(true)
                .jsonPath("$.frozenAt").isNumber();
    }

    @Test
    void shouldExposeDisabledRouteAlertPayloadAndCoherenceWithAdminRoutes() {
        webTestClient.put()
                .uri("/admin/routes/disabled-alert-route")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("provider", "openai", "upstreamModel", "gpt-4o-mini", "enabled", false))
                .exchange()
                .expectStatus().isEqualTo(201);

        webTestClient.get()
                .uri("/admin/routes")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.routes['disabled-alert-route'].enabled").isEqualTo(false);

        webTestClient.get()
                .uri("/admin/alerts")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.generatedAt").exists()
                .jsonPath("$.active[?(@.type=='route_disabled' && @.source=='disabled-alert-route')]").isNotEmpty()
                .jsonPath("$.active[?(@.type=='route_disabled' && @.source=='disabled-alert-route')].severity").value(values -> org.assertj.core.api.Assertions.assertThat((java.util.List<?>) values).anyMatch("warning"::equals))
                .jsonPath("$.active[?(@.type=='route_disabled' && @.source=='disabled-alert-route')].status").value(values -> org.assertj.core.api.Assertions.assertThat((java.util.List<?>) values).anyMatch("active"::equals))
                .jsonPath("$.active[?(@.type=='route_disabled' && @.source=='disabled-alert-route')].message").value(values -> org.assertj.core.api.Assertions.assertThat((java.util.List<?>) values).anyMatch("Route is disabled"::equals))
                .jsonPath("$.active[?(@.type=='route_disabled' && @.source=='disabled-alert-route')].metadata.routeId").value(values -> org.assertj.core.api.Assertions.assertThat((java.util.List<?>) values).anyMatch("disabled-alert-route"::equals))
                .jsonPath("$.active[?(@.type=='route_disabled' && @.source=='disabled-alert-route')].detectedAt").exists()
                .jsonPath("$.recent[?(@.type=='route_disabled' && @.source=='disabled-alert-route')]").isNotEmpty();
    }

    @Test
    void shouldExposeFrozenAccountAlertPayload() {
        webTestClient.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "alert-user", "password", "pass"))
                .exchange()
                .expectStatus().isOk();

        webTestClient.put()
                .uri("/admin/users/alert-user")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("frozen", true))
                .exchange()
                .expectStatus().isOk();

        webTestClient.get()
                .uri("/admin/alerts")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.active[?(@.type=='account_frozen' && @.source=='alert-user')]").isNotEmpty()
                .jsonPath("$.active[?(@.type=='account_frozen' && @.source=='alert-user')].severity").value(values -> org.assertj.core.api.Assertions.assertThat((java.util.List<?>) values).anyMatch("warning"::equals))
                .jsonPath("$.active[?(@.type=='account_frozen' && @.source=='alert-user')].status").value(values -> org.assertj.core.api.Assertions.assertThat((java.util.List<?>) values).anyMatch("active"::equals))
                .jsonPath("$.active[?(@.type=='account_frozen' && @.source=='alert-user')].metadata.username").value(values -> org.assertj.core.api.Assertions.assertThat((java.util.List<?>) values).anyMatch("alert-user"::equals))
                .jsonPath("$.active[?(@.type=='account_frozen' && @.source=='alert-user')].detectedAt").exists();
    }

    @Test
    void shouldClearRouteDisabledAlertAfterRouteRecovery() {
        webTestClient.put()
                .uri("/admin/routes/recoverable-route")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("provider", "openai", "upstreamModel", "gpt-4o-mini", "enabled", false))
                .exchange()
                .expectStatus().isEqualTo(201);

        webTestClient.get()
                .uri("/admin/alerts")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.active[?(@.type=='route_disabled' && @.source=='recoverable-route')]").isNotEmpty();

        webTestClient.put()
                .uri("/admin/routes/recoverable-route")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("provider", "openai", "upstreamModel", "gpt-4o-mini", "enabled", true))
                .exchange()
                .expectStatus().isOk();

        webTestClient.get()
                .uri("/admin/routes")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.routes['recoverable-route'].enabled").isEqualTo(true);

        webTestClient.get()
                .uri("/admin/alerts")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.active[?(@.type=='route_disabled' && @.source=='recoverable-route')]").isEmpty();
    }

    @Test
    void shouldExposeAndClearCircuitOpenAlert() throws Exception {
        properties.getResilience().setSlidingWindowSize(3);
        properties.getResilience().setWaitDurationInOpenState(java.time.Duration.ofMillis(100));
        properties.getResilience().setPermittedNumberOfCallsInHalfOpenState(1);

        String routeId = "alerts-circuit-route";
        GatewayException upstreamError = new GatewayException(HttpStatus.BAD_GATEWAY, "upstream_error", "boom");

        for (int i = 0; i < 8; i++) {
            resilience4jCircuitBreakerService.decorateMono(routeId, Mono.error(upstreamError))
                    .onErrorResume(ex -> Mono.empty())
                    .block();
        }

        webTestClient.get()
                .uri("/admin/alerts")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.active[?(@.type=='circuit_open' && @.source=='alerts-circuit-route')]").isNotEmpty()
                .jsonPath("$.active[?(@.type=='circuit_open' && @.source=='alerts-circuit-route')].severity").value(values -> org.assertj.core.api.Assertions.assertThat((java.util.List<?>) values).anyMatch("critical"::equals))
                .jsonPath("$.active[?(@.type=='circuit_open' && @.source=='alerts-circuit-route')].status").value(values -> org.assertj.core.api.Assertions.assertThat((java.util.List<?>) values).anyMatch("active"::equals))
                .jsonPath("$.active[?(@.type=='circuit_open' && @.source=='alerts-circuit-route')].message").value(values -> org.assertj.core.api.Assertions.assertThat((java.util.List<?>) values).anyMatch("Circuit breaker is open"::equals))
                .jsonPath("$.active[?(@.type=='circuit_open' && @.source=='alerts-circuit-route')].metadata.routeId").value(values -> org.assertj.core.api.Assertions.assertThat((java.util.List<?>) values).anyMatch(routeId::equals));

        Thread.sleep(180);
        resilience4jCircuitBreakerService.decorateMono(routeId, Mono.just("ok")).block();

        webTestClient.get()
                .uri("/admin/alerts")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.active[?(@.type=='circuit_open' && @.source=='alerts-circuit-route')]").isEmpty();
    }

    // ─── GET /admin/clients ───

    @Test
    void shouldMaskClientApiKey() {
        // demo-client-key should be masked to ****-key
        webTestClient.get()
                .uri("/admin/clients")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.clients['****-key']").exists()
                .jsonPath("$.clients['****-key'].enabled").isEqualTo(true)
                .jsonPath("$.clients['****-key'].allowedModels[0]").isEqualTo("gpt-4o-mini");
    }

    // ─── POST /admin/sync/models-dev ───

    @Test
    void shouldReturnFailedStatusWhenSyncDisabled() {
        // models-dev sync is disabled by default in test config
        webTestClient.post()
                .uri("/admin/sync/models-dev")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.triggeredAt").exists()
                .jsonPath("$.completedAt").exists()
                .jsonPath("$.status").isEqualTo("failed")
                .jsonPath("$.success").isEqualTo(false);
    }

    @Test
    void shouldReturn403ForUserTokenOnAdminProviders() {
        webTestClient.get()
                .uri("/admin/providers")
                .header("Authorization", "Bearer " + userAccessToken)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("forbidden");
    }

    @Test
    void shouldReturn401WhenAuthorizationMissingOnAdminProviders() {
        webTestClient.get()
                .uri("/admin/providers")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("unauthorized");
    }

    @Test
    void shouldSupportClientFilterOnAdminRecentRequests() {
        requestLogService.record(new RequestLogEntry(
                "admin-req-c1",
                "client-A",
                "client-A",
                "gpt-4o-mini",
                "openai",
                "openai-primary",
                "default-chat",
                200,
                10L,
                Instant.now(),
                "non-stream",
                11L,
                8L,
                3L,
                0.001,
                null
        ));
        requestLogService.record(new RequestLogEntry(
                "admin-req-c2",
                "client-B",
                "client-B",
                "gpt-4o-mini",
                "openai",
                "openai-primary",
                "default-chat",
                200,
                10L,
                Instant.now(),
                "non-stream",
                12L,
                10L,
                2L,
                0.002,
                null
        ));

        webTestClient.get()
                .uri("/admin/requests/recent?client=client-A&limit=10")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.requests.length()").isEqualTo(1)
                .jsonPath("$.requests[0].clientId").isEqualTo("client-A");
    }

    @Test
    void shouldExposeFailedRequestInAdminRecentRequestsForOperatorCorrelation() {
        requestLogService.record(new RequestLogEntry(
                "failed-req-id",
                "cli***aa",
                "client-a",
                "gpt-test",
                "provider-a",
                "route-a",
                "scene-a",
                502,
                321L,
                Instant.now(),
                "streaming",
                null,
                null,
                null,
                null,
                "upstream_error"
        ));

        webTestClient.get()
                .uri("/admin/requests/recent?status=502&limit=10")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.requests.length()").isEqualTo(1)
                .jsonPath("$.requests[0].requestId").isEqualTo("failed-req-id")
                .jsonPath("$.requests[0].status").isEqualTo(502)
                .jsonPath("$.requests[0].provider").isEqualTo("provider-a")
                .jsonPath("$.requests[0].routeId").isEqualTo("route-a")
                .jsonPath("$.requests[0].scene").isEqualTo("scene-a")
                .jsonPath("$.requests[0].errorMessage").isEqualTo("upstream_error");
    }

    // ─── PUT /admin/providers/{name} ───

    @Test
    void shouldCreateNewProviderViaPut() {
        webTestClient.put()
                .uri("/admin/providers/new-provider")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "type", "openai-compatible",
                        "baseUrl", "http://203.0.113.10:9000",
                        "apiKey", "new-api-key"
                ))
                .exchange()
                .expectStatus().isEqualTo(201)
                .expectBody()
                .jsonPath("$.type").isEqualTo("openai-compatible")
                .jsonPath("$.baseUrl").isEqualTo("http://203.0.113.10:9000")
                .jsonPath("$.apiKey").isEqualTo("new-api-key");
    }

    @Test
    void shouldUpdateExistingProviderViaPut() {
        // openai provider exists from default config
        webTestClient.put()
                .uri("/admin/providers/openai")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "type", "openai-compatible",
                        "baseUrl", "http://203.0.113.11:9000",
                        "apiKey", "updated-key"
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.type").isEqualTo("openai-compatible")
                .jsonPath("$.baseUrl").isEqualTo("http://203.0.113.11:9000")
                .jsonPath("$.apiKey").isEqualTo("updated-key");
    }

    // ─── DELETE /admin/providers/{name} ───

    @Test
    void shouldDeleteExistingProvider() {
        // First create one to ensure it exists
        webTestClient.put()
                .uri("/admin/providers/deleteme")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "type", "openai-compatible",
                        "baseUrl", "http://203.0.113.12:9000",
                        "apiKey", "temp-key"
                ))
                .exchange()
                .expectStatus().isEqualTo(201);

        // Then delete it
        webTestClient.delete()
                .uri("/admin/providers/deleteme")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void shouldReturn404WhenDeletingNonExistentProvider() {
        webTestClient.delete()
                .uri("/admin/providers/does-not-exist")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void shouldReturn409WhenDeletingProviderWithRouteReferences() {
        // openai provider has routes referencing it (openai-primary, openai-fallback, gpt-4o-mini)
        webTestClient.delete()
                .uri("/admin/providers/openai")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.error").isEqualTo("provider_in_use")
                .jsonPath("$.routes").isArray();
    }

    // ─── PUT /admin/routes/{id} ───

    @Test
    void shouldCreateNewRouteViaPut() {
        webTestClient.put()
                .uri("/admin/routes/new-route")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "provider", "openai",
                        "upstreamModel", "gpt-4o",
                        "weight", 2,
                        "enabled", false
                ))
                .exchange()
                .expectStatus().isEqualTo(201)
                .expectBody()
                .jsonPath("$.provider").isEqualTo("openai")
                .jsonPath("$.upstreamModel").isEqualTo("gpt-4o")
                .jsonPath("$.weight").isEqualTo(2)
                .jsonPath("$.enabled").isEqualTo(false);
    }

    @Test
    void shouldUpdateExistingRouteViaPut() {
        // openai-primary route exists from default config
        webTestClient.put()
                .uri("/admin/routes/openai-primary")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "provider", "openai",
                        "upstreamModel", "gpt-4o-mini",
                        "weight", 5,
                        "enabled", false
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.provider").isEqualTo("openai")
                .jsonPath("$.upstreamModel").isEqualTo("gpt-4o-mini")
                .jsonPath("$.weight").isEqualTo(5)
                .jsonPath("$.enabled").isEqualTo(false);
    }

    // ─── DELETE /admin/routes/{id} ───

    @Test
    void shouldDeleteExistingRoute() {
        // First create one
        webTestClient.put()
                .uri("/admin/routes/temp-route")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "provider", "openai",
                        "upstreamModel", "gpt-4o-mini"
                ))
                .exchange()
                .expectStatus().isEqualTo(201);

        // Then delete it
        webTestClient.delete()
                .uri("/admin/routes/temp-route")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isNoContent();
    }



    // ─── PUT /admin/clients/{key} ───

    @Test
    void shouldCreateNewClientViaPut() {
        webTestClient.put()
                .uri("/admin/clients/new-client-key")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "enabled", true,
                        "allowedModels", List.of("gpt-4o"),
                        "allowedScenes", List.of("default-chat"),
                        "defaults", Map.of("scene", "default-chat", "temperature", 0.5, "maxTokens", 128),
                        "capabilities", Map.of("streaming", true),
                        "limits", Map.of("maxTokens", 256)
                ))
                .exchange()
                .expectStatus().isEqualTo(201)
                .expectBody()
                .jsonPath("$.enabled").isEqualTo(true)
                .jsonPath("$.allowedModels[0]").isEqualTo("gpt-4o")
                .jsonPath("$.allowedScenes[0]").isEqualTo("default-chat")
                .jsonPath("$.defaults.scene").isEqualTo("default-chat")
                .jsonPath("$.defaults.temperature").isEqualTo(0.5)
                .jsonPath("$.defaults.maxTokens").isEqualTo(128)
                .jsonPath("$.capabilities.streaming").isEqualTo(true)
                .jsonPath("$.limits.maxTokens").isEqualTo(256);
    }

    @Test
    void shouldUpdateExistingClientViaPut() {
        webTestClient.put()
                .uri("/admin/clients/demo-client-key")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "enabled", true,
                        "allowedModels", List.of("gpt-4o"),
                        "allowedScenes", List.of("default-chat"),
                        "defaults", Map.of("scene", "default-chat", "temperature", 0.5, "maxTokens", 128),
                        "capabilities", Map.of("streaming", true),
                        "limits", Map.of("maxTokens", 256)
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.enabled").isEqualTo(true)
                .jsonPath("$.allowedModels[0]").isEqualTo("gpt-4o")
                .jsonPath("$.allowedScenes[0]").isEqualTo("default-chat")
                .jsonPath("$.defaults.scene").isEqualTo("default-chat")
                .jsonPath("$.defaults.temperature").isEqualTo(0.5)
                .jsonPath("$.defaults.maxTokens").isEqualTo(128)
                .jsonPath("$.capabilities.streaming").isEqualTo(true)
                .jsonPath("$.limits.maxTokens").isEqualTo(256);
    }

    // ─── DELETE /admin/clients/{key} ───

    @Test
    void shouldDeleteExistingClient() {
        webTestClient.put()
                .uri("/admin/clients/temp-client-key")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "enabled", true,
                        "allowedModels", List.of("gpt-4o"),
                        "allowedScenes", List.of("default-chat"),
                        "defaults", Map.of("scene", "default-chat", "temperature", 0.5, "maxTokens", 128),
                        "capabilities", Map.of("streaming", true),
                        "limits", Map.of("maxTokens", 256)
                ))
                .exchange()
                .expectStatus().isEqualTo(201);

        webTestClient.delete()
                .uri("/admin/clients/temp-client-key")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isNoContent();
    }



    // ─── PUT /admin/system/limit ───

    @Test
    void shouldUpdateSystemLimitConfig() {
        webTestClient.put()
                .uri("/admin/system/limit")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "requestsPerWindow", 100,
                        "window", "PT2M"
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.requestsPerWindow").isEqualTo(100)
                .jsonPath("$.window").isEqualTo("PT2M");
    }

    // ─── PUT /admin/system/resilience ───

    @Test
    void shouldUpdateSystemResilienceConfig() {
        webTestClient.put()
                .uri("/admin/system/resilience")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "maxAttempts", 3,
                        "retryableFailureThreshold", 5,
                        "failureWindow", "PT1M",
                        "openDuration", "PT1M"
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.maxAttempts").isEqualTo(3)
                .jsonPath("$.retryableFailureThreshold").isEqualTo(5)
                .jsonPath("$.failureWindow").isEqualTo("PT1M")
                .jsonPath("$.openDuration").isEqualTo("PT1M");
    }

    // ─── PUT /admin/system/pricing ───

    @Test
    void shouldUpdateSystemPricingConfig() {
        webTestClient.put()
                .uri("/admin/system/pricing")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "default", Map.of("unitPrice", 0.0002),
                        "models", Map.of("gpt-4o", Map.of("unitPrice", 0.0003))
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.default.unitPrice").isEqualTo(0.0002)
                .jsonPath("$.models.gpt-4o.unitPrice").isEqualTo(0.0003);
    }

    // ─── PUT /admin/system/concurrent-limit ───

    @Test
    void shouldUpdateSystemConcurrentLimitConfig() {
        webTestClient.put()
                .uri("/admin/system/concurrent-limit")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "enabled", true,
                        "maxPerClient", 5,
                        "maxGlobal", 100
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.enabled").isEqualTo(true)
                .jsonPath("$.maxPerClient").isEqualTo(5)
                .jsonPath("$.maxGlobal").isEqualTo(100);
    }

    // ─── PUT /admin/system/tracing ───

    @Test
    void shouldUpdateSystemTracingConfig() {
        webTestClient.put()
                .uri("/admin/system/tracing")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "enabled", true,
                        "maxBodySize", 8192,
                        "sampleRate", 0.5
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.enabled").isEqualTo(true)
                .jsonPath("$.maxBodySize").isEqualTo(8192)
                .jsonPath("$.sampleRate").isEqualTo(0.5);
    }

    // ─── PUT /admin/system/sync ───

    @Test
    void shouldUpdateSystemSyncConfig() {
        webTestClient.put()
                .uri("/admin/system/sync")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "modelsDev", Map.of(
                                "enabled", true,
                                "endpoint", "https://models-dev.example.com/api.json",
                                "refreshInterval", "PT1H",
                                "timeout", "PT10S"
                        )
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.modelsDev.enabled").isEqualTo(true)
                .jsonPath("$.modelsDev.endpoint").isEqualTo("https://models-dev.example.com/api.json")
                .jsonPath("$.modelsDev.refreshInterval").isEqualTo("PT1H");
    }

    // ─── PUT /admin/system/provider-health ───

    @Test
    void shouldUpdateSystemProviderHealthConfig() {
        webTestClient.put()
                .uri("/admin/system/provider-health")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "enabled", true,
                        "refreshInterval", "PT10M",
                        "disableAfterConsecutiveFailures", 5,
                        "recoverAfterConsecutiveSuccesses", 3
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.enabled").isEqualTo(true)
                .jsonPath("$.refreshInterval").isEqualTo("PT10M")
                .jsonPath("$.disableAfterConsecutiveFailures").isEqualTo(5)
                .jsonPath("$.recoverAfterConsecutiveSuccesses").isEqualTo(3);
    }

    // ─── PUT /admin/system/auth ───

    @Test
    void shouldUpdateSystemAuthConfig() {
        webTestClient.put()
                .uri("/admin/system/auth")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "enabled", true,
                        "registrationMode", "restricted",
                        "jwt", Map.of(
                                "secret", "custom-secret-at-least-32-chars-long-for-test",
                                "accessExpiration", "PT10M",
                                "refreshExpiration", "PT2H"
                        )
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.enabled").isEqualTo(true)
                .jsonPath("$.registrationMode").isEqualTo("restricted")
                .jsonPath("$.jwt.accessExpiration").isEqualTo("PT10M")
                .jsonPath("$.jwt.refreshExpiration").isEqualTo("PT2H");
    }

    // ─── Auth: user token → 403 on PUT/DELETE ───

    @Test
    void shouldReturn403ForUserTokenOnPutProvider() {
        webTestClient.put()
                .uri("/admin/providers/test")
                .header("Authorization", "Bearer " + userAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "type", "openai-compatible",
                        "baseUrl", "http://203.0.113.13:9000",
                        "apiKey", "key"
                ))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("forbidden");
    }















    // ─── Auth: missing auth → 401 on PUT/DELETE ───

    @Test
    void shouldReturn401WhenAuthMissingOnPutProvider() {
        webTestClient.put()
                .uri("/admin/providers/test")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("type", "openai-compatible", "baseUrl", "http://203.0.113.1", "apiKey", "k"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("unauthorized");
    }















    // ─── Phase 5: /admin/users ───

    @Test
    void shouldListUsersForAdminAndMaskSensitiveFields() {
        registerUser("phase5-user", "pass123");

        webTestClient.get()
                .uri("/admin/users")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    if (!body.contains("\"generatedAt\"")) {
                        throw new AssertionError("generatedAt missing");
                    }
                    if (!body.contains("\"username\":\"phase5-user\"")) {
                        throw new AssertionError("phase5-user missing");
                    }
                    if (!body.contains("\"role\":\"user\"")) {
                        throw new AssertionError("role user missing");
                    }
                    if (!body.contains("\"apiKeyMasked\":\"****")) {
                        throw new AssertionError("apiKeyMasked not masked");
                    }
                    if (!body.contains("\"clientId\":null")) {
                        throw new AssertionError("dynamic user should not claim a client binding");
                    }
                    if (!body.contains("\"ownerUserId\":\"phase5-user\"")) {
                        throw new AssertionError("ownerUserId missing");
                    }
                    if (!body.contains("\"clientName\":null")) {
                        throw new AssertionError("dynamic user clientName should be null");
                    }
                    if (body.contains("passwordHash") || body.contains("\"apiKey\":\"gw-")) {
                        throw new AssertionError("sensitive fields leaked");
                    }
                });
    }

    @Test
    void shouldExposeStableMaskedClientBindingForConfiguredUser() {
        assertThat(properties.getAuth().getUsers()).containsKey("user1");
        assertThat(properties.getAuth().getUsers().get("user1").getClientId()).isEqualTo("demo-client-key");
        assertThat(properties.getClients()).containsKey("demo-client-key");

        webTestClient.get()
                .uri("/admin/users")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    if (!body.contains("\"username\":\"user1\"")) {
                        throw new AssertionError("configured static user missing: " + body);
                    }
                    if (!body.contains("\"username\":\"user1\",\"role\":\"user\",\"apiKeyMasked\":null,\"clientId\":\"****-key\",\"ownerUserId\":\"user1\",\"clientName\":\"demo-client-key\"")) {
                        throw new AssertionError("stable masked client binding missing for configured user: " + body);
                    }
                });
    }

    @Test
    void shouldReturn403ForUserTokenOnAdminUsers() {
        webTestClient.get()
                .uri("/admin/users")
                .header("Authorization", "Bearer " + userAccessToken)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("forbidden");
    }

    @Test
    void shouldUpdateUserRoleSuccessfully() {
        registerUser("promote-user", "pass123");

        webTestClient.put()
                .uri("/admin/users/promote-user")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("role", "admin"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo("promote-user")
                .jsonPath("$.role").isEqualTo("admin");

        webTestClient.get()
                .uri("/admin/users")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    if (!body.contains("\"username\":\"promote-user\"")) {
                        throw new AssertionError("promote-user missing");
                    }
                    if (!body.contains("\"role\":\"admin\"")) {
                        throw new AssertionError("role admin missing");
                    }
                });
    }

    @Test
    void shouldReturn400WhenUpdatingRoleWithInvalidValue() {
        registerUser("invalid-role-user", "pass123");

        webTestClient.put()
                .uri("/admin/users/invalid-role-user")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("role", "super-admin"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_role");
    }



    @Test
    void shouldDeleteUserSuccessfully() {
        registerUser("delete-user", "pass123");

        webTestClient.delete()
                .uri("/admin/users/delete-user")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isNoContent();
    }







    // ─── PUT /admin/users/{username}/limits ───

    @Test
    void shouldUpdateUserLimitsAndPersist() {
        registerUser("limits-user", "pass123");

        webTestClient.put()
                .uri("/admin/users/limits-user/limits")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "dailyTokens", 500000,
                        "monthlyTokens", 15000000,
                        "tokensPerMinute", 50000,
                        "maxTokens", 4096,
                        "dailyCost", 50.0,
                        "monthlyCost", 1500.0
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo("limits-user")
                .jsonPath("$.limits.dailyTokens").isEqualTo(500000)
                .jsonPath("$.limits.monthlyTokens").isEqualTo(15000000)
                .jsonPath("$.limits.tokensPerMinute").isEqualTo(50000)
                .jsonPath("$.limits.maxTokens").isEqualTo(4096)
                .jsonPath("$.limits.dailyCost").isEqualTo(50.0)
                .jsonPath("$.limits.monthlyCost").isEqualTo(1500.0);

        webTestClient.get()
                .uri("/admin/users")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.users[?(@.username=='limits-user')].limits.dailyTokens").isEqualTo(500000)
                .jsonPath("$.users[?(@.username=='limits-user')].limits.monthlyCost").isEqualTo(1500.0);
    }







    // ─── POST /admin/users/{username}/reset-password ───

    @Test
    void shouldResetUserPasswordAndReturnTemporary() {
        registerUser("reset-user", "old-pass");

        webTestClient.post()
                .uri("/admin/users/reset-user/reset-password")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.temporaryPassword").isNotEmpty()
                .jsonPath("$.temporaryPassword").value(v -> assertThat(String.valueOf(v)).isNotEqualTo("old-pass"));
    }







    // ─── Admin API key management ───

    @Test
    void shouldListUserApiKeysViaAdmin() {
        registerUser("key-user", "pass123");

        webTestClient.get()
                .uri("/admin/users/key-user/api-keys")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].keyId").isEqualTo("primary")
                .jsonPath("$[0].enabled").isEqualTo(true)
                .jsonPath("$[0].apiKeyMasked").value(v -> assertThat(String.valueOf(v)).startsWith("****"));
    }

    @Test
    void shouldCreateUserApiKeyViaAdmin() {
        registerUser("admin-key-user", "pass123");

        webTestClient.post()
                .uri("/admin/users/admin-key-user/api-keys")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "admin-created-key", "allowedModels", List.of("gpt-4o-mini")))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.name").isEqualTo("admin-created-key")
                .jsonPath("$.apiKeyMasked").value(v -> assertThat(String.valueOf(v)).startsWith("gw-"))
                .jsonPath("$.allowedModels[0]").isEqualTo("gpt-4o-mini")
                .jsonPath("$.enabled").isEqualTo(true);

        webTestClient.get()
                .uri("/admin/users/admin-key-user/api-keys")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2);
    }

    @Test
    void shouldToggleUserApiKeyViaAdmin() {
        registerUser("toggle-key-user", "pass123");

        webTestClient.put()
                .uri("/admin/users/toggle-key-user/api-keys/primary/toggle")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("enabled", false))
                .exchange()
                .expectStatus().isNoContent();

        webTestClient.get()
                .uri("/admin/users/toggle-key-user/api-keys")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].enabled").isEqualTo(false);
    }

    @Test
    void shouldDeleteUserApiKeyViaAdmin() {
        registerUser("del-key-user", "pass123");

        webTestClient.post()
                .uri("/admin/users/del-key-user/api-keys")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "to-delete"))
                .exchange()
                .expectStatus().isCreated();

        webTestClient.delete()
                .uri("/admin/users/del-key-user/api-keys/primary")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isNoContent();

        webTestClient.get()
                .uri("/admin/users/del-key-user/api-keys")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1);
    }



    // ─── Admin rotate user API key ───

    @Test
    void shouldRotateUserApiKeyAsAdmin() {
        registerUser("rotate-user", "rotate123");
        String token = loginAndGetAccessToken("rotate-user", "rotate123");

        String keyIdBody = webTestClient.get().uri("/auth/keys")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .blockFirst();
        String extractedKeyId = extractJsonValue(keyIdBody, "keyId");

        webTestClient.post().uri("/admin/users/rotate-user/api-keys/" + extractedKeyId + "/rotate")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.keyId").isNotEmpty()
                .jsonPath("$.keyId").value(v -> assertThat((String) v).isNotEqualTo(extractedKeyId))
                .jsonPath("$.apiKey").isNotEmpty()
                .jsonPath("$.enabled").isEqualTo(true)
                .jsonPath("$.name").isEqualTo("default");
    }





    // ─── Admin update user allowed-models ───

    @Test
    void shouldUpdateUserAllowedModelsAsAdmin() {
        registerUser("am-user", "pass123");

        webTestClient.put().uri("/admin/users/am-user/allowed-models")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("allowedModels", List.of("gpt-4o-mini", "gpt-4o")))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.allowedModels").isArray()
                .jsonPath("$.allowedModels.length()").isEqualTo(2);

        webTestClient.get().uri("/admin/users")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.users[?(@.username=='am-user')].allowedModels.length()").isEqualTo(2);
    }



    // ─── Config export/import ───

    @Test
    void shouldExportConfigWithProvidersRoutesScenesClients() {
        webTestClient.get().uri("/admin/config/export")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.providers").exists()
                .jsonPath("$.routes").exists()
                .jsonPath("$.scenes").exists()
                .jsonPath("$.clients").exists()
                .jsonPath("$.system").exists()
                .jsonPath("$.pendingRestart").isArray()
                .jsonPath("$.providers.openai.keys").isArray()
                .jsonPath("$.providers.openai.models").isArray()
                .jsonPath("$.clients['****-key'].allowedModels").isArray()
                .jsonPath("$.clients['****-key'].limits.dailyTokens").isEqualTo(1000)
                .jsonPath("$.clients['****-key'].apiKeyMasked").isEqualTo("****-key");
    }

    @Test
    void shouldExportPendingRestartAfterSystemConfigUpdate() {
        webTestClient.put()
                .uri("/admin/system/limit")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "requestsPerWindow", 222,
                        "window", "PT3M"
                ))
                .exchange()
                .expectStatus().isOk();

        webTestClient.get().uri("/admin/config/export")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.pendingRestart").isArray()
                // limit 是运行时生效的热更新配置，不应出现在 pendingRestart 中
                .jsonPath("$.pendingRestart.length()").isEqualTo(0);
    }

    @Test
    void shouldReturn403ForExportConfigWithoutAdminToken() {
        webTestClient.get().uri("/admin/config/export")
                .header("Authorization", "Bearer " + userAccessToken)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void shouldImportConfigAndPersist() {
        Map<String, Object> body = Map.of("providers", Map.of(), "routes", Map.of());

        webTestClient.post().uri("/admin/config/import")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.imported").isEqualTo(0)
                .jsonPath("$.status").isEqualTo("ok")
                .jsonPath("$.dryRun").isEqualTo(false)
                .jsonPath("$.validated").isEqualTo(true)
                .jsonPath("$.applied").isEqualTo(true)
                .jsonPath("$.errors").isArray()
                .jsonPath("$.errors.length()").isEqualTo(0);
    }

    // ─── Scene management via config import ───

    @Test
    void shouldImportNewSceneAndReflectInExport() {
        // Import a new scene
        Map<String, Object> body = Map.of(
                "scenes", Map.of(
                        "test-scene", Map.of(
                                "primaryRoute", "openai-primary",
                                "fallbackRoutes", List.of("openai-fallback")
                        )
                )
        );

        webTestClient.post().uri("/admin/config/import")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.imported").isEqualTo(1)
                .jsonPath("$.status").isEqualTo("ok");

        // Verify it is reflected in export
        webTestClient.get().uri("/admin/config/export")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.scenes['test-scene'].primaryRoute").isEqualTo("openai-primary");
    }

    // ─── Limit 热更新验证 ───

    @Test
    void shouldApplyLimitHotUpdate() {
        // Update limit via API
        webTestClient.put()
                .uri("/admin/system/limit")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "requestsPerWindow", 50,
                        "window", "PT1M"
                ))
                .exchange()
                .expectStatus().isOk();

        // Verify the change is reflected in export
        webTestClient.get().uri("/admin/config/export")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.system.limit.requestsPerWindow").isEqualTo(50)
                .jsonPath("$.system.limit.window").isEqualTo("PT1M");
    }

    @Test
    void shouldDryRunImportWithoutApplyingChanges() {
        Map<String, Object> body = Map.of(
                "providers", Map.of(
                        "dry-run-provider", Map.of(
                                "type", "openai-compatible",
                                "baseUrl", "https://203.0.113.30",
                                "apiKey", "dry-key"
                        )
                )
        );

        webTestClient.post().uri("/admin/config/import?dryRun=true")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("ok")
                .jsonPath("$.dryRun").isEqualTo(true)
                .jsonPath("$.validated").isEqualTo(true)
                .jsonPath("$.applied").isEqualTo(false)
                .jsonPath("$.errors").isArray()
                .jsonPath("$.errors.length()").isEqualTo(0);

        webTestClient.get().uri("/admin/providers")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.providers['dry-run-provider']").doesNotExist();
    }

    @Test
    void shouldFailApplyImportForInvalidProviderBaseUrlBeforeAnySave() {
        Map<String, Object> body = Map.of(
                "providers", Map.of(
                        "bad-provider", Map.of(
                                "type", "openai-compatible",
                                "baseUrl", "http://127.0.0.1:18080",
                                "apiKey", "bad-key"
                        )
                )
        );

        webTestClient.post().uri("/admin/config/import")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_config_import");

        webTestClient.get().uri("/admin/providers")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.providers['bad-provider']").doesNotExist();
    }

    @Test
    void shouldFailDryRunImportForInvalidRouteReferenceAndNotSave() {
        Map<String, Object> body = Map.of(
                "routes", Map.of(
                        "broken-route", Map.of(
                                "provider", "missing-provider",
                                "upstreamModel", "gpt-4o-mini"
                        )
                )
        );

        webTestClient.post().uri("/admin/config/import?validateOnly=true")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_config_import");

        webTestClient.get().uri("/admin/routes")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.routes['broken-route']").doesNotExist();
    }

    @Test
    void shouldImportMaskedClientKeyWhenItResolvesUniquely() {
        Map<String, Object> body = Map.of(
                "clients", Map.of(
                        "****-key", Map.of(
                                "enabled", true,
                                "allowedModels", List.of("gpt-4o-mini"),
                                "allowedScenes", List.of("default-chat"),
                                "defaults", Map.of("scene", "default-chat", "temperature", 0.6, "maxTokens", 111),
                                "capabilities", Map.of("streaming", true),
                                "limits", Map.of("maxTokens", 333, "dailyTokens", 4321)
                        )
                )
        );

        webTestClient.post().uri("/admin/config/import")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.applied").isEqualTo(true);

        webTestClient.get().uri("/admin/config/export")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.clients['****-key'].limits.dailyTokens").isEqualTo(4321)
                .jsonPath("$.clients['****-key'].defaults.maxTokens").isEqualTo(111);
    }

    @Test
    void shouldRejectImportWhenProviderTimeoutIsInvalid() {
        Map<String, Object> body = Map.of(
                "providers", Map.of(
                        "openai", Map.of(
                                "timeout", "not-a-duration"
                        )
                )
        );

        webTestClient.post().uri("/admin/config/import")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_config_import")
                .jsonPath("$.message").value(message -> assertThat((String) message).contains("providers.openai.timeout"));
    }

    @Test
    void shouldRejectImportWhenRouteWeightIsInvalid() {
        Map<String, Object> body = Map.of(
                "routes", Map.of(
                        "openai-primary", Map.of(
                                "weight", "bad-weight"
                        )
                )
        );

        webTestClient.post().uri("/admin/config/import")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_config_import")
                .jsonPath("$.message").value(message -> assertThat((String) message).contains("routes.openai-primary.weight"));
    }

    @Test
    void shouldRejectMaskedClientKeyImportWhenResolutionIsAmbiguous() {
        ClientConfig otherClient = new ClientConfig();
        otherClient.setEnabled(true);
        java.util.LinkedHashMap<String, ClientConfig> clients = new java.util.LinkedHashMap<>(properties.getClients());
        clients.put("other-key", otherClient);
        properties.setClients(clients);

        Map<String, Object> body = Map.of(
                "clients", Map.of(
                        "****-key", Map.of(
                                "enabled", true,
                                "allowedModels", List.of("gpt-4o-mini")
                        )
                )
        );

        webTestClient.post().uri("/admin/config/import")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_config_import");
    }

    private String extractJsonValue(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int start = json.indexOf(pattern) + pattern.length();
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }

    private void registerUser(String username, String password) {
        webTestClient.post()
                .uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", username, "password", password))
                .exchange()
                .expectStatus().isOk();
    }
}
