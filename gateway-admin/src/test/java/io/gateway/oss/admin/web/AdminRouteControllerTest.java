package io.gateway.oss.admin.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

class AdminRouteControllerTest extends AdminInMemoryWebIntegrationTestBase {

    @Test
    void shouldReturnRouteList() {
        webTestClient.get()
                .uri("/admin/routes")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.generatedAt").exists()
                .jsonPath("$.routes").isMap()
                .jsonPath("$.routes.openai-primary.provider").isEqualTo("openai")
                .jsonPath("$.routes.openai-primary.upstreamModel").isEqualTo("gpt-4o-mini")
                .jsonPath("$.routes.gpt-4o-mini.scene").isEqualTo("default-chat");
    }

    @Test
    void shouldReturn401WhenListingRoutesWithoutAuth() {
        webTestClient.get()
                .uri("/admin/routes")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void shouldCreateNewRoute() {
        webTestClient.put()
                .uri("/admin/routes/new-route")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "provider", "openai",
                        "upstreamModel", "gpt-4.1",
                        "upstreamModels", List.of("gpt-4.1", "gpt-4o-mini"),
                        "scene", "default-chat",
                        "strategy", "weighted-random",
                        "fallbackRoutes", List.of("openai-primary"),
                        "weight", 3,
                        "enabled", true
                ))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.provider").isEqualTo("openai")
                .jsonPath("$.upstreamModel").isEqualTo("gpt-4.1")
                .jsonPath("$.weight").isEqualTo(3)
                .jsonPath("$.enabled").isEqualTo(true);
    }

    @Test
    void shouldUpdateExistingRoute() {
        webTestClient.put()
                .uri("/admin/routes/openai-primary")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "provider", "openai",
                        "upstreamModel", "gpt-4.1",
                        "scene", "default-chat",
                        "strategy", "round-robin",
                        "fallbackRoutes", List.of(),
                        "weight", 2,
                        "enabled", false
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.provider").isEqualTo("openai")
                .jsonPath("$.upstreamModel").isEqualTo("gpt-4.1")
                .jsonPath("$.weight").isEqualTo(2)
                .jsonPath("$.enabled").isEqualTo(false);
    }

    @Test
    void shouldDeleteRoute() {
        webTestClient.put()
                .uri("/admin/routes/delete-route")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "provider", "openai",
                        "upstreamModel", "gpt-4o-mini",
                        "scene", "default-chat",
                        "strategy", "round-robin",
                        "weight", 1,
                        "enabled", true
                ))
                .exchange()
                .expectStatus().isCreated();

        webTestClient.delete()
                .uri("/admin/routes/delete-route")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void shouldReturn404WhenDeletingMissingRoute() {
        webTestClient.delete()
                .uri("/admin/routes/missing-route")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isNotFound();
    }
}
