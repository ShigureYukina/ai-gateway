package io.gateway.oss.admin.web;

import io.gateway.oss.admin.sync.PricingSyncService;
import io.gateway.oss.admin.sync.ProviderModelCatalogService;
import io.gateway.oss.core.contract.security.ClientPrincipal;
import io.gateway.oss.core.observability.GatewayMetricsRecorder;
import io.gateway.oss.core.security.AuthorizationService;
import io.gateway.oss.core.security.ClientAuthService;
import io.gateway.oss.core.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;

@WebFluxTest(useDefaultFilters = false)
@Import({InternalModelsSnapshotController.class, InternalEndpointAuthFilter.class, GlobalExceptionHandler.class})
class InternalModelsSnapshotControllerTest {

    private static final String ADMIN_BEARER_TOKEN = "Bearer admin-token";
    private static final ClientPrincipal ADMIN_PRINCIPAL = new ClientPrincipal("admin", null, "admin");

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private ProviderModelCatalogService catalogService;

    @MockBean
    private PricingSyncService pricingSyncService;

    @MockBean
    private AuthorizationService authorizationService;

    @MockBean
    private ClientAuthService clientAuthService;

    @MockBean
    private GatewayMetricsRecorder gatewayMetricsRecorder;

    @MockBean
    private WebTestCleanupSupport webTestCleanupSupport;

    @BeforeEach
    void setUp() {
        when(clientAuthService.authenticate(ADMIN_BEARER_TOKEN)).thenReturn(ADMIN_PRINCIPAL);
        when(catalogService.getSnapshot()).thenReturn(ProviderModelCatalogService.CatalogSnapshot.empty());
        when(pricingSyncService.getSnapshot()).thenReturn(PricingSyncService.PricingSnapshot.empty());
    }

    @Test
    void shouldReturnEmptySnapshotWhenCatalogNotSyncedYet() {
        webTestClient.get().uri("/internal/catalog/providers")
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .value(body -> {
                    if (body.get("fetchedAt") != null) throw new AssertionError("fetchedAt should be null when unsynced");
                    if (body.get("version") != null) throw new AssertionError("version should be null when unsynced");
                    if (!"models.dev".equals(body.get("source"))) throw new AssertionError("source mismatch");
                    Object providerModels = body.get("providerModels");
                    if (!(providerModels instanceof Map<?, ?> map) || !map.isEmpty()) {
                        throw new AssertionError("providerModels should be empty when unsynced");
                    }
                });
    }

    @Test
    void shouldReturnCatalogSnapshotWithMetadata() {
        when(catalogService.getSnapshot()).thenReturn(new ProviderModelCatalogService.CatalogSnapshot(
                Map.of(
                        "openai", List.of("gpt-4o", "gpt-4o-mini"),
                        "anthropic", List.of("claude-3-5-sonnet")
                ),
                Instant.parse("2026-04-28T08:00:00Z"),
                1L
        ));

        webTestClient.get().uri("/internal/catalog/providers")
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.source").isEqualTo("models.dev")
                .jsonPath("$.version").isEqualTo(1)
                .jsonPath("$.fetchedAt").isEqualTo("2026-04-28T08:00:00Z")
                .jsonPath("$.providerModels.openai[0]").isEqualTo("gpt-4o")
                .jsonPath("$.providerModels.openai[1]").isEqualTo("gpt-4o-mini")
                .jsonPath("$.providerModels.anthropic[0]").isEqualTo("claude-3-5-sonnet");
    }

    @Test
    void shouldSupportProviderAndModelFiltersForCatalogAndPricing() {
        Instant syncedAt = Instant.parse("2026-04-28T09:00:00Z");
        when(catalogService.getSnapshot()).thenReturn(new ProviderModelCatalogService.CatalogSnapshot(
                Map.of(
                        "openai", List.of("gpt-4o", "gpt-4o-mini"),
                        "anthropic", List.of("claude-3-5-sonnet")
                ),
                syncedAt,
                1L
        ));

        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        prices.put("gpt-4o-mini", new BigDecimal("0.000120"));
        prices.put("claude-3-5-sonnet", new BigDecimal("0.000300"));
        when(pricingSyncService.getSnapshot()).thenReturn(new PricingSyncService.PricingSnapshot(
                Map.copyOf(prices),
                Map.of(
                        "gpt-4o-mini", new PricingSyncService.ModelPricingEntry(new BigDecimal("0.000120"), null, null),
                        "claude-3-5-sonnet", new PricingSyncService.ModelPricingEntry(new BigDecimal("0.000300"), null, null)
                ),
                syncedAt,
                1L
        ));

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/internal/catalog/providers")
                        .queryParam("provider", "openai")
                        .queryParam("model", "gpt-4o-mini")
                        .build())
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.providerModels.openai.length()").isEqualTo(1)
                .jsonPath("$.providerModels.openai[0]").isEqualTo("gpt-4o-mini")
                .jsonPath("$.providerModels.anthropic").doesNotExist();

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/internal/pricing/models")
                        .queryParam("model", "gpt-4o-mini")
                        .build())
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.source").isEqualTo("models.dev")
                .jsonPath("$.version").isEqualTo(1)
                .jsonPath("$.fetchedAt").isEqualTo("2026-04-28T09:00:00Z")
                .jsonPath("$.modelUnitPrices.gpt-4o-mini").isEqualTo(0.000120)
                .jsonPath("$.modelUnitPrices.claude-3-5-sonnet").doesNotExist();
    }

    @Test
    void shouldReturnAggregatedModelsPricingSnapshot() {
        Instant syncedAt = Instant.parse("2026-04-28T10:00:00Z");
        when(catalogService.getSnapshot()).thenReturn(new ProviderModelCatalogService.CatalogSnapshot(
                Map.of(
                        "openai", List.of("gpt-4o", "gpt-4o-mini"),
                        "anthropic", List.of("claude-3-5-sonnet")
                ),
                syncedAt,
                1L
        ));

        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        prices.put("gpt-4o-mini", new BigDecimal("0.000120"));
        prices.put("claude-3-5-sonnet", new BigDecimal("0.000300"));
        when(pricingSyncService.getSnapshot()).thenReturn(new PricingSyncService.PricingSnapshot(
                Map.copyOf(prices),
                Map.of(
                        "gpt-4o-mini", new PricingSyncService.ModelPricingEntry(new BigDecimal("0.000120"), null, null),
                        "claude-3-5-sonnet", new PricingSyncService.ModelPricingEntry(new BigDecimal("0.000300"), null, null)
                ),
                syncedAt,
                1L
        ));

        webTestClient.get().uri("/internal/snapshots/models-pricing")
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.generatedAt").exists()
                .jsonPath("$.catalog.source").isEqualTo("models.dev")
                .jsonPath("$.catalog.version").isEqualTo(1)
                .jsonPath("$.catalog.fetchedAt").isEqualTo("2026-04-28T10:00:00Z")
                .jsonPath("$.catalog.providerModels.openai[0]").isEqualTo("gpt-4o")
                .jsonPath("$.catalog.providerModels.openai[1]").isEqualTo("gpt-4o-mini")
                .jsonPath("$.pricing.source").isEqualTo("models.dev")
                .jsonPath("$.pricing.version").isEqualTo(1)
                .jsonPath("$.pricing.fetchedAt").isEqualTo("2026-04-28T10:00:00Z")
                .jsonPath("$.pricing.modelUnitPrices.gpt-4o-mini").isEqualTo(0.000120)
                .jsonPath("$.pricing.modelUnitPrices.claude-3-5-sonnet").isEqualTo(0.000300)
                .jsonPath("$.catalog.api-key").doesNotExist()
                .jsonPath("$.catalog.keys").doesNotExist()
                .jsonPath("$.catalog.secret").doesNotExist()
                .jsonPath("$.pricing.api-key").doesNotExist()
                .jsonPath("$.pricing.keys").doesNotExist()
                .jsonPath("$.pricing.secret").doesNotExist();
    }

    @Test
    void shouldReturnEmptyAggregatedSnapshotWhenNotSyncedYet() {
        webTestClient.get().uri("/internal/snapshots/models-pricing")
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .value(body -> {
                    Object generatedAt = body.get("generatedAt");
                    if (generatedAt == null) {
                        throw new AssertionError("generatedAt should exist");
                    }

                    Object catalogObj = body.get("catalog");
                    if (!(catalogObj instanceof Map<?, ?> catalog)) {
                        throw new AssertionError("catalog should be an object");
                    }
                    if (!"models.dev".equals(catalog.get("source"))) {
                        throw new AssertionError("catalog source mismatch");
                    }
                    if (catalog.get("version") != null) {
                        throw new AssertionError("catalog version should be null when unsynced");
                    }
                    if (catalog.get("fetchedAt") != null) {
                        throw new AssertionError("catalog fetchedAt should be null when unsynced");
                    }
                    Object providerModels = catalog.get("providerModels");
                    if (!(providerModels instanceof Map<?, ?> providerModelsMap) || !providerModelsMap.isEmpty()) {
                        throw new AssertionError("catalog providerModels should be empty when unsynced");
                    }

                    Object pricingObj = body.get("pricing");
                    if (!(pricingObj instanceof Map<?, ?> pricing)) {
                        throw new AssertionError("pricing should be an object");
                    }
                    if (!"models.dev".equals(pricing.get("source"))) {
                        throw new AssertionError("pricing source mismatch");
                    }
                    if (pricing.get("version") != null) {
                        throw new AssertionError("pricing version should be null when unsynced");
                    }
                    if (pricing.get("fetchedAt") != null) {
                        throw new AssertionError("pricing fetchedAt should be null when unsynced");
                    }
                    Object modelUnitPrices = pricing.get("modelUnitPrices");
                    if (!(modelUnitPrices instanceof Map<?, ?> modelUnitPricesMap) || !modelUnitPricesMap.isEmpty()) {
                        throw new AssertionError("pricing modelUnitPrices should be empty when unsynced");
                    }
                });
    }

    @Test
    void shouldFilterAggregatedSnapshotByProviderAndModel() {
        Instant syncedAt = Instant.parse("2026-04-28T11:00:00Z");
        when(catalogService.getSnapshot()).thenReturn(new ProviderModelCatalogService.CatalogSnapshot(
                Map.of(
                        "openai", List.of("gpt-4o", "gpt-4o-mini"),
                        "anthropic", List.of("claude-3-5-sonnet")
                ),
                syncedAt,
                1L
        ));

        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        prices.put("gpt-4o-mini", new BigDecimal("0.000120"));
        prices.put("claude-3-5-sonnet", new BigDecimal("0.000300"));
        when(pricingSyncService.getSnapshot()).thenReturn(new PricingSyncService.PricingSnapshot(
                Map.copyOf(prices),
                Map.of(
                        "gpt-4o-mini", new PricingSyncService.ModelPricingEntry(new BigDecimal("0.000120"), null, null),
                        "claude-3-5-sonnet", new PricingSyncService.ModelPricingEntry(new BigDecimal("0.000300"), null, null)
                ),
                syncedAt,
                1L
        ));

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/internal/snapshots/models-pricing")
                        .queryParam("provider", "openai")
                        .queryParam("model", "gpt-4o-mini")
                        .build())
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.catalog.providerModels.openai.length()").isEqualTo(1)
                .jsonPath("$.catalog.providerModels.openai[0]").isEqualTo("gpt-4o-mini")
                .jsonPath("$.catalog.providerModels.anthropic").doesNotExist()
                .jsonPath("$.pricing.modelUnitPrices.gpt-4o-mini").isEqualTo(0.000120)
                .jsonPath("$.pricing.modelUnitPrices.claude-3-5-sonnet").doesNotExist();

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/internal/snapshots/models-pricing")
                        .queryParam("provider", "openai")
                        .queryParam("model", "not-exists")
                        .build())
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.catalog.providerModels").isMap()
                .jsonPath("$.pricing.modelUnitPrices").isMap();
    }
}
