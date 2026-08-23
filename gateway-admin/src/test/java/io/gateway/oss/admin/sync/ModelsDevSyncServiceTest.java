package io.gateway.oss.admin.sync;

import io.gateway.oss.core.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelsDevSyncServiceTest {

    @Test
    void shouldSyncCatalogAndPricingOnSuccess() {
        GatewayProperties properties = new GatewayProperties();
        properties.getSync().getModelsDev().setEnabled(true);

        ModelsDevClient client = mock(ModelsDevClient.class);
        ProviderModelCatalogService catalogService = new ProviderModelCatalogService();
        PricingSyncService pricingSyncService = new PricingSyncService();

        Map<String, Set<String>> providerModels = new LinkedHashMap<>();
        providerModels.put("openai", new LinkedHashSet<>(Set.of("gpt-4o-mini", "gpt-4.1-mini")));
        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        prices.put("gpt-4o-mini", new BigDecimal("0.00021"));
        Instant now = Instant.now();

        when(client.fetchSnapshot()).thenReturn(reactor.core.publisher.Mono.just(
                new ModelsDevClient.ModelsDevSnapshot(providerModels, prices, new LinkedHashMap<>(), new LinkedHashMap<>(), now)
        ));

        ModelsDevSyncService service = new ModelsDevSyncService(properties, client, catalogService, pricingSyncService, null, null);

        boolean result = Boolean.TRUE.equals(service.syncOnceReactive("test").block());

        assertTrue(result);
        assertEquals(2, catalogService.getModels("openai").size());
        assertEquals(new BigDecimal("0.00021"), pricingSyncService.getUnitPrice("gpt-4o-mini"));
        assertEquals(now, catalogService.getSnapshot().updatedAt());
        assertEquals(now, pricingSyncService.getSnapshot().updatedAt());
    }

    @Test
    void shouldKeepLastSnapshotWhenSyncFails() {
        GatewayProperties properties = new GatewayProperties();
        properties.getSync().getModelsDev().setEnabled(true);

        ModelsDevClient client = mock(ModelsDevClient.class);
        ProviderModelCatalogService catalogService = new ProviderModelCatalogService();
        PricingSyncService pricingSyncService = new PricingSyncService();

        catalogService.replaceSnapshot(Map.of("openai", Set.of("gpt-4o-mini")), Instant.parse("2026-01-01T00:00:00Z"));
        pricingSyncService.replaceSnapshot(Map.of("gpt-4o-mini", new BigDecimal("0.00010")), Instant.parse("2026-01-01T00:00:00Z"));

        when(client.fetchSnapshot()).thenReturn(reactor.core.publisher.Mono.error(new RuntimeException("boom")));

        ModelsDevSyncService service = new ModelsDevSyncService(properties, client, catalogService, pricingSyncService, null, null);

        boolean result = Boolean.TRUE.equals(service.syncOnceReactive("test").block());

        assertFalse(result);
        assertTrue(catalogService.hasModel("openai", "gpt-4o-mini"));
        assertEquals(new BigDecimal("0.00010"), pricingSyncService.getUnitPrice("gpt-4o-mini"));
    }

    @Test
    void shouldReturnFailedAndKeepLastSnapshotWhenPersistenceFails() {
        GatewayProperties properties = new GatewayProperties();
        properties.getSync().getModelsDev().setEnabled(true);

        ModelsDevClient client = mock(ModelsDevClient.class);
        ProviderModelCatalogService catalogService = new ProviderModelCatalogService();
        PricingSyncService pricingSyncService = new PricingSyncService();
        ProviderModelPersistenceService persistenceService = mock(ProviderModelPersistenceService.class);

        Instant oldAt = Instant.parse("2026-01-01T00:00:00Z");
        catalogService.replaceSnapshot(Map.of("openai", Set.of("gpt-4o-mini")), oldAt);
        pricingSyncService.replaceSnapshot(Map.of("gpt-4o-mini", new BigDecimal("0.00010")), oldAt);

        Instant newAt = Instant.parse("2026-02-01T00:00:00Z");
        Map<String, Set<String>> providerModels = new LinkedHashMap<>();
        providerModels.put("openai", new LinkedHashSet<>(Set.of("gpt-4.1-mini")));
        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        prices.put("gpt-4.1-mini", new BigDecimal("0.00021"));

        when(client.fetchSnapshot()).thenReturn(Mono.just(
                new ModelsDevClient.ModelsDevSnapshot(providerModels, prices, new LinkedHashMap<>(), new LinkedHashMap<>(), newAt)
        ));
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(persistenceService).persistFromSnapshot(any());

        ModelsDevSyncService service = new ModelsDevSyncService(
                properties,
                client,
                catalogService,
                pricingSyncService,
                persistenceService,
                null
        );

        boolean result = Boolean.TRUE.equals(service.syncOnceReactive("test").block());

        assertFalse(result);
        assertTrue(catalogService.hasModel("openai", "gpt-4o-mini"));
        assertFalse(catalogService.hasModel("openai", "gpt-4.1-mini"));
        assertEquals(new BigDecimal("0.00010"), pricingSyncService.getUnitPrice("gpt-4o-mini"));
        assertEquals(oldAt, catalogService.getSnapshot().updatedAt());
        assertEquals(oldAt, pricingSyncService.getSnapshot().updatedAt());
    }

    @Test
    void shouldSyncDualPricingThroughToPricingSyncService() {
        GatewayProperties properties = new GatewayProperties();
        properties.getSync().getModelsDev().setEnabled(true);
        properties.getSync().getModelsDev().setPreferRemotePricing(true);

        ModelsDevClient client = mock(ModelsDevClient.class);
        ProviderModelCatalogService catalogService = new ProviderModelCatalogService();
        PricingSyncService pricingSyncService = new PricingSyncService();

        Map<String, Set<String>> providerModels = new LinkedHashMap<>();
        providerModels.put("openai", new LinkedHashSet<>(Set.of("gpt-4o")));
        Map<String, BigDecimal> modelPrices = new LinkedHashMap<>();
        modelPrices.put("gpt-4o", new BigDecimal("0.0005")); // input price
        Map<String, Map<String, Object>> modelMetadata = new LinkedHashMap<>();
        modelMetadata.put("gpt-4o", Map.of("output_price", new BigDecimal("0.0015")));
        Map<String, PricingSyncService.ModelPricingEntry> modelPricings = new LinkedHashMap<>();
        modelPricings.put("gpt-4o", new PricingSyncService.ModelPricingEntry(
                new BigDecimal("0.0005"),   // unitPrice (fallback)
                new BigDecimal("0.0005"),   // inputUnitPrice
                new BigDecimal("0.0015")    // outputUnitPrice
        ));
        Instant now = Instant.now();

        when(client.fetchSnapshot()).thenReturn(Mono.just(
                new ModelsDevClient.ModelsDevSnapshot(providerModels, modelPrices, modelPricings, modelMetadata, now)
        ));

        ModelsDevSyncService service = new ModelsDevSyncService(properties, client, catalogService, pricingSyncService, null, null);
        boolean result = Boolean.TRUE.equals(service.syncOnceReactive("test").block());

        assertTrue(result);
        // Verify dual pricing stored in PricingSyncService
        assertEquals(new BigDecimal("0.0005"), pricingSyncService.getInputUnitPrice("gpt-4o"),
                "inputUnitPrice should match the synced input price");
        assertEquals(new BigDecimal("0.0015"), pricingSyncService.getOutputUnitPrice("gpt-4o"),
                "outputUnitPrice should match the synced output price from metadata");
    }

    @Test
    void shouldFallBackToInputPriceWhenOutputPriceMissing() {
        GatewayProperties properties = new GatewayProperties();
        properties.getSync().getModelsDev().setEnabled(true);

        ModelsDevClient client = mock(ModelsDevClient.class);
        ProviderModelCatalogService catalogService = new ProviderModelCatalogService();
        PricingSyncService pricingSyncService = new PricingSyncService();

        Map<String, Set<String>> providerModels = new LinkedHashMap<>();
        providerModels.put("openai", new LinkedHashSet<>(Set.of("gpt-4o-mini")));
        Map<String, BigDecimal> modelPrices = new LinkedHashMap<>();
        modelPrices.put("gpt-4o-mini", new BigDecimal("0.00021"));
        Instant now = Instant.now();

        // No modelPricings passed (single-price model) — PricingSyncService will
        // auto-create ModelPricingEntry from the flat modelPrices map.
        when(client.fetchSnapshot()).thenReturn(Mono.just(
                new ModelsDevClient.ModelsDevSnapshot(providerModels, modelPrices, new LinkedHashMap<>(), new LinkedHashMap<>(), now)
        ));

        ModelsDevSyncService service = new ModelsDevSyncService(properties, client, catalogService, pricingSyncService, null, null);
        boolean result = Boolean.TRUE.equals(service.syncOnceReactive("test").block());

        assertTrue(result);
        // For single-price models, output falls back to input/unit price
        assertEquals(new BigDecimal("0.00021"), pricingSyncService.getInputUnitPrice("gpt-4o-mini"));
        assertEquals(new BigDecimal("0.00021"), pricingSyncService.getOutputUnitPrice("gpt-4o-mini"),
                "output should fallback to input when no dual pricing configured");
    }

    @Test
    void shouldSingleFlightConcurrentSyncTriggers() throws InterruptedException {
        GatewayProperties properties = new GatewayProperties();
        properties.getSync().getModelsDev().setEnabled(true);

        ModelsDevClient client = mock(ModelsDevClient.class);
        ProviderModelCatalogService catalogService = new ProviderModelCatalogService();
        PricingSyncService pricingSyncService = new PricingSyncService();

        Map<String, Set<String>> providerModels = new LinkedHashMap<>();
        providerModels.put("openai", new LinkedHashSet<>(Set.of("gpt-4o-mini")));
        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        prices.put("gpt-4o-mini", new BigDecimal("0.00021"));
        Instant now = Instant.now();

        AtomicInteger fetchCount = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        when(client.fetchSnapshot()).thenAnswer(invocation -> Mono.fromCallable(() -> {
            fetchCount.incrementAndGet();
            if (!release.await(2, TimeUnit.SECONDS)) {
                throw new RuntimeException("timeout");
            }
            return new ModelsDevClient.ModelsDevSnapshot(providerModels, prices, new LinkedHashMap<>(), new LinkedHashMap<>(), now);
        }).subscribeOn(Schedulers.boundedElastic()));

        ModelsDevSyncService service = new ModelsDevSyncService(properties, client, catalogService, pricingSyncService, null, null);

        Mono<Boolean> startup = service.syncOnceReactive("startup").subscribeOn(Schedulers.boundedElastic());
        Mono<Boolean> scheduled = service.syncOnceReactive("scheduled").subscribeOn(Schedulers.boundedElastic());
        Mono<Boolean> admin = service.syncOnceReactive("admin-api", true).subscribeOn(Schedulers.boundedElastic());

        release.countDown();
        Boolean allSuccess = Mono.zip(startup, scheduled, admin)
                .map(tuple -> tuple.getT1() && tuple.getT2() && tuple.getT3())
                .block();

        assertTrue(Boolean.TRUE.equals(allSuccess));
        assertEquals(1, fetchCount.get());
    }
}
