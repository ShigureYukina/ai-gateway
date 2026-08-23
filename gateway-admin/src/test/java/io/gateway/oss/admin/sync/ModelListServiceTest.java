package io.gateway.oss.admin.sync;

import io.gateway.oss.admin.pricing.BillingPriceResolver;
import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.ModelPricing;
import io.gateway.oss.core.config.PricingConfig;
import io.gateway.oss.core.config.RouteConfig;
import io.gateway.oss.core.upstream.RouteResilienceTracker;
import io.gateway.oss.core.web.ModelsController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelListServiceTest {

    private ProviderModelCatalogService catalogService;

    private GatewayProperties properties;

    @Mock
    private RouteResilienceTracker resilienceTracker;

    @Mock
    private ModelMetadataService metadataService;

    @Mock
    private PublicModelMetadataService publicModelMetadataService;

    private ModelListService service;

    @BeforeEach
    void setUp() {
        catalogService = new ProviderModelCatalogService();
        properties = new GatewayProperties();
        PricingSyncService pricingSyncService = new PricingSyncService();
        service = new ModelListService(
                catalogService,
                properties,
                resilienceTracker,
                new BillingPriceResolver(properties, pricingSyncService),
                metadataService,
                publicModelMetadataService
        );

        properties.setPricing(new PricingConfig());
        properties.setScenes(Map.of());
        properties.setRoutes(Map.of());
    }

    @Test
    void hasData_returnsTrueWhenCatalogHasData() {
        catalogService.replaceSnapshot(Map.of("openai", java.util.Set.of("gpt-4o-mini")),
                Instant.parse("2026-06-04T00:00:00Z"));

        assertTrue(service.hasData());
    }

    @Test
    void hasData_returnsFalseWhenCatalogAndRoutesAreEmpty() {
        properties.setRoutes(Map.of());

        assertFalse(service.hasData());
    }

    @Test
    void buildModels_withSnapshotDataUsesSnapshotPath() {
        RouteConfig localRoute = new RouteConfig();
        localRoute.setProvider("local-provider");
        localRoute.setUpstreamModel("local-model");
        properties.setRoutes(Map.of("local-route", localRoute));
        ModelPricing pricing = new ModelPricing();
        pricing.setUnitPrice(new java.math.BigDecimal("0.123"));
        properties.getPricing().getModels().put("gpt-4o-mini", pricing);
        catalogService.replaceSnapshot(Map.of("openai", java.util.Set.of("gpt-4o-mini")),
                Instant.parse("2026-06-04T00:00:00Z"));
        when(metadataService.getContextLength("gpt-4o-mini")).thenReturn(128000);
        when(metadataService.getMetadata("gpt-4o-mini")).thenReturn(Map.of("vision", true));

        List<ModelsController.ModelObject> models = service.buildModels(null, null);

        assertEquals(1, models.size());
        ModelsController.ModelObject model = models.get(0);
        assertEquals("gpt-4o-mini", model.id());
        assertEquals("snapshot", model.sourceType());
        assertEquals("openai", model.owned_by());
        assertEquals("openai/gpt-4o-mini", model.canonicalId());
        assertEquals(128000, model.contextLength());
        assertTrue(model.capabilities().contains("chat.completions"));
        assertTrue(model.capabilities().contains("vision"));
        assertEquals(new java.math.BigDecimal("0.123"), model.pricing().get("input"));
        assertEquals("manual_override", model.pricing().get("source"));
        assertEquals("manual_override", model.pricing().get("matchedBy"));
        assertEquals("gpt-4o-mini", model.pricing().get("matchedModel"));
    }

    @Test
    void buildModels_filtersByProviderAndModelName() {
        catalogService.replaceSnapshot(Map.of(
                "openai", java.util.Set.of("gpt-4o", "gpt-4o-mini"),
                "anthropic", java.util.Set.of("claude-3-5-sonnet")
        ), Instant.parse("2026-06-04T00:00:00Z"));
        when(metadataService.getMetadata(org.mockito.ArgumentMatchers.anyString())).thenReturn(Map.of());
        when(metadataService.getContextLength(org.mockito.ArgumentMatchers.anyString())).thenReturn(0);

        List<ModelsController.ModelObject> models = service.buildModels("openai", "gpt-4o-mini");

        assertEquals(1, models.size());
        assertEquals("gpt-4o-mini", models.get(0).id());
        assertEquals("openai", models.get(0).owned_by());
        assertEquals("snapshot", models.get(0).sourceType());
    }

    @Test
    void buildModels_exposesResolvedSyncedPricingMetadata() {
        GatewayProperties localProperties = new GatewayProperties();
        PricingSyncService pricingSyncService = new PricingSyncService();
        pricingSyncService.replaceSnapshot(
                Map.of(),
                Map.of("gpt_4o_mini", new PricingSyncService.ModelPricingEntry(
                        null,
                        new java.math.BigDecimal("0.0002"),
                        new java.math.BigDecimal("0.0007"))),
                Instant.parse("2026-06-04T00:00:00Z")
        );
        ModelListService localService = new ModelListService(
                catalogService,
                localProperties,
                resilienceTracker,
                new BillingPriceResolver(localProperties, pricingSyncService),
                metadataService,
                publicModelMetadataService
        );
        catalogService.replaceSnapshot(Map.of("openai", java.util.Set.of("gpt-4o-mini")),
                Instant.parse("2026-06-04T00:00:00Z"));
        when(metadataService.getMetadata("gpt-4o-mini")).thenReturn(Map.of());
        when(metadataService.getContextLength("gpt-4o-mini")).thenReturn(0);

        ModelsController.ModelObject model = localService.buildModels("openai", "gpt-4o-mini").get(0);

        assertEquals(new java.math.BigDecimal("0.0002"), model.pricing().get("input"));
        assertEquals(new java.math.BigDecimal("0.0007"), model.pricing().get("output"));
        assertEquals("synced_pricing", model.pricing().get("source"));
        assertEquals("fuzzy_name_fallback", model.pricing().get("matchedBy"));
        assertEquals("gpt_4o_mini", model.pricing().get("matchedModel"));
    }
}
