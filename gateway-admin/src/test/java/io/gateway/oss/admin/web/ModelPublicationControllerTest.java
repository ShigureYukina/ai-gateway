package io.gateway.oss.admin.web;

import io.gateway.oss.admin.sync.PricingSyncService;
import io.gateway.oss.admin.sync.ProviderModelCatalogService;
import io.gateway.oss.core.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelPublicationControllerTest extends AdminInMemoryWebIntegrationTestBase {

    @Autowired
    private PricingSyncService pricingSyncService;

    @Autowired
    private ProviderModelCatalogService providerModelCatalogService;

    @Autowired
    private GatewayProperties gatewayProperties;

    @Test
    void shouldPublishAliasAndPersistExactPriceMatch() {
        pricingSyncService.replaceSnapshot(
                Map.of(),
                Map.of("gpt-4o-mini", new PricingSyncService.ModelPricingEntry(
                        null,
                        new BigDecimal("0.00015"),
                        new BigDecimal("0.00060")
                )),
                Instant.parse("2026-06-05T10:00:00Z")
        );

        webTestClient.put()
                .uri("/admin/publications/support-bot")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("provider", "openai", "upstreamModel", "gpt-4o-mini"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.alias").isEqualTo("support-bot")
                .jsonPath("$.provider").isEqualTo("openai")
                .jsonPath("$.upstreamModel").isEqualTo("gpt-4o-mini")
                .jsonPath("$.visibleInV1Models").isEqualTo(true)
                .jsonPath("$.price.source").isEqualTo("synced_pricing")
                .jsonPath("$.price.matchedBy").isEqualTo("exact_mapping")
                .jsonPath("$.price.matchedModel").isEqualTo("gpt-4o-mini")
                .jsonPath("$.price.inputUnitPrice").isEqualTo(0.00015)
                .jsonPath("$.price.outputUnitPrice").isEqualTo(0.00060)
                .jsonPath("$.warnings.length()").isEqualTo(0);

        assertEquals("support-bot-scene", gatewayProperties.getRoutes().get("support-bot").getScene());
        assertEquals("openai", gatewayProperties.getRoutes().get("support-bot-primary").getProvider());
        assertEquals("gpt-4o-mini", gatewayProperties.getRoutes().get("support-bot-primary").getUpstreamModel());
        assertEquals("gpt-4o-mini", gatewayProperties.getPricing().getExactMatches().get("support-bot"));
    }

    @Test
    void publicationVisibleInV1ModelsEvenWithSnapshotActive() {
        // Snapshot is present — previously this would shadow published aliases.
        // Now model groups are merged with snapshot, so the alias is visible.
        providerModelCatalogService.replaceSnapshot(
                Map.of("openai", Set.of("gpt-4o-mini")),
                Instant.parse("2026-06-05T11:00:00Z")
        );

        webTestClient.put()
                .uri("/admin/publications/support-bot")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("provider", "openai", "upstreamModel", "gpt-4o-mini"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.visibleInV1Models").isEqualTo(true);
    }

    @Test
    void shouldRejectPublicationWhenUpstreamModelNotKnownByProvider() {
        webTestClient.put()
                .uri("/admin/publications/support-bot")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("provider", "openai", "upstreamModel", "unknown-model"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_request")
                .jsonPath("$.message").isEqualTo("upstreamModel not found in provider catalog or configured models: unknown-model");
    }
}
