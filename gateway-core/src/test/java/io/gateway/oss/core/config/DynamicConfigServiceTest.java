package io.gateway.oss.core.config;

import io.gateway.oss.core.routing.ModelRouteResolver;
import io.gateway.oss.core.routing.RouteLoadBalancer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DynamicConfigServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    private InMemoryConfigStore configStore;
    private GatewayProperties properties;
    private DynamicConfigService service;

    @BeforeEach
    void setUp() {
        configStore = new InMemoryConfigStore();
        properties = new GatewayProperties();
        service = createService(null, null, null, null);
    }

    private DynamicConfigService createService(RouteLoadBalancer routeLoadBalancer,
                                               io.gateway.oss.core.upstream.Resilience4jCircuitBreakerService resilienceService,
                                               ModelRouteResolver modelRouteResolver,
                                               ConfigSyncPublisher syncPublisher) {
        return new DynamicConfigService(
                configStore,
                properties,
                objectMapper,
                null,
                null,
                new ConfigLoadService(configStore, properties, objectMapper),
                RuntimeRefreshHooks.of(routeLoadBalancer, resilienceService, modelRouteResolver),
                syncPublisher
        );
    }

    // ─── init: empty store preserves YAML defaults ───

    @Test
    void shouldPreserveYamlDefaultsWhenStoreIsEmpty() {
        // YAML defaults: providers have openai + anthropic, routes have 3, clients have demo-client-key
        int originalProviderCount = properties.getProviders().size();
        int originalRouteCount = properties.getRoutes().size();

        service.init();

        assertEquals(originalProviderCount, properties.getProviders().size());
        assertEquals(originalRouteCount, properties.getRoutes().size());
    }

    // ─── saveProvider ───

    @Test
    void shouldSaveProviderAndPersistToStore() {
        ProviderConfig config = new ProviderConfig();
        config.setBaseUrl("http://new-provider:8080");
        config.setApiKey("new-key");

        StepVerifier.create(service.saveProvider("new-provider", config))
                .verifyComplete();

        // Memory updated
        assertTrue(properties.getProviders().containsKey("new-provider"));
        assertEquals("http://new-provider:8080",
                properties.getProviders().get("new-provider").getBaseUrl());

        // Store persisted
        StepVerifier.create(configStore.load("providers", "new-provider"))
                .assertNext(json -> assertTrue(json.contains("new-provider")))
                .verifyComplete();
    }

    @Test
    void shouldOverwriteExistingProvider() {
        ProviderConfig config1 = new ProviderConfig();
        config1.setBaseUrl("http://v1:8080");
        config1.setApiKey("key1");

        ProviderConfig config2 = new ProviderConfig();
        config2.setBaseUrl("http://v2:8080");
        config2.setApiKey("key2");

        StepVerifier.create(service.saveProvider("test", config1)).verifyComplete();
        StepVerifier.create(service.saveProvider("test", config2)).verifyComplete();

        assertEquals("http://v2:8080", properties.getProviders().get("test").getBaseUrl());

        StepVerifier.create(configStore.load("providers", "test"))
                .assertNext(json -> assertTrue(json.contains("v2")))
                .verifyComplete();
    }

    // ─── deleteProvider ───

    @Test
    void shouldDeleteProviderFromMemoryAndStore() {
        ProviderConfig config = new ProviderConfig();
        config.setBaseUrl("http://temp:8080");
        config.setApiKey("temp-key");

        StepVerifier.create(service.saveProvider("temp", config)).verifyComplete();
        assertTrue(properties.getProviders().containsKey("temp"));

        StepVerifier.create(service.deleteProvider("temp")).verifyComplete();

        assertFalse(properties.getProviders().containsKey("temp"));
        StepVerifier.create(configStore.load("providers", "temp")).verifyComplete();
    }

    @Test
    void shouldDeleteNonExistentProviderWithoutError() {
        StepVerifier.create(service.deleteProvider("never-existed")).verifyComplete();
    }

    // ─── saveRoute ───

    @Test
    void shouldSaveRouteAndPersistToStore() {
        RouteConfig config = new RouteConfig();
        config.setProvider("openai");
        config.setUpstreamModel("gpt-4o");

        StepVerifier.create(service.saveRoute("new-route", config))
                .verifyComplete();

        assertTrue(properties.getRoutes().containsKey("new-route"));
        assertEquals("openai", properties.getRoutes().get("new-route").getProvider());

        StepVerifier.create(configStore.load("routes", "new-route"))
                .assertNext(json -> assertTrue(json.contains("gpt-4o")))
                .verifyComplete();
    }

    // ─── deleteRoute ───

    @Test
    void shouldDeleteRouteFromMemoryAndStore() {
        RouteConfig config = new RouteConfig();
        config.setProvider("openai");
        config.setUpstreamModel("gpt-4o");

        StepVerifier.create(service.saveRoute("temp-route", config)).verifyComplete();
        StepVerifier.create(service.deleteRoute("temp-route")).verifyComplete();

        assertFalse(properties.getRoutes().containsKey("temp-route"));
    }

    // ─── saveClient ───

    @Test
    void shouldSaveClientAndPersistToStore() {
        ClientConfig config = new ClientConfig();
        config.setEnabled(true);
        config.getAllowedModels().add("gpt-4o");

        StepVerifier.create(service.saveClient("new-client-key", config))
                .verifyComplete();

        assertTrue(properties.getClients().containsKey("new-client-key"));
        assertTrue(properties.getClients().get("new-client-key").getAllowedModels().contains("gpt-4o"));

        StepVerifier.create(configStore.load("clients", "new-client-key"))
                .assertNext(json -> assertTrue(json.contains("gpt-4o")))
                .verifyComplete();
    }

    // ─── deleteClient ───

    @Test
    void shouldDeleteClientFromMemoryAndStore() {
        ClientConfig config = new ClientConfig();
        config.setEnabled(true);

        StepVerifier.create(service.saveClient("temp-key", config)).verifyComplete();
        StepVerifier.create(service.deleteClient("temp-key")).verifyComplete();

        assertFalse(properties.getClients().containsKey("temp-key"));
    }

    // ─── saveSystemLimit ───

    @Test
    void shouldSaveSystemLimitAndPersistToStore() {
        LimitConfig config = new LimitConfig();
        config.setRequestsPerWindow(100);
        config.setWindow(Duration.ofMinutes(5));

        StepVerifier.create(service.saveSystemLimit(config))
                .verifyComplete();

        // System config now updates memory immediately (hot reload)
        assertEquals(100, properties.getLimit().getRequestsPerWindow());
        assertFalse(service.getPendingSystemKeys().contains("limit"));

        StepVerifier.create(configStore.load("system", "limit"))
                .assertNext(json -> assertTrue(json.contains("100")))
                .verifyComplete();
    }

    // ─── saveSystemResilience ───

    @Test
    void shouldSaveSystemResilienceAndPersistToStore() {
        ResilienceConfig config = new ResilienceConfig();
        config.setMaxAttempts(5);
        config.setRetryableFailureThreshold(10);

        StepVerifier.create(service.saveSystemResilience(config))
                .verifyComplete();

        // System config now updates memory immediately (hot reload)
        assertEquals(5, properties.getResilience().getMaxAttempts());
        assertFalse(service.getPendingSystemKeys().contains("resilience"));

        StepVerifier.create(configStore.load("system", "resilience"))
                .assertNext(json -> assertTrue(json.contains("5")))
                .verifyComplete();
    }

    // ─── saveSystemPricing ───

    @Test
    void shouldSaveSystemPricingAndPersistToStore() {
        PricingConfig config = new PricingConfig();
        config.getDefault().setUnitPrice(new java.math.BigDecimal("0.0002"));
        config.getExactMatches().put("alias-model", "openai/gpt-4o");

        StepVerifier.create(service.saveSystemPricing(config))
                .verifyComplete();

        // System config now updates memory immediately (hot reload)
        assertEquals(new java.math.BigDecimal("0.0002"),
                properties.getPricing().getDefault().getUnitPrice());
        assertEquals("openai/gpt-4o", properties.getPricing().getExactMatches().get("alias-model"));
        assertFalse(service.getPendingSystemKeys().contains("pricing"));

        StepVerifier.create(configStore.load("system", "pricing"))
                .assertNext(json -> {
                    assertTrue(json.contains("0.0002"));
                    assertTrue(json.contains("alias-model"));
                    assertTrue(json.contains("openai/gpt-4o"));
                })
                .verifyComplete();
    }

    // ─── init: load from store ───

    @Test
    void shouldLoadFromStoreOnInit() {
        // Pre-populate store with a provider
        ProviderConfig config = new ProviderConfig();
        config.setBaseUrl("http://stored-provider:8080");
        config.setApiKey("stored-key");
        try {
            String json = objectMapper.writeValueAsString(config);
            configStore.save("providers", "stored-provider", json).block();
        } catch (Exception e) {
            fail("JSON serialization failed", e);
        }

        // Init should load from store and add to properties
        service.init();

        assertTrue(properties.getProviders().containsKey("stored-provider"));
        assertEquals("http://stored-provider:8080",
                properties.getProviders().get("stored-provider").getBaseUrl());
    }

    @Test
    void shouldLoadRoutesFromStoreOnInit() {
        RouteConfig config = new RouteConfig();
        config.setProvider("openai");
        config.setUpstreamModel("gpt-4-turbo");
        try {
            String json = objectMapper.writeValueAsString(config);
            configStore.save("routes", "loaded-route", json).block();
        } catch (Exception e) {
            fail("JSON serialization failed", e);
        }

        service.init();

        assertTrue(properties.getRoutes().containsKey("loaded-route"));
        assertEquals("gpt-4-turbo",
                properties.getRoutes().get("loaded-route").getUpstreamModel());
    }

    @Test
    void shouldLoadClientsFromStoreOnInit() {
        ClientConfig config = new ClientConfig();
        config.setEnabled(true);
        config.getAllowedModels().add("claude-3");
        try {
            String json = objectMapper.writeValueAsString(config);
            configStore.save("clients", "loaded-client-key", json).block();
        } catch (Exception e) {
            fail("JSON serialization failed", e);
        }

        service.init();

        assertTrue(properties.getClients().containsKey("loaded-client-key"));
        assertTrue(properties.getClients().get("loaded-client-key").getAllowedModels().contains("claude-3"));
    }

    @Test
    void shouldLoadSystemLimitFromStoreOnInit() {
        LimitConfig config = new LimitConfig();
        config.setRequestsPerWindow(200);
        config.setWindow(Duration.ofMinutes(10));
        try {
            String json = objectMapper.writeValueAsString(config);
            configStore.save("system", "limit", json).block();
        } catch (Exception e) {
            fail("JSON serialization failed", e);
        }

        service.init();

        assertEquals(200, properties.getLimit().getRequestsPerWindow());
        assertEquals(Duration.ofMinutes(10), properties.getLimit().getWindow());
    }

    @Test
    void shouldLoadSystemResilienceFromStoreOnInit() {
        ResilienceConfig config = new ResilienceConfig();
        config.setMaxAttempts(7);
        try {
            String json = objectMapper.writeValueAsString(config);
            configStore.save("system", "resilience", json).block();
        } catch (Exception e) {
            fail("JSON serialization failed", e);
        }

        service.init();

        assertEquals(7, properties.getResilience().getMaxAttempts());
    }

    @Test
    void shouldLoadSystemPricingFromStoreOnInit() {
        PricingConfig config = new PricingConfig();
        config.getDefault().setUnitPrice(new java.math.BigDecimal("0.0005"));
        try {
            String json = objectMapper.writeValueAsString(config);
            configStore.save("system", "pricing", json).block();
        } catch (Exception e) {
            fail("JSON serialization failed", e);
        }

        service.init();

        assertEquals(new java.math.BigDecimal("0.0005"),
                properties.getPricing().getDefault().getUnitPrice());
    }

    // ─── init: store error should not prevent startup ───

    @Test
    void shouldFallbackToYamlDefaultsWhenStoreLoadFails() {
        ConfigStore failingStore = new ConfigStore() {
            @Override
            public Mono<Void> save(String configType, String key, String jsonValue) {
                return Mono.error(new RuntimeException("Redis down"));
            }

            @Override
            public Mono<String> load(String configType, String key) {
                return Mono.error(new RuntimeException("Redis down"));
            }

            @Override
            public Mono<Void> delete(String configType, String key) {
                return Mono.error(new RuntimeException("Redis down"));
            }

            @Override
            public Mono<Boolean> saveIfAbsentOrReplaceExpired(String configType, String key, String jsonValue, Duration ttl) {
                return save(configType, key, jsonValue).thenReturn(true);
            }

            @Override
            public Mono<Map<String, String>> loadAll(String configType) {
                return Mono.error(new RuntimeException("Redis down"));
            }
        };

        // 预先给 properties 添加 YAML 模拟默认值
        ProviderConfig defaultProvider = new ProviderConfig();
        defaultProvider.setBaseUrl("http://default:8080");
        defaultProvider.setApiKey("default-key");
        Map<String, ProviderConfig> providers = new LinkedHashMap<>();
        providers.put("default-provider", defaultProvider);
        properties.setProviders(providers);

        DynamicConfigService failingService = new DynamicConfigService(
                failingStore,
                properties,
                objectMapper,
                null,
                null,
                new ConfigLoadService(failingStore, properties, objectMapper),
                RuntimeRefreshHooks.noop(),
                null
        );

        // Should not throw
        failingService.init();

        // YAML defaults should still be present
        assertTrue(properties.getProviders().containsKey("default-provider"));
        assertEquals("http://default:8080",
                properties.getProviders().get("default-provider").getBaseUrl());
    }

    // ─── init: merged behavior (store overrides YAML) ───

    @Test
    void shouldMergeStoreValuesWithYamlDefaults() {
        // YAML has openai provider; store adds a new one
        ProviderConfig newProvider = new ProviderConfig();
        newProvider.setBaseUrl("http://store-only:8080");
        newProvider.setApiKey("store-key");
        try {
            String json = objectMapper.writeValueAsString(newProvider);
            configStore.save("providers", "store-only", json).block();
        } catch (Exception e) {
            fail("JSON serialization failed", e);
        }

        int originalSize = properties.getProviders().size();

        service.init();

        // Original YAML providers + store-only
        assertEquals(originalSize + 1, properties.getProviders().size());
        assertTrue(properties.getProviders().containsKey("store-only"));
    }

    @Test
    void shouldApplyImportedConfigAtomicallyWithoutChangingMemoryOnStoreFailure() {
        AtomicInteger saveCount = new AtomicInteger();
        ConfigStore failingStore = new ConfigStore() {
            private final Map<String, Map<String, String>> store = new LinkedHashMap<>();

            @Override
            public Mono<Void> save(String configType, String key, String jsonValue) {
                if (saveCount.incrementAndGet() == 2) {
                    return Mono.error(new RuntimeException("boom"));
                }
                store.computeIfAbsent(configType, ignored -> new LinkedHashMap<>()).put(key, jsonValue);
                return Mono.empty();
            }

            @Override
            public Mono<String> load(String configType, String key) {
                return Mono.justOrEmpty(store.getOrDefault(configType, Map.of()).get(key));
            }

            @Override
            public Mono<Void> delete(String configType, String key) {
                store.getOrDefault(configType, Map.of()).remove(key);
                return Mono.empty();
            }

            @Override
            public Mono<Boolean> saveIfAbsentOrReplaceExpired(String configType, String key, String jsonValue, Duration ttl) {
                return save(configType, key, jsonValue).thenReturn(true);
            }

            @Override
            public Mono<Map<String, String>> loadAll(String configType) {
                return Mono.just(Map.copyOf(store.getOrDefault(configType, Map.of())));
            }
        };

        DynamicConfigService failingService = new DynamicConfigService(
                failingStore,
                properties,
                objectMapper,
                null,
                null,
                new ConfigLoadService(failingStore, properties, objectMapper),
                RuntimeRefreshHooks.noop(),
                null
        );
        ProviderConfig originalProvider = new ProviderConfig();
        originalProvider.setBaseUrl("http://original:8080");
        originalProvider.setApiKey("original-key");
        properties.setProviders(Map.of("openai", originalProvider));

        ProviderConfig replacement = new ProviderConfig();
        replacement.setBaseUrl("http://replacement:8080");
        replacement.setApiKey("replacement-key");
        RouteConfig newRoute = new RouteConfig();
        newRoute.setProvider("openai");
        newRoute.setUpstreamModel("gpt-4o-mini");

        StepVerifier.create(failingService.applyImportedConfig(
                        Map.of("openai", replacement),
                        Map.of("route-x", newRoute),
                        Map.of(),
                        Map.of(),
                        null,
                        null,
                        null,
                        null))
                .expectError()
                .verify();

        assertEquals("http://original:8080", properties.getProviders().get("openai").getBaseUrl());
        assertFalse(properties.getRoutes().containsKey("route-x"));
    }

    // ─── routeLoadBalancer notification ───

    @Test
    void saveRouteShouldNotifyLoadBalancer() {
        RouteLoadBalancer mockBalancer = mock(RouteLoadBalancer.class);
        service = createService(mockBalancer, null, null, null);

        RouteConfig config = new RouteConfig();
        config.setProvider("openai");
        config.setUpstreamModel("gpt-4o-mini");

        StepVerifier.create(service.saveRoute("r-new", config))
                .verifyComplete();

        verify(mockBalancer, times(1)).onConfigChange();
        assertTrue(properties.getRoutes().containsKey("r-new"));
    }

    @Test
    void deleteRouteShouldNotifyLoadBalancer() {
        RouteLoadBalancer mockBalancer = mock(RouteLoadBalancer.class);
        service = createService(mockBalancer, null, null, null);

        RouteConfig config = new RouteConfig();
        config.setProvider("openai");
        config.setUpstreamModel("gpt-4o-mini");
        var routes = new LinkedHashMap<String, RouteConfig>();
        routes.put("r-del", config);
        properties.setRoutes(routes);

        StepVerifier.create(service.deleteRoute("r-del"))
                .verifyComplete();

        verify(mockBalancer, times(1)).onConfigChange();
        assertFalse(properties.getRoutes().containsKey("r-del"));
    }

    @Test
    void saveRouteShouldNotFailWhenLoadBalancerAbsent() {
        // routeLoadBalancer is null by default — should be a no-op
        RouteConfig config = new RouteConfig();
        config.setProvider("openai");
        config.setUpstreamModel("gpt-4o-mini");

        StepVerifier.create(service.saveRoute("r-no-lb", config))
                .verifyComplete();

        assertTrue(properties.getRoutes().containsKey("r-no-lb"));
    }

    // ─── system config hot reload ───

    @Test
    void saveSystemLimit_updatesMemoryImmediately() {
        LimitConfig config = new LimitConfig();
        config.setRequestsPerWindow(42);
        config.setWindow(Duration.ofMinutes(1));

        StepVerifier.create(service.saveSystemLimit(config))
                .verifyComplete();

        assertEquals(42, properties.getLimit().getRequestsPerWindow());
        assertEquals(Duration.ofMinutes(1), properties.getLimit().getWindow());
    }

    @Test
    void saveSystemResilience_updatesMemoryAndResetsResilienceService() {
        io.gateway.oss.core.upstream.Resilience4jCircuitBreakerService mockR4j =
                mock(io.gateway.oss.core.upstream.Resilience4jCircuitBreakerService.class);
        service = createService(null, mockR4j, null, null);

        ResilienceConfig config = new ResilienceConfig();
        config.setOpenDuration(java.time.Duration.ofSeconds(99));

        StepVerifier.create(service.saveSystemResilience(config))
                .verifyComplete();

        assertEquals(java.time.Duration.ofSeconds(99), properties.getResilience().getOpenDuration());
        verify(mockR4j, times(1)).resetResilience();
    }

    @Test
    void saveSystemPricing_updatesMemoryImmediately() {
        PricingConfig config = new PricingConfig();
        ModelPricing mp = new ModelPricing();
        mp.setUnitPrice(new BigDecimal("0.0005"));
        config.getModels().put("test-model", mp);

        StepVerifier.create(service.saveSystemPricing(config))
                .verifyComplete();

        assertEquals(new BigDecimal("0.0005"), properties.getPricing().getModels().get("test-model").getUnitPrice());
    }

    @Test
    void saveSystemOperational_updatesMemoryImmediately() {
        OperationalConfig config = new OperationalConfig();
        config.setMaintenanceMode(true);

        StepVerifier.create(service.saveSystemOperational(config))
                .verifyComplete();

        assertTrue(properties.getOperational().isMaintenanceMode());
    }

    @Test
    void saveSystemResilience_notFailWhenResilienceServiceAbsent() {
        // resilienceService is null by default — should be a no-op
        ResilienceConfig config = new ResilienceConfig();
        config.setOpenDuration(java.time.Duration.ofSeconds(30));

        StepVerifier.create(service.saveSystemResilience(config))
                .verifyComplete();

        assertEquals(java.time.Duration.ofSeconds(30), properties.getResilience().getOpenDuration());
    }

    @Test
    void saveSystemLoadBalancer_updatesMemoryImmediately() {
        LoadBalancerConfig config = new LoadBalancerConfig();
        config.setEnabled(true);

        StepVerifier.create(service.saveSystemLoadBalancer(config))
                .verifyComplete();

        assertTrue(properties.getLoadBalancer().isEnabled());
    }

    @Test
    void saveSystemLoadBalancer_persistsAndHasAudit() {
        LoadBalancerConfig config = new LoadBalancerConfig();
        config.setEnabled(true);

        StepVerifier.create(service.saveSystemLoadBalancer(config))
                .verifyComplete();

        // Verify persisted in store
        StepVerifier.create(configStore.load("system", "load-balancer"))
                .assertNext(json -> assertTrue(json.contains("true")))
                .verifyComplete();
    }

    @Test
    void deleteRouteShouldResetModelRouteResolverWhenProvided() {
        RouteConfig config = new RouteConfig();
        config.setProvider("openai");
        config.setUpstreamModel("gpt-4o-mini");
        properties.setRoutes(new LinkedHashMap<>(Map.of("route-1", config)));
        ModelRouteResolver modelRouteResolver = mock(ModelRouteResolver.class);
        service = createService(null, null, modelRouteResolver, null);

        StepVerifier.create(service.deleteRoute("route-1"))
                .verifyComplete();

        verify(modelRouteResolver).resetCache();
    }

    @Test
    void saveProviderShouldPublishSyncWhenPublisherProvided() {
        ConfigSyncPublisher syncPublisher = mock(ConfigSyncPublisher.class);
        service = createService(null, null, null, syncPublisher);
        ProviderConfig config = new ProviderConfig();
        config.setBaseUrl("http://new-provider:8080");
        config.setApiKey("new-key");

        StepVerifier.create(service.saveProvider("new-provider", config))
                .verifyComplete();

        verify(syncPublisher).publish(DynamicConfigService.TYPE_PROVIDERS);
    }
}
