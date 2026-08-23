package io.gateway.oss.admin.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

class AdminSystemConfigControllerTest extends AdminInMemoryWebIntegrationTestBase {

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

    @Test
    void shouldUpdateSystemPricingConfig() {
        webTestClient.put()
                .uri("/admin/system/pricing")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "default", Map.of("unitPrice", 0.0002),
                        "models", Map.of("gpt-4o", Map.of("unitPrice", 0.0003)),
                        "exactMatches", Map.of("alias-model", "openai/gpt-4o")
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.default.unitPrice").isEqualTo(0.0002)
                .jsonPath("$.models.gpt-4o.unitPrice").isEqualTo(0.0003)
                .jsonPath("$.exactMatches.alias-model").isEqualTo("openai/gpt-4o");
    }

    @Test
    void shouldPreviewResolvedPricingDeterministically() {
        webTestClient.put()
                .uri("/admin/system/pricing")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "default", Map.of("unitPrice", 0.0002),
                        "models", Map.of("gpt-4o", Map.of("unitPrice", 0.0003)),
                        "exactMatches", Map.of("alias-model", "gpt-4o")
                ))
                .exchange()
                .expectStatus().isOk();

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/admin/system/pricing/resolve")
                        .queryParam("model", "alias-model")
                        .queryParam("provider", "openai")
                        .queryParam("upstreamModel", "gpt-4o")
                        .build())
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.requestedModel").isEqualTo("alias-model")
                .jsonPath("$.upstreamModel").isEqualTo("gpt-4o")
                .jsonPath("$.provider").isEqualTo("openai")
                .jsonPath("$.matchedModel").isEqualTo("gpt-4o")
                .jsonPath("$.source").isEqualTo("manual_override")
                .jsonPath("$.matchedBy").isEqualTo("manual_override")
                .jsonPath("$.unitPrice").isEqualTo(0.0003)
                .jsonPath("$.inputUnitPrice").isEqualTo(0.0003)
                .jsonPath("$.outputUnitPrice").isEqualTo(0.0003)
                .jsonPath("$.resolved").isEqualTo(true)
                .jsonPath("$.trace.candidates.length()").isEqualTo(2)
                .jsonPath("$.trace.candidates[0]").isEqualTo("alias-model")
                .jsonPath("$.trace.candidates[1]").isEqualTo("gpt-4o")
                .jsonPath("$.trace.attempts.manualOverride.status").isEqualTo("hit")
                .jsonPath("$.trace.attempts.manualOverride.matchedModel").isEqualTo("gpt-4o")
                .jsonPath("$.trace.attempts.exactMapping.status").isEqualTo("skipped")
                .jsonPath("$.trace.attempts.exactMatch.status").isEqualTo("skipped")
                .jsonPath("$.trace.attempts.fuzzyMatch.status").isEqualTo("skipped")
                .jsonPath("$.trace.attempts.defaultApplied.status").isEqualTo("not_applied")
                .jsonPath("$.trace.reason").value(org.hamcrest.Matchers.containsString("manual override"));
    }

    @Test
    void shouldExposeTraceWhenPricingFallsBackToDefault() {
        webTestClient.put()
                .uri("/admin/system/pricing")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "default", Map.of("unitPrice", 0.0002),
                        "models", Map.of("manual-only-model", Map.of("unitPrice", 0.0003)),
                        "exactMatches", Map.of("another-alias", "synced-model")
                ))
                .exchange()
                .expectStatus().isOk();

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/admin/system/pricing/resolve")
                        .queryParam("model", "missing-model")
                        .queryParam("provider", "openai")
                        .queryParam("upstreamModel", "still-missing")
                        .build())
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.source").isEqualTo("configured_default")
                .jsonPath("$.matchedBy").isEqualTo("default_price")
                .jsonPath("$.unitPrice").isEqualTo(0.0002)
                .jsonPath("$.resolved").isEqualTo(true)
                .jsonPath("$.trace.candidates.length()").isEqualTo(2)
                .jsonPath("$.trace.attempts.manualOverride.status").isEqualTo("miss")
                .jsonPath("$.trace.attempts.exactMapping.status").isEqualTo("miss")
                .jsonPath("$.trace.attempts.exactMatch.status").isEqualTo("miss")
                .jsonPath("$.trace.attempts.fuzzyMatch.status").isEqualTo("miss")
                .jsonPath("$.trace.attempts.defaultApplied.status").isEqualTo("applied")
                .jsonPath("$.trace.reason").value(org.hamcrest.Matchers.containsString("回落到默认价格"));
    }

    @Test
    void shouldUpdateSystemOperationalConfig() {
        webTestClient.put()
                .uri("/admin/system/operational")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "maintenanceMode", true,
                        "emergencyRateLimit", Map.of(
                                "enabled", true,
                                "maxRequestsPerMinute", 25
                        ),
                        "maintenanceWhitelist", List.of("admin", "ops")
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.maintenanceMode").isEqualTo(true)
                .jsonPath("$.emergencyRateLimit.enabled").isEqualTo(true)
                .jsonPath("$.emergencyRateLimit.maxRequestsPerMinute").isEqualTo(25)
                .jsonPath("$.maintenanceWhitelist.length()").isEqualTo(2);
    }

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
                                "timeout", "PT10S",
                                "runOnStartup", false,
                                "preferRemotePricing", false
                        )
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.modelsDev.enabled").isEqualTo(true)
                .jsonPath("$.modelsDev.endpoint").isEqualTo("https://models-dev.example.com/api.json")
                .jsonPath("$.modelsDev.refreshInterval").isEqualTo("PT1H")
                .jsonPath("$.modelsDev.timeout").isEqualTo("PT10S")
                .jsonPath("$.modelsDev.runOnStartup").isEqualTo(false)
                .jsonPath("$.modelsDev.preferRemotePricing").isEqualTo(false);
    }

    @Test
    void shouldUpdateSystemProviderHealthConfig() {
        webTestClient.put()
                .uri("/admin/system/provider-health")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "enabled", true,
                        "refreshInterval", "PT10M",
                        "runOnStartup", false,
                        "disableAfterConsecutiveFailures", 5,
                        "recoverAfterConsecutiveSuccesses", 3
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.enabled").isEqualTo(true)
                .jsonPath("$.refreshInterval").isEqualTo("PT10M")
                .jsonPath("$.runOnStartup").isEqualTo(false)
                .jsonPath("$.disableAfterConsecutiveFailures").isEqualTo(5)
                .jsonPath("$.recoverAfterConsecutiveSuccesses").isEqualTo(3);
    }

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

    @Test
    void shouldUpdateSystemLoadBalancerConfig() {
        webTestClient.put()
                .uri("/admin/system/load-balancer")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("enabled", true))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.enabled").isEqualTo(true);
    }

    @Test
    void shouldReturn401WithoutAuthForSystemEndpoints() {
        List<Map.Entry<String, Object>> requests = List.of(
                Map.entry("/admin/system/limit", Map.of("requestsPerWindow", 100, "window", "PT2M")),
                Map.entry("/admin/system/resilience", Map.of("maxAttempts", 3, "retryableFailureThreshold", 5, "failureWindow", "PT1M", "openDuration", "PT1M")),
                Map.entry("/admin/system/pricing", Map.of("default", Map.of("unitPrice", 0.0002), "models", Map.of("gpt-4o", Map.of("unitPrice", 0.0003)))),
                Map.entry("/admin/system/operational", Map.of("maintenanceMode", true, "emergencyRateLimit", Map.of("enabled", true, "maxRequestsPerMinute", 25), "maintenanceWhitelist", List.of("admin"))),
                Map.entry("/admin/system/concurrent-limit", Map.of("enabled", true, "maxPerClient", 5, "maxGlobal", 100)),
                Map.entry("/admin/system/tracing", Map.of("enabled", true, "maxBodySize", 8192, "sampleRate", 0.5)),
                Map.entry("/admin/system/sync", Map.of("modelsDev", Map.of("enabled", true, "endpoint", "https://models-dev.example.com/api.json", "refreshInterval", "PT1H", "timeout", "PT10S"))),
                Map.entry("/admin/system/provider-health", Map.of("enabled", true, "refreshInterval", "PT10M", "disableAfterConsecutiveFailures", 5, "recoverAfterConsecutiveSuccesses", 3)),
                Map.entry("/admin/system/auth", Map.of("enabled", true, "registrationMode", "restricted", "jwt", Map.of("secret", "custom-secret-at-least-32-chars-long-for-test", "accessExpiration", "PT10M", "refreshExpiration", "PT2H"))),
                Map.entry("/admin/system/load-balancer", Map.of("enabled", true))
        );

        for (Map.Entry<String, Object> request : requests) {
            webTestClient.put()
                    .uri(request.getKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request.getValue())
                    .exchange()
                    .expectStatus().isUnauthorized()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo("unauthorized");
        }
    }

    @Test
    void shouldTriggerModelsDevSync() {
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
    void shouldReturnAlerts() {
        webTestClient.put()
                .uri("/admin/routes/disabled-alert-route")
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
                .uri("/admin/alerts")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.generatedAt").exists()
                .jsonPath("$.active.length()").isEqualTo(1)
                .jsonPath("$.active[0].id").isEqualTo("route-disabled:disabled-alert-route")
                .jsonPath("$.active[0].type").isEqualTo("route_disabled")
                .jsonPath("$.recent.length()").isEqualTo(1);
    }
}
