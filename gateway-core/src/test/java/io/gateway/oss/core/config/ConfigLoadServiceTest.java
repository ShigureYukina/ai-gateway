package io.gateway.oss.core.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoadServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    private GatewayProperties properties;
    private FakeConfigStore configStore;
    private ConfigLoadService service;

    @BeforeEach
    void setUp() {
        properties = new GatewayProperties();
        configStore = new FakeConfigStore();
        service = new ConfigLoadService(configStore, properties, objectMapper);
    }

    @Test
    void shouldAuthoritativelyReplaceYamlDefaultsForAllConfigMaps() {
        ProviderConfig defaultProvider = new ProviderConfig();
        defaultProvider.setBaseUrl("https://yaml-provider.example.com");
        defaultProvider.setApiKey("yaml-key");
        Map<String, ProviderConfig> providers = new LinkedHashMap<>();
        providers.put("yaml-provider", defaultProvider);
        properties.setProviders(providers);

        RouteConfig aliasRoute = new RouteConfig();
        aliasRoute.setScene("default-chat");
        RouteConfig concreteRoute = new RouteConfig();
        concreteRoute.setProvider("yaml-provider");
        concreteRoute.setUpstreamModel("gpt-4o-mini");
        Map<String, RouteConfig> routes = new LinkedHashMap<>();
        routes.put("gpt-4o-mini", aliasRoute);
        routes.put("openai-primary", concreteRoute);
        properties.setRoutes(routes);

        SceneConfig defaultScene = new SceneConfig();
        defaultScene.setPrimaryRoute("openai-primary");
        Map<String, SceneConfig> scenes = new LinkedHashMap<>();
        scenes.put("default-chat", defaultScene);
        properties.setScenes(scenes);

        ClientConfig defaultClient = new ClientConfig();
        defaultClient.getAllowedModels().add("gpt-4o-mini");
        Map<String, ClientConfig> clients = new LinkedHashMap<>();
        clients.put("demo-client-key", defaultClient);
        properties.setClients(clients);

        ProviderConfig storedProvider = new ProviderConfig();
        storedProvider.setBaseUrl("https://store-provider.example.com");
        storedProvider.setApiKey("store-key");
        configStore.put(DynamicConfigService.TYPE_PROVIDERS, "openai-primary", toJson(storedProvider));

        RouteConfig storedConcreteRoute = new RouteConfig();
        storedConcreteRoute.setProvider("openai-primary");
        storedConcreteRoute.setUpstreamModel("gpt-4.1-mini");
        configStore.put(DynamicConfigService.TYPE_ROUTES, "openai-primary", toJson(storedConcreteRoute));

        SceneConfig storedScene = new SceneConfig();
        storedScene.setPrimaryRoute("openai-primary");
        configStore.put(DynamicConfigService.TYPE_SCENES, "store-scene", toJson(storedScene));

        ClientConfig storedClient = new ClientConfig();
        storedClient.getAllowedModels().add("gpt-4.1-mini");
        configStore.put(DynamicConfigService.TYPE_CLIENTS, "store-client-key", toJson(storedClient));

        service.loadAll().block();

        assertEquals(1, properties.getProviders().size());
        assertTrue(properties.getProviders().containsKey("openai-primary"));

        assertEquals(1, properties.getRoutes().size());
        assertTrue(properties.getRoutes().containsKey("openai-primary"));
        assertEquals("gpt-4.1-mini", properties.getRoutes().get("openai-primary").getUpstreamModel());

        assertEquals(1, properties.getScenes().size());
        assertTrue(properties.getScenes().containsKey("store-scene"));

        assertEquals(1, properties.getClients().size());
        assertTrue(properties.getClients().containsKey("store-client-key"));
    }

    @Test
    void shouldClearYamlDefaultsWhenStoreIsEmpty() {
        ProviderConfig defaultProvider = new ProviderConfig();
        defaultProvider.setBaseUrl("https://yaml-provider.example.com");
        defaultProvider.setApiKey("yaml-key");
        Map<String, ProviderConfig> providers = new LinkedHashMap<>();
        providers.put("yaml-provider", defaultProvider);
        properties.setProviders(providers);

        RouteConfig aliasRoute = new RouteConfig();
        aliasRoute.setScene("default-chat");
        Map<String, RouteConfig> routes = new LinkedHashMap<>();
        routes.put("gpt-4o-mini", aliasRoute);
        properties.setRoutes(routes);

        service.loadAll().block();

        assertEquals(1, properties.getProviders().size());
        assertTrue(properties.getProviders().containsKey("yaml-provider"));
        assertEquals(1, properties.getRoutes().size());
        assertTrue(properties.getRoutes().containsKey("gpt-4o-mini"));
    }

    @Test
    void shouldKeepYamlSystemDefaultsWhenStoreIsEmpty() {
        LimitConfig limitConfig = new LimitConfig();
        limitConfig.setRequestsPerWindow(99);
        properties.setLimit(limitConfig);

        TraceConfig traceConfig = new TraceConfig();
        traceConfig.setEnabled(true);
        traceConfig.setMaxBodySize(2048);
        properties.setTracing(traceConfig);

        service.loadAll().block();

        assertEquals(99, properties.getLimit().getRequestsPerWindow());
        assertEquals(2048, properties.getTracing().getMaxBodySize());
    }

    @Test
    void shouldApplyStoredSystemOverridesWithoutResettingOtherYamlValues() {
        LimitConfig limitConfig = new LimitConfig();
        limitConfig.setRequestsPerWindow(99);
        properties.setLimit(limitConfig);

        AuthConfig authConfig = new AuthConfig();
        authConfig.setEnabled(true);
        properties.setAuth(authConfig);

        TraceConfig traceConfig = new TraceConfig();
        traceConfig.setEnabled(true);
        traceConfig.setMaxBodySize(2048);
        properties.setTracing(traceConfig);

        LimitConfig storedLimit = new LimitConfig();
        storedLimit.setRequestsPerWindow(123);
        configStore.put(DynamicConfigService.TYPE_SYSTEM, DynamicConfigService.KEY_LIMIT, toJson(storedLimit));

        service.loadAll().block();

        assertEquals(123, properties.getLimit().getRequestsPerWindow());
        assertTrue(properties.getAuth().isEnabled());
        assertEquals(2048, properties.getTracing().getMaxBodySize());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private static class FakeConfigStore implements ConfigStore {
        private final Map<String, Map<String, String>> data = new LinkedHashMap<>();

        void put(String type, String key, String jsonValue) {
            data.computeIfAbsent(type, ignored -> new LinkedHashMap<>()).put(key, jsonValue);
        }

        @Override
        public Mono<Void> save(String configType, String key, String jsonValue) {
            put(configType, key, jsonValue);
            return Mono.empty();
        }

        @Override
        public Mono<String> load(String configType, String key) {
            return Mono.justOrEmpty(data.getOrDefault(configType, Map.of()).get(key));
        }

        @Override
        public Mono<Void> delete(String configType, String key) {
            Map<String, String> byType = data.get(configType);
            if (byType != null) {
                byType.remove(key);
            }
            return Mono.empty();
        }

        @Override
        public Mono<Boolean> saveIfAbsentOrReplaceExpired(String configType, String key, String jsonValue, Duration ttl) {
            return save(configType, key, jsonValue).thenReturn(true);
        }

        @Override
        public Mono<Map<String, String>> loadAll(String configType) {
            return Mono.just(new LinkedHashMap<>(data.getOrDefault(configType, Map.of())));
        }
    }
}
